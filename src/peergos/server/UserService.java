package peergos.server;
import java.util.logging.Logger;

import peergos.server.util.Args;
import peergos.server.util.Logging;
import java.util.logging.Level;

import com.sun.net.httpserver.*;
import peergos.server.corenode.*;
import peergos.server.mutable.*;
import peergos.server.social.*;
import peergos.server.storage.*;
import peergos.shared.corenode.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.ContentAddressedStorage;

import peergos.server.net.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;
import java.util.function.*;

public class UserService {
	private static final Logger LOG = Logging.LOG();

    public static final String DHT_URL = "/api/v0/";
    public static final String SIGNUP_URL = "/signup/";
    public static final String ACTIVATION_URL = "/activation/";
    public static final String UI_URL = "/";

    public static final int HANDLER_THREADS = 100;
    public static final int CONNECTION_BACKLOG = 100;

    static {
        // disable weak algorithms
        LOG.info("\nInitial security properties:");
        printSecurityProperties();

        // The ECDH and RSA ket exchange algorithms are disabled because they don't provide forward secrecy
        Security.setProperty("jdk.tls.disabledAlgorithms",
                "SSLv3, RC4, MD2, MD4, MD5, SHA1, DSA, DH, RSA keySize < 2048, EC keySize < 160, " +
                "TLS_RSA_WITH_AES_256_GCM_SHA384, " +
                "TLS_RSA_WITH_AES_256_CBC_SHA256, " +
                "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256, " +
                "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256, " +
                "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256, " +
                "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256, " +
                "TLS_RSA_WITH_AES_128_CBC_SHA256, " +
                "TLS_RSA_WITH_AES_128_GCM_SHA256");
        Security.setProperty("jdk.certpath.disabledAlgorithms",
                "RC4, MD2, MD4, MD5, SHA1, DSA, RSA keySize < 2048, EC keySize < 160");
        Security.setProperty("jdk.tls.rejectClientInitializedRenegotiation", "true");

        LOG.info("\nUpdated security properties:");
        printSecurityProperties();

        Security.setProperty("jdk.tls.ephemeralDHKeySize", "2048");
    }

    static void printSecurityProperties() {
        LOG.info("jdk.tls.disabledAlgorithms: " + Security.getProperty("jdk.tls.disabledAlgorithms"));
        LOG.info("jdk.certpath.disabledAlgorithms: " + Security.getProperty("jdk.certpath.disabledAlgorithms"));
        LOG.info("jdk.tls.rejectClientInitializedRenegotiation: "+Security.getProperty("jdk.tls.rejectClientInitializedRenegotiation"));
    }

    private final InetSocketAddress local;
    private final CoreNode coreNode;
    private final SocialNetwork social;
    private final MutablePointers mutable;
    private HttpServer server;

    public UserService(InetSocketAddress local,
                       ContentAddressedStorage dht,
                       CoreNode coreNode,
                       SocialNetwork social,
                       MutablePointers mutable,
                       Args args) throws IOException
    {
        this.local = local;
        this.coreNode = coreNode;
        this.social = social;
        this.mutable = mutable;
        init(dht, args);
    }

    public boolean init(ContentAddressedStorage dht, Args args) throws IOException {
        boolean isLocal = this.local.getHostName().contains("local");
        if (!isLocal)
            try {
                HttpServer httpServer = HttpServer.create();
                httpServer.createContext("/", new RedirectHandler("https://" + local.getHostName() + ":" + local.getPort() + "/"));
                httpServer.bind(new InetSocketAddress(InetAddress.getByName("::"), 80), CONNECTION_BACKLOG);
                httpServer.start();
            } catch (Exception e) {
                LOG.log(Level.WARNING, e.getMessage(), e);
                LOG.info("Couldn't start http redirect to https for user server!");
            }
        LOG.info("Starting user API server at: " + local.getHostName() + ":" + local.getPort());

        if (isLocal) {
            LOG.info("Starting user server on localhost:"+local.getPort()+" only.");
            server = HttpServer.create(local, CONNECTION_BACKLOG);
        } else if (args.hasArg("publicserver")) {
            LOG.info("Starting user server on all interfaces.");
            server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("::"), local.getPort()), CONNECTION_BACKLOG);
        } else
            server = HttpsServer.create(local, CONNECTION_BACKLOG);

        if (!isLocal) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");

                char[] password = "storage".toCharArray();
                KeyStore ks = getKeyStore("storage.p12", password);

                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, password);

