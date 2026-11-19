package vpn.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.HexFormat;

/** Shows the bytes produced by the VPN framing layer before TLS encryption. */
public final class FrameWalkthrough {
    public static void main(String[] args) throws Exception {
        // IPv4 header (20 bytes) + ICMP Echo Request (8 bytes), 10.8.0.2 -> 10.8.0.1.
        byte[] packet = HexFormat.of().parseHex(
                "4500001c00000000400166cf0a0800020a080001" +
                "0800f7fd00010001");

        var buffer = new ByteArrayOutputStream();
        PacketFrames.write(new DataOutputStream(buffer), packet);
        byte[] frame = buffer.toByteArray();

        System.out.printf("1. IPv4 packet: %d bytes%n", packet.length);
        System.out.printf("2. VPN frame: [%s] + %d packet bytes = %d bytes%n",
                HexFormat.ofDelimiter(" ").formatHex(frame, 0, 2), packet.length, frame.length);

        var input = new DataInputStream(new ByteArrayInputStream(frame));
        byte[] received = PacketFrames.read(input);
        if (!Arrays.equals(packet, received)) {
            throw new AssertionError("Decoded packet differs from the original");
        }
        System.out.printf("3. Receiver recovered all %d original bytes%n", received.length);
    }
}
