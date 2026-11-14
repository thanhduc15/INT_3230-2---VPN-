package vpn.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import vpn.core.TlsEndpoints;
import vpn.core.TlsTunnel;

/** A one-session packet echo demo for the TLS core, using generated IPv4 packets. */
public final class TunnelDemo {
    private TunnelDemo() {}

    public static void main(String[] args) {
        if ((args.length != 4 && args.length != 5) || (!args[0].equals("server") && !args[0].equals("client"))) {
            System.err.println("Usage: TunnelDemo server|client host port store.p12 [peer-store.p12]");
            System.exit(2);
        }
        try {
            int port = Integer.parseInt(args[2]);
            int minimumPort = args[0].equals("server") ? 0 : 1;
            if (port < minimumPort || port > 65535) {
                throw new IllegalArgumentException("Invalid TCP port");
            }
            String value = System.getenv("VPN_STORE_PASSWORD");
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("Set VPN_STORE_PASSWORD before running");
            }
            char[] password = value.toCharArray();
            try {
                Path store = Path.of(args[3]);
                Path peerStore = args.length == 5 ? Path.of(args[4]) : null;
                if (args[0].equals("server")) {
                    runServer(args[1], port, store, password, peerStore);
                } else {
                    runClient(args[1], port, store, password, peerStore);
                }
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (Exception failure) {
            System.err.println("Demo failed: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage());
            System.exit(1);
        }
    }

    private static void runServer(String host, int port, Path store, char[] password,
            Path trustedClients) throws Exception {
        var context = trustedClients == null
                ? TlsEndpoints.serverContext(store, password)
                : TlsEndpoints.mutualContext(store, password, trustedClients, password);
        try (var listener = TlsEndpoints.listen(context, new InetSocketAddress(host, port),
                trustedClients != null)) {
            System.out.println("READY port=" + listener.port());
            try (var tunnel = listener.accept()) {
                tunnel.setReadTimeout(10_000);
                System.out.println("TLS " + tunnel.protocol() + " " + tunnel.cipherSuite());
                int count = 0;
                byte[] packet;
                while ((packet = tunnel.receive()) != null) {
                    tunnel.send(packet);
                    count++;
                    System.out.println("ECHO packet=" + count + " bytes=" + packet.length);
                }
                System.out.println("DONE packets=" + count);
            }
        }
    }

    private static void runClient(String host, int port, Path store, char[] password,
            Path clientIdentity) throws Exception {
        var context = clientIdentity == null
                ? TlsEndpoints.clientContext(store, password)
                : TlsEndpoints.mutualContext(clientIdentity, password, store, password);
        try (var tunnel = TlsEndpoints.connect(context, host, port)) {
            tunnel.setReadTimeout(5_000);
            System.out.println("TLS " + tunnel.protocol() + " " + tunnel.cipherSuite());
            for (int sequence = 1; sequence <= 3; sequence++) {
                byte[] packet = probePacket(sequence);
                long start = System.nanoTime();
                tunnel.send(packet);
                byte[] reply = tunnel.receive();
                if (!Arrays.equals(packet, reply)) {
                    throw new IOException("Missing or changed echo packet " + sequence);
                }
                double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
                System.out.printf("PASS packet=%d bytes=%d roundTripMs=%.3f%n",
                        sequence, packet.length, milliseconds);
            }
        }
    }

    private static byte[] probePacket(int sequence) {
        byte[] payload = ("VPN-DEMO-" + sequence).getBytes(StandardCharsets.US_ASCII);
        byte[] packet = new byte[20 + payload.length];
        packet[0] = 0x45;
        packet[2] = (byte) (packet.length >>> 8);
        packet[3] = (byte) packet.length;
        packet[5] = (byte) sequence;
        packet[8] = 64;
        packet[9] = (byte) 253; // Experimental protocol for this generated test packet.
        packet[12] = 10;
        packet[13] = 8;
        packet[15] = 2;
        packet[16] = 10;
        packet[17] = 8;
        packet[19] = 1;
        System.arraycopy(payload, 0, packet, 20, payload.length);

        int sum = 0;
        for (int offset = 0; offset < 20; offset += 2) {
            sum += ((packet[offset] & 0xff) << 8) | (packet[offset + 1] & 0xff);
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        int checksum = (~sum) & 0xffff;
        packet[10] = (byte) (checksum >>> 8);
        packet[11] = (byte) checksum;
        return packet;
    }
}
