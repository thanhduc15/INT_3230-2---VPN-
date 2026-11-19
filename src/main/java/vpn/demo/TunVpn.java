package vpn.demo;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import vpn.core.LinuxTunDevice;
import vpn.core.TlsEndpoints;
import vpn.core.TlsTunnel;
import vpn.core.TunnelBridge;

/** Run from the repository root on a Linux lab machine with a prepared TUN. */
public final class TunVpn {
    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !(args[0].equals("server") || args[0].equals("client"))) {
            throw new IllegalArgumentException(
                    "Usage: TunVpn server|client host port tunName identity.p12 trusted-peer.p12");
        }
        String secret = System.getenv("VPN_STORE_PASSWORD");
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Set VPN_STORE_PASSWORD");
        }
        char[] password = secret.toCharArray();
        SSLContext context;
        try {
            context = TlsEndpoints.mutualContext(Path.of(args[4]), password,
                    Path.of(args[5]), password);
        } finally {
            Arrays.fill(password, '\0');
        }
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        try (LinuxTunDevice tun = new LinuxTunDevice(args[3])) {
            if (args[0].equals("server")) {
                try (var listener = TlsEndpoints.listen(context, new InetSocketAddress(host, port), true)) {
                    System.out.println("READY port=" + listener.port());
                    run(listener.accept(), tun);
                }
            } else {
                run(TlsEndpoints.connect(context, host, port), tun);
            }
        }
    }

    private static void run(TlsTunnel tunnel, LinuxTunDevice tun) throws Exception {
        try (tunnel) {
            System.out.println("TUN connected: " + tunnel.protocol() + " " + tunnel.cipherSuite());
            TunnelBridge.run(tunnel, tun);
        }
    }
}
