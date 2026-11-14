package vpn.core;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/** Exercises client authentication over real TLS 1.3 loopback connections. */
public final class MutualTlsTest {
    private static final char[] PASSWORD = "demo-only".toCharArray();

    public static void main(String[] args) throws Exception {
        Path certs = Path.of(args[0]);
        SSLContext server = TlsEndpoints.mutualContext(certs.resolve("server.p12"), PASSWORD,
                certs.resolve("clients.p12"), PASSWORD);
        SSLContext missing = TlsEndpoints.clientContext(certs.resolve("trusted.p12"), PASSWORD);
        SSLContext untrusted = TlsEndpoints.mutualContext(certs.resolve("other.p12"), PASSWORD,
                certs.resolve("trusted.p12"), PASSWORD);
        SSLContext trusted = TlsEndpoints.mutualContext(certs.resolve("client.p12"), PASSWORD,
                certs.resolve("trusted.p12"), PASSWORD);

        ExecutorService tasks = Executors.newSingleThreadExecutor();
        try (var listener = TlsEndpoints.listen(server, new InetSocketAddress("127.0.0.1", 0), true)) {
            rejectClient(tasks, listener, missing, "missing client certificate");
            rejectClient(tasks, listener, untrusted, "untrusted client certificate");
            acceptClient(tasks, listener, trusted);
        } finally {
            tasks.shutdownNow();
        }
    }

    private static void rejectClient(ExecutorService tasks, TlsEndpoints.Listener listener,
            SSLContext context, String reason) throws Exception {
        Future<String> rejection = tasks.submit(() -> {
            try (TlsTunnel ignored = listener.accept()) {
                throw new AssertionError("Server accepted " + reason);
            } catch (SSLHandshakeException expected) {
                return expected.getMessage();
            }
        });
        // TLS 1.3 may let the client finish locally before the server rejects its identity.
        try (TlsTunnel client = TlsEndpoints.connect(context, "127.0.0.1", listener.port())) {
            client.setReadTimeout(5_000);
            client.send(packet());
            if (client.receive() != null) {
                throw new AssertionError("Received a packet from a rejected session");
            }
        } catch (SSLException | SocketException expected) {
            // Require the server-side handshake failure as well.
        }
        String message = rejection.get(10, TimeUnit.SECONDS);
        if (reason.equals("untrusted client certificate") &&
                message.contains("Empty client certificate chain")) {
            throw new AssertionError("Untrusted identity was not presented to the server");
        }
        System.out.println("PASS: rejected " + reason);
    }

    private static void acceptClient(ExecutorService tasks, TlsEndpoints.Listener listener,
            SSLContext context) throws Exception {
        Future<?> echo = tasks.submit(() -> {
            try (TlsTunnel server = listener.accept()) {
                server.setReadTimeout(5_000);
                byte[] packet = server.receive();
                if (packet == null) {
                    throw new AssertionError("Trusted client sent no packet");
                }
                server.send(packet);
            }
            return null;
        });
        try (TlsTunnel client = TlsEndpoints.connect(context, "127.0.0.1", listener.port())) {
            client.setReadTimeout(5_000);
            byte[] packet = packet();
            client.send(packet);
            if (!Arrays.equals(packet, client.receive())) {
                throw new AssertionError("Mutual TLS changed packet content");
            }
        }
        echo.get(10, TimeUnit.SECONDS);
        System.out.println("PASS: trusted client after failed handshakes, bidirectional mTLS");
    }

    private static byte[] packet() {
        byte[] packet = new byte[20];
        packet[0] = 0x45;
        packet[3] = 20;
        return packet;
    }
}
