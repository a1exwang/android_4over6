package wang.a1ex.android_4over6;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

public class VpnDevices {
    static {
        System.loadLibrary("VpnDevices");
    }

    private VpnCallbacks vpnCallbacks;
    private short jcPort;
    public VpnDevices(VpnCallbacks vpnCallbacks, int jcPort) {
        this.jcPort = (short) jcPort;
        this.vpnCallbacks = vpnCallbacks;
    }

    public native int startVpn();
}
