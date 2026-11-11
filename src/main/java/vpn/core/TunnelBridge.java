package vpn.core;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pumps packets in both directions and owns the device and tunnel for the session. */
public final class TunnelBridge {
    private TunnelBridge() {}

    public static void run(TlsTunnel tunnel, PacketDevice device)
            throws IOException, InterruptedException {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (tunnel; device) {
            ExecutorCompletionService<Void> pumps = new ExecutorCompletionService<>(workers);
            pumps.submit(() -> {
                byte[] packet;
                while ((packet = device.readPacket()) != null) {
                    tunnel.send(packet);
                }
                return null;
            });
            pumps.submit(() -> {
                byte[] packet;
                while ((packet = tunnel.receive()) != null) {
                    device.writePacket(packet);
                }
                return null;
            });
            // Ending either direction ends this point-to-point VPN session.
            try {
                pumps.take().get();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof IOException ioFailure) {
                    throw ioFailure;
                }
                throw new IOException("VPN packet pump failed", failure.getCause());
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