//            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
//            tmf.init(SSL.getTrustedKeyStore());

                // setup the HTTPS context and parameters
                sslContext.init(kmf.getKeyManagers(), null, null);
                sslContext.getSupportedSSLParameters().setUseCipherSuitesOrder(true);
                // set up perfect forward secrecy
                sslContext.getSupportedSSLParameters().setCipherSuites(new String[]{
                        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"
                });

                SSLContext.setDefault(sslContext);
                ((HttpsServer)server).setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    public void configure(HttpsParameters params) {
                        try {
                            // initialise the SSL context
                            SSLContext c = SSLContext.getDefault();
                            SSLEngine engine = c.createSSLEngine();
                            params.setNeedClientAuth(false);
                            params.setCipherSuites(engine.getEnabledCipherSuites());
                            params.setProtocols(engine.getEnabledProtocols());

                            // get the default parameters
                            SSLParameters defaultSSLParameters = c.getDefaultSSLParameters();
                            params.setSSLParameters(defaultSSLParameters);
                        } catch (Exception ex) {
                            LOG.severe("Failed to create HTTPS port");
                            ex.printStackTrace(System.err);
                        }
                    }
                });
            }
            catch (NoSuchAlgorithmException | InvalidKeyException | KeyStoreException | CertificateException |
                    NoSuchProviderException | SignatureException |
                    UnrecoverableKeyException | KeyManagementException ex)
            {
                LOG.severe("Failed to create HTTPS port");
                ex.printStackTrace(System.err);
                return false;
            }
        }

        Function<HttpHandler, HttpHandler> wrap = h -> !isLocal ? new HSTSHandler(h) : h;

        long defaultQuota = args.getLong("default-quota");
        LOG.info("Using default user space quota of " + defaultQuota);
        Path quotaFilePath = args.fromPeergosDir("quotas_file","quotas.txt");
        UserQuotas userQuotas = new UserQuotas(quotaFilePath, defaultQuota);
        SpaceCheckingKeyFilter spaceChecker = new SpaceCheckingKeyFilter(coreNode, mutable, dht, userQuotas::quota);

        server.createContext(DHT_URL,
                wrap.apply(new DHTHandler(dht, spaceChecker::allowWrite)));

        CorenodeEventPropagator corenodePropagator = new CorenodeEventPropagator(this.coreNode);
        corenodePropagator.addListener(spaceChecker::accept);
        server.createContext("/" + HttpCoreNodeServer.CORE_URL,
                wrap.apply(new HttpCoreNodeServer.CoreNodeHandler(corenodePropagator)));

        server.createContext("/" + HttpSocialNetworkServer.SOCIAL_URL,
                wrap.apply(new HttpSocialNetworkServer.SocialHandler(this.social)));

        MutableEventPropagator mutablePropagator = new MutableEventPropagator(this.mutable);
        mutablePropagator.addListener(spaceChecker::accept);
        server.createContext("/" + HttpMutablePointerServer.MUTABLE_POINTERS_URL,
                wrap.apply(new HttpMutablePointerServer.MutationHandler(mutablePropagator)));

        server.createContext(SIGNUP_URL,
                wrap.apply(new InverseProxyHandler("demo.peergos.net", isLocal)));
        server.createContext(ACTIVATION_URL,
                wrap.apply(new InverseProxyHandler("demo.peergos.net", isLocal)));

        //define web-root static-handler
        StaticHandler handler;
        try {
            String webroot = args.getArg("webroot");
            LOG.info("Using webroot from local file system: " + webroot);
            handler = new FileHandler(Paths.get(webroot), true);
        } catch (IllegalStateException ile) {
            LOG.info("Using webroot from jar");
            handler = new JarHandler(true, Paths.get("webroot"));
        }

        boolean webcache = args.getBoolean("webcache", true);
        if (webcache) {
            LOG.info("Caching web-resources");
            handler = handler.withCache();
        }

        server.createContext(UI_URL, wrap.apply(handler));

        server.setExecutor(Executors.newFixedThreadPool(HANDLER_THREADS));
        server.start();

        return true;
    }

    public static KeyStore getKeyStore(String filename, char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, InvalidKeyException,
            NoSuchProviderException, SignatureException
    {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (new File(filename).exists())
        {
            ks.load(new FileInputStream(filename), password);
            return ks;
        }

        throw new IllegalStateException("SSL keystore file doesn't exist: "+filename);
    }
}
