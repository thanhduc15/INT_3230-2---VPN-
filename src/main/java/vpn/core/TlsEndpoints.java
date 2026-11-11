package vpn.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** Creates TLS 1.3 connections for the lab's VPN wire protocol. */
public final class TlsEndpoints {
    private static final String APPLICATION_PROTOCOL = "java-vpn/1";
    private static final int TIMEOUT_MS = 5_000;

    private TlsEndpoints() {}

    public static SSLContext clientContext(Path trustStore, char[] password)
            throws IOException, GeneralSecurityException {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trust.init(loadStore(trustStore, password));
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    public static SSLContext serverContext(Path keyStore, char[] password)
            throws IOException, GeneralSecurityException {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        keys.init(loadStore(keyStore, password), password);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    public static TlsTunnel connect(SSLContext context, String host, int port) throws IOException {
        Socket tcp = new Socket();
        try {
            tcp.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            SSLSocket tls = (SSLSocket) context.getSocketFactory()
                    .createSocket(tcp, host, port, true);
            try {
                configure(tls, true);
                return handshake(tls);
            } catch (IOException | RuntimeException failure) {
                closeOnFailure(tls, failure);
                throw failure;
            }
        } catch (IOException | RuntimeException failure) {
            closeOnFailure(tcp, failure);
            throw failure;
        }
    }

    public static Listener listen(SSLContext context, InetSocketAddress bind) throws IOException {
        SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        try {
            server.bind(bind);
            return new Listener(server);
        } catch (IOException | RuntimeException failure) {
            closeOnFailure(server, failure);
            throw failure;
        }
    }

    public static final class Listener implements Closeable {
        private final SSLServerSocket server;

        private Listener(SSLServerSocket server) {
            this.server = server;
        }

        public int port() {
            return server.getLocalPort();
        }

        public TlsTunnel accept() throws IOException {
            SSLSocket tls = (SSLSocket) server.accept();
            try {
                configure(tls, false);
                return handshake(tls);
            } catch (IOException | RuntimeException failure) {
                closeOnFailure(tls, failure);
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static KeyStore loadStore(Path path, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }

    private static void configure(SSLSocket socket, boolean client) {
        socket.setUseClientMode(client);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3"});
        parameters.setApplicationProtocols(new String[]{APPLICATION_PROTOCOL});
        if (client) {
            // This algorithm checks the DNS name or IP passed to connect against the certificate.
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
        }
        socket.setSSLParameters(parameters);
    }

    private static TlsTunnel handshake(SSLSocket socket) throws IOException {
        socket.setSoTimeout(TIMEOUT_MS);
        socket.startHandshake();
        if (!APPLICATION_PROTOCOL.equals(socket.getApplicationProtocol())) {
            throw new SSLHandshakeException("Expected ALPN protocol " + APPLICATION_PROTOCOL);
        }
        socket.setSoTimeout(0);
        return new TlsTunnel(socket);
    }

    private static void closeOnFailure(Closeable resource, Throwable failure) {
        try {
            resource.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
