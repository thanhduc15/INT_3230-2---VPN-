package vpn.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Arrays;

/** Checks packet boundaries even when a stream supplies one byte per read. */
public final class PacketFramesTest {
    public static void main(String[] args) throws Exception {
        byte[] minimum = packet(20);
        byte[] options = packet(24);
        options[0] = 0x46;
        byte[] maximum = packet(PacketFrames.MAX_PACKET_SIZE);

        var wire = new ByteArrayOutputStream();
        var output = new DataOutputStream(wire);
        for (byte[] packet : new byte[][]{minimum, options, maximum}) {
            PacketFrames.write(output, packet);
        }
        byte[] encoded = wire.toByteArray();
        if ((encoded[0] & 0xff) != 0 || (encoded[1] & 0xff) != 20) {
            throw new AssertionError("Length prefix must be unsigned big-endian");
        }
        try (var input = new DataInputStream(new FilterInputStream(
                new ByteArrayInputStream(encoded)) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        })) {
            for (byte[] expected : new byte[][]{minimum, options, maximum}) {
                if (!Arrays.equals(expected, PacketFrames.read(input))) {
                    throw new AssertionError("Packet boundary or content changed");
                }
            }
            if (PacketFrames.read(input) != null) {
                throw new AssertionError("Expected clean EOF after the final frame");
            }
        }
        System.out.println("PASS: fragmented stream, consecutive frames, IPv4 options and MTU");

        for (int length : new int[]{0, 19, 1401, 65535}) {
            expectReadFailure(new byte[]{(byte) (length >>> 8), (byte) length},
                    ProtocolException.class);
        }
        expectReadFailure(new byte[]{0}, EOFException.class);
        expectReadFailure(Arrays.copyOf(encoded, 21), EOFException.class);
        for (int header : new int[]{0x65, 0x44, 0x4f}) {
            byte[] invalid = packet(20);
            invalid[0] = (byte) header;
            expectInvalidPacket(invalid);
        }
        byte[] wrongTotalLength = packet(20);
        wrongTotalLength[3] = 21;
        expectInvalidPacket(wrongTotalLength);
        System.out.println("PASS: invalid lengths, headers and truncated frames");
    }

    private static byte[] packet(int length) {
        byte[] packet = new byte[length];
        packet[0] = 0x45;
        packet[2] = (byte) (length >>> 8);
        packet[3] = (byte) length;
        return packet;
    }

    private static void expectReadFailure(byte[] wire, Class<? extends IOException> type)
            throws IOException {
        try {
            PacketFrames.read(new DataInputStream(new ByteArrayInputStream(wire)));
            throw new AssertionError("Accepted malformed wire data");
        } catch (IOException failure) {
            if (!type.isInstance(failure)) {
                throw failure;
            }
        }
    }

    private static void expectInvalidPacket(byte[] packet) throws IOException {
        var wire = new ByteArrayOutputStream();
        var output = new DataOutputStream(wire);
        try {
            PacketFrames.write(output, packet);
            throw new AssertionError("Writer accepted an invalid IPv4 packet");
        } catch (ProtocolException expected) {
            if (wire.size() != 0) {
                throw new AssertionError("Writer changed stream before validating packet");
            }
        }
        output.writeShort(packet.length);
        output.write(packet);
        expectReadFailure(wire.toByteArray(), ProtocolException.class);
    }
}
