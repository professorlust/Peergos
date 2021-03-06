package peergos.shared.user.fs;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FileUploader implements AutoCloseable {
	private static final Logger LOG = Logger.getGlobal();

    private final String name;
    private final long offset, length;
    private final FileProperties props;
    private final SymmetricKey baseKey, metaKey;
    private final long nchunks;
    private final Location parentLocation;
    private final SymmetricKey parentparentKey;
    private final ProgressConsumer<Long> monitor;
    private final Fragmenter fragmenter;
    private final AsyncReader reader; // resettable input stream

    @JsConstructor
    public FileUploader(String name, String mimeType, AsyncReader fileData,
                        int offsetHi, int offsetLow, int lengthHi, int lengthLow,
                        SymmetricKey baseKey, SymmetricKey metaKey,
                        Location parentLocation, SymmetricKey parentparentKey,
                        ProgressConsumer<Long> monitor,
                        FileProperties fileProperties, Fragmenter fragmenter) {
        long length = lengthLow + ((lengthHi & 0xFFFFFFFFL) << 32);
        if (fileProperties == null)
            this.props = new FileProperties(name, mimeType, length, LocalDateTime.now(), false, Optional.empty());
        else
            this.props = fileProperties;
        if (baseKey == null) baseKey = SymmetricKey.random();

        this.fragmenter = fragmenter;

        long offset = offsetLow + ((offsetHi & 0xFFFFFFFFL) << 32);

        // Process and upload chunk by chunk to avoid running out of RAM, in reverse order to build linked list
        this.nchunks = length > 0 ? (length + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE : 1;
        this.name = name;
        this.offset = offset;
        this.length = length;
        this.reader = fileData;
        this.baseKey = baseKey;
        this.metaKey = metaKey;
        this.parentLocation = parentLocation;
        this.parentparentKey = parentparentKey;
        this.monitor = monitor;
    }

    public FileUploader(String name, String mimeType, AsyncReader fileData, long offset, long length,
                        SymmetricKey baseKey, SymmetricKey metaKey, Location parentLocation, SymmetricKey parentparentKey,
                        ProgressConsumer<Long> monitor, FileProperties fileProperties, Fragmenter fragmenter) {
        this(name, mimeType, fileData, (int)(offset >> 32), (int) offset, (int) (length >> 32), (int) length,
                baseKey, metaKey, parentLocation, parentparentKey, monitor, fileProperties, fragmenter);
    }

    public CompletableFuture<Location> uploadChunk(NetworkAccess network,
                                                   SafeRandom random,
                                                   PublicKeyHash owner,
                                                   SigningPrivateKeyAndPublicHash writer,
                                                   long chunkIndex,
                                                   Location currentLocation,
                                                   MaybeMultihash ourExistingHash,
                                                   ProgressConsumer<Long> monitor) {
	    LOG.info("uploading chunk: "+chunkIndex + " of "+name);

        long position = chunkIndex * Chunk.MAX_SIZE;

        long fileLength = length;
        boolean isLastChunk = fileLength < position + Chunk.MAX_SIZE;
        int length =  isLastChunk ? (int)(fileLength -  position) : Chunk.MAX_SIZE;
        byte[] data = new byte[length];
        return reader.readIntoArray(data, 0, data.length).thenCompose(b -> {
            byte[] nonce = random.randomBytes(TweetNaCl.SECRETBOX_NONCE_BYTES);
            Chunk chunk = new Chunk(data, metaKey, currentLocation.getMapKey(), nonce);
            LocatedChunk locatedChunk = new LocatedChunk(new Location(owner, writer.publicKeyHash, chunk.mapKey()), ourExistingHash, chunk);
            byte[] mapKey = random.randomBytes(32);
            Location nextLocation = new Location(owner, writer.publicKeyHash, mapKey);
            return uploadChunk(writer, props, parentLocation, parentparentKey, baseKey, locatedChunk,
                    fragmenter, nextLocation, network, monitor).thenApply(c -> nextLocation);
        });
    }

    public CompletableFuture<Location> upload(NetworkAccess network,
                                              SafeRandom random,
                                              PublicKeyHash owner,
                                              SigningPrivateKeyAndPublicHash writer,
                                              Location currentChunk) {
        long t1 = System.currentTimeMillis();
        Location originalChunk = currentChunk;

        List<Integer> input = IntStream.range(0, (int) nchunks).mapToObj(i -> Integer.valueOf(i)).collect(Collectors.toList());
        return Futures.reduceAll(input, currentChunk, (loc, i) -> uploadChunk(network, random, owner, writer, i,
                loc, MaybeMultihash.empty(), monitor), (a, b) -> b)
                .thenApply(loc -> {
                    LOG.info("File encryption, erasure coding and upload took: " +(System.currentTimeMillis()-t1) + " mS");
                    return originalChunk;
                });
    }

    public static CompletableFuture<Multihash> uploadChunk(SigningPrivateKeyAndPublicHash writer, FileProperties props, Location parentLocation, SymmetricKey parentparentKey,
                                                           SymmetricKey baseKey, LocatedChunk chunk, Fragmenter fragmenter, Location nextChunkLocation,
                                                           NetworkAccess network, ProgressConsumer<Long> monitor) {
        return chunk.chunk.encrypt().thenCompose(encryptedChunk -> {
            List<Fragment> fragments = encryptedChunk.generateFragments(fragmenter);
            LOG.info(StringUtils.format("Uploading chunk with %d fragments\n", fragments.size()));
            SymmetricKey chunkKey = chunk.chunk.key();
            byte[] nextLocationNonce = chunkKey.createNonce();
            byte[] nextLocation = nextChunkLocation.encrypt(chunkKey, nextLocationNonce);
            CipherText encryptedNextChunkLocation = new CipherText(nextLocationNonce, nextLocation);
            return network.uploadFragments(fragments, writer, monitor, fragmenter.storageIncreaseFactor())
                    .thenCompose(hashes -> {
                        FileRetriever retriever = new EncryptedChunkRetriever(chunk.chunk.nonce(), encryptedChunk.getAuth(),
                                hashes, Optional.of(encryptedNextChunkLocation), fragmenter);
                        FileAccess metaBlob = FileAccess.create(chunk.existingHash, baseKey, SymmetricKey.random(),
                                chunkKey, props, retriever, parentLocation, parentparentKey);
                        return network.uploadChunk(metaBlob, new Location(chunk.location.owner,
                                writer.publicKeyHash, chunk.chunk.mapKey()), writer);
                    });
        });
    }

    public void close() throws IOException  {
        reader.close();
    }
}
