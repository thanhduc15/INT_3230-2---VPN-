package vpn.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/** Packet device backed by the Linux helper shipped in scripts/tun-io.py. */
public final class LinuxTunDevice implements PacketDevice {
    private final Process helper;
    private final DataInputStream input;
    private final DataOutputStream output;

    public LinuxTunDevice(String name) throws IOException {
        ProcessBuilder command = new ProcessBuilder("python3", "scripts/tun-io.py", name);
        command.environment().remove("VPN_STORE_PASSWORD");
        helper = command.redirectError(ProcessBuilder.Redirect.INHERIT).start();
        input = new DataInputStream(helper.getInputStream());
        output = new DataOutputStream(helper.getOutputStream());
        try {
            if (input.read() != 'R') {
                throw new IOException("Could not attach to TUN " + name + "; check helper stderr");
            }
        } catch (IOException failure) {
            close();
            throw failure;
        }
    }

    @Override
    public byte[] readPacket() throws IOException {
        byte[] packet = PacketFrames.read(input);
        if (packet == null) {
            throw new EOFException("TUN helper stopped");
        }
        return packet;
    }

    @Override
    public void writePacket(byte[] packet) throws IOException {
        PacketFrames.write(output, packet);
    }

    @Override
    public void close() {
        helper.destroyForcibly();
    }
}
