package vpn.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketException;
import javax.net.ssl.SSLSocket;

/** An authenticated transport for IPv4 packets. Supports one reader and one writer. */
public final class TlsTunnel implements Closeable {
    public static final int MTU = PacketFrames.MAX_PACKET_SIZE;

    private final SSLSocket socket;
    private final DataInputStream input;
    private final DataOutputStream output;

    TlsTunnel(SSLSocket socket) throws IOException {
        this.socket = socket;
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public String protocol() {
        return socket.getSession().getProtocol();
    }

    public String cipherSuite() {
        return socket.getSession().getCipherSuite();
    }

    /**
     * Sets the timeout for socket reads in milliseconds. Zero allows unlimited waiting.
     * After a receive timeout, close the session because a frame may be partly consumed.
     */
    public void setReadTimeout(int milliseconds) throws SocketException {
        socket.setSoTimeout(milliseconds);
    }

    public void send(byte[] packet) throws IOException {
        PacketFrames.write(output, packet);
    }

    /** Returns null on EOF at a frame boundary. Close the session on any I/O error. */
    public byte[] receive() throws IOException {
        return PacketFrames.read(input);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
