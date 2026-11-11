package vpn.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

public final class CoreSmoke {
    private static final char[] PASSWORD = "demo-only".toCharArray();
    private static final byte[] END = new byte[0];

    public static void main(String[] args) throws Exception {
        Path certs = Path.of(args[0]);
        SSLContext server = TlsEndpoints.serverContext(certs.resolve("server.p12"), PASSWORD);
        SSLContext trusted = TlsEndpoints.clientContext(certs.resolve("trusted.p12"), PASSWORD);
        SSLContext untrusted = TlsEndpoints.clientContext(certs.resolve("untrusted.p12"), PASSWORD);

        ExecutorService tasks = Executors.newFixedThreadPool(3);
        try (var listener = TlsEndpoints.listen(server, new InetSocketAddress("127.0.0.1", 0))) {
            checkRejection(tasks, listener, untrusted, "127.0.0.1", "untrusted certificate");
            checkRejection(tasks, listener, trusted, "localhost", "certificate name mismatch");
            checkBridge(tasks, listener, trusted);
            checkInvalidFrame(new byte[]{5, 121}, ProtocolException.class);
            checkInvalidFrame(new byte[]{0}, EOFException.class);
            checkInvalidFrame(new byte[]{0, 20, 0x45}, EOFException.class);
            byte[] badIpv4 = new byte[22];
            badIpv4[1] = 20;
            badIpv4[2] = 0x65;
            checkInvalidFrame(badIpv4, ProtocolException.class);
            System.out.println("PASS: oversized, truncated, and invalid IPv4 frames rejected");
        } finally {
            tasks.shutdownNow();
        }
    }

    private static void checkRejection(ExecutorService tasks, TlsEndpoints.Listener listener,
            SSLContext client, String host, String reason) throws Exception {
        Future<?> accept = tasks.submit(() -> {
            // In TLS 1.3 the server can finish locally just before the client rejects it.
            try (TlsTunnel tunnel = listener.accept()) {
                if (tunnel.receive() != null) {
                    throw new AssertionError("Unexpected packet during negative test");
                }
            } catch (IOException expected) {
                // The client rejected the connection and sent no packets.
            }
            return null;
        });
        try (TlsTunnel ignored = TlsEndpoints.connect(client, host, listener.port())) {
            throw new AssertionError("Accepted " + reason);
        } catch (SSLHandshakeException expected) {
            System.out.println("PASS: rejected " + reason);
        }
        accept.get(10, TimeUnit.SECONDS);
    }

    private static void checkBridge(ExecutorService tasks, TlsEndpoints.Listener listener,
            SSLContext clientContext) throws Exception {
        Future<TlsTunnel> accepted = tasks.submit(listener::accept);
        try (TlsTunnel client = TlsEndpoints.connect(clientContext, "127.0.0.1", listener.port());
                TlsTunnel server = accepted.get(10, TimeUnit.SECONDS);
                MemoryDevice clientDevice = new MemoryDevice();
                MemoryDevice serverDevice = new MemoryDevice()) {
            if (!"TLSv1.3".equals(client.protocol())) {
                throw new AssertionError("Unexpected TLS version: " + client.protocol());
            }
            Future<?> clientPump = tasks.submit(() -> {
                TunnelBridge.run(client, clientDevice);
                return null;
            });
            Future<?> serverPump = tasks.submit(() -> {
                TunnelBridge.run(server, serverDevice);
                return null;
            });
            byte[] first = packet(64, 1);
            byte[] second = packet(TlsTunnel.MTU, 2);
            byte[] reply = packet(20, 3);
            clientDevice.outgoing.add(first);
            clientDevice.outgoing.add(second);
            serverDevice.outgoing.add(reply);
            expectPacket(first, serverDevice);
            expectPacket(second, serverDevice);
            expectPacket(reply, clientDevice);
            clientDevice.outgoing.add(END);
            clientPump.get(10, TimeUnit.SECONDS);
            serverPump.get(10, TimeUnit.SECONDS);
            System.out.println("PASS: bidirectional IPv4 frames, orderly shutdown, " + client.cipherSuite());
        }
    }

    private static byte[] packet(int size, int id) {
        byte[] result = new byte[size];
        result[0] = 0x45;
        result[2] = (byte) (size >>> 8);
        result[3] = (byte) size;
        result[5] = (byte) id;
        result[8] = 64;
        return result;
    }

    private static void expectPacket(byte[] expected, MemoryDevice destination)
            throws InterruptedException {
        byte[] actual = destination.incoming.poll(10, TimeUnit.SECONDS);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Missing packet or packet bytes changed in transit");
        }
    }

    private static void checkInvalidFrame(byte[] bytes, Class<? extends IOException> type)
            throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            PacketFrames.read(input);
            throw new AssertionError("Accepted invalid frame");
        } catch (IOException failure) {
            if (!type.isInstance(failure)) {
                throw failure;
            }
        }
    }

    private static final class MemoryDevice implements PacketDevice {
        final BlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();

        @Override
        public byte[] readPacket() throws IOException {
            try {
                byte[] next = outgoing.take();
                return next == END ? null : next;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Packet device read interrupted", failure);
            }
        }

        @Override
        public void writePacket(byte[] packet) {
            incoming.add(packet);
        }

        @Override
        public void close() {
            outgoing.offer(END);
        }
    }
}
