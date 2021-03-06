package peergos.server.social;
import java.util.logging.*;

import peergos.server.util.Args;
import peergos.server.util.Logging;

import com.sun.net.httpserver.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class HttpSocialNetworkServer  {
	private static final Logger LOG = Logging.LOG();

    private static final boolean LOGGING = true;
    private static final int CONNECTION_BACKLOG = 100;
    private static final int HANDLER_THREAD_COUNT = 100;

    public static final String SOCIAL_URL = "social/";
    public static final int PORT = 7777;

    public static class SocialHandler implements HttpHandler
    {
        private final SocialNetwork social;

        public SocialHandler(SocialNetwork social) {
            this.social = social;
        }

        public void handle(HttpExchange exchange) throws IOException
        {
            long t1 = System.currentTimeMillis();
            DataInputStream din = new DataInputStream(exchange.getRequestBody());

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/"))
                path = path.substring(1);
            String[] subComponents = path.substring(SOCIAL_URL.length()).split("/");
            String method = subComponents[0];
//            LOG.info("social method "+ method +" from path "+ path);

            try {
                switch (method)
                {
                    case "followRequest":
                        followRequest(din, dout);
                        break;
                    case "getFollowRequests":
                        getFollowRequests(din, dout);
                        break;
                    case "removeFollowRequest":
                        removeFollowRequest(din, dout);
                        break;
                    default:
                        throw new IOException("Unknown method "+ method);
                }

                dout.flush();
                dout.close();
                byte[] b = bout.toByteArray();
                exchange.sendResponseHeaders(200, b.length);
                exchange.getResponseBody().write(b);
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause != null)
                    exchange.getResponseHeaders().set("Trailer", cause.getMessage());
                else
                    exchange.getResponseHeaders().set("Trailer", e.getMessage());

                exchange.sendResponseHeaders(400, 0);
            } finally {
                exchange.close();
                long t2 = System.currentTimeMillis();
                if (LOGGING)
                    LOG.info("Social Network server handled " + method + " request in: " + (t2 - t1) + " mS");
            }

        }

        void followRequest(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedKey = Serialize.deserializeByteArray(din, PublicSigningKey.MAX_SIZE);
            PublicKeyHash target = PublicKeyHash.fromCbor(CborObject.fromByteArray(encodedKey));
            byte[] encodedSharingPublicKey = CoreNodeUtils.deserializeByteArray(din);

            boolean followRequested = social.sendFollowRequest(target, encodedSharingPublicKey).get();
            dout.writeBoolean(followRequested);
        }
        void getFollowRequests(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedKey = Serialize.deserializeByteArray(din, PublicSigningKey.MAX_SIZE);
            PublicKeyHash ownerPublicKey = PublicKeyHash.fromCbor(CborObject.fromByteArray(encodedKey));
            byte[] res = social.getFollowRequests(ownerPublicKey).get();
            Serialize.serialize(res, dout);
        }
        void removeFollowRequest(DataInputStream din, DataOutputStream dout) throws Exception
        {
            byte[] encodedKey = Serialize.deserializeByteArray(din, PublicSigningKey.MAX_SIZE);
            PublicKeyHash owner = PublicKeyHash.fromCbor(CborObject.fromByteArray(encodedKey));
            byte[] signedFollowRequest = CoreNodeUtils.deserializeByteArray(din);

            boolean isRemoved = social.removeFollowRequest(owner, signedFollowRequest).get();
            dout.writeBoolean(isRemoved);
        }
    }

    private final HttpServer server;
    private final InetSocketAddress address;
    private final SocialHandler ch;

    public HttpSocialNetworkServer(SocialNetwork social, InetSocketAddress address) throws IOException
    {

        this.address = address;
        if (address.getHostName().toLowerCase().contains("local"))
            server = HttpServer.create(address, CONNECTION_BACKLOG);
        else
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLocalHost(), address.getPort()), CONNECTION_BACKLOG);
        ch = new SocialHandler(social);
        server.createContext("/" + SOCIAL_URL, ch);
        server.setExecutor(Executors.newFixedThreadPool(HANDLER_THREAD_COUNT));
    }

    public void start() throws IOException
    {
        server.start();
    }
    
    public InetSocketAddress getAddress(){return address;}

    public void close() throws IOException
    {   
        server.stop(5);
    }


    public static void createAndStart(String keyfile, char[] passphrase, int port, SocialNetwork social, Args args)
    {
        try {
            String hostname = args.getArg("domain", "localhost");
            LOG.info("Starting social network server listening on: " + hostname+":"+port +" proxying to "+social);
            InetSocketAddress address = new InetSocketAddress(hostname, port);
            HttpSocialNetworkServer server = new HttpSocialNetworkServer(social, address);
            server.start();
        } catch (Exception e)
        {
            LOG.log(Level.WARNING, e.getMessage(), e);
            LOG.info("Couldn't start Social Network server!");
        }
    }
}
