package vpn.core;

import java.io.IOException;

/** Adapter for a packet-oriented device, such as a Linux TUN interface. */
public interface PacketDevice extends AutoCloseable {
    /** Reads one complete IPv4 packet, or returns null when the device ends. */
    byte[] readPacket() throws IOException;

    /** Writes one complete IPv4 packet to the device. */
    void writePacket(byte[] packet) throws IOException;

    /**
     * Releases the device and promptly unblocks pending reads and writes.
     * An adapter must allow one reader and one writer to run concurrently.
     */
    @Override
    void close() throws IOException;
}
