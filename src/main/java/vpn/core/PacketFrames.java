package vpn.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ProtocolException;

final class PacketFrames {
    static final int MAX_PACKET_SIZE = 1400;
    private static final int MIN_IPV4_HEADER = 20;

    private PacketFrames() {}

    static byte[] read(DataInputStream input) throws IOException {
        int high = input.read();
        if (high == -1) {
            return null;
        }

        // A missing second length byte or an incomplete payload is a truncated frame.
        int length = (high << 8) | input.readUnsignedByte();
        checkLength(length);
        byte[] packet = new byte[length];
        input.readFully(packet);
        checkIpv4(packet);
        return packet;
    }

    static void write(DataOutputStream output, byte[] packet) throws IOException {
        checkLength(packet.length);
        checkIpv4(packet);
        output.writeShort(packet.length);
        output.write(packet);
        output.flush();
    }

    private static void checkLength(int length) throws ProtocolException {
        if (length < MIN_IPV4_HEADER || length > MAX_PACKET_SIZE) {
            throw new ProtocolException("Invalid VPN frame length: " + length);
        }
    }

    private static void checkIpv4(byte[] packet) throws ProtocolException {
        int version = (packet[0] & 0xff) >>> 4;
        int headerSize = (packet[0] & 0x0f) * 4;
        int totalLength = ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
        if (version != 4 || headerSize < MIN_IPV4_HEADER ||
                headerSize > packet.length || totalLength != packet.length) {
            throw new ProtocolException("Invalid IPv4 packet in VPN frame");
        }
    }
}
