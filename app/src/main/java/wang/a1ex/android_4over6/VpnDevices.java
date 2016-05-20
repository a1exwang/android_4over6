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
        System.loadLibrary("ndkutil");
    }

    static String ServerAddress = "2402:f000:1:4417::900";
    static String ServerPort = "5678";

    final Socket socket;
    InputStream mSocketRead;
    OutputStream mSocketWrite;

    public VpnDevices(Socket protectedSocket) throws IOException {
        this.socket = protectedSocket;
        String ipv6Addr = IVIVpnService.getLocalIpv6Address();
        SocketAddress socketAddress = new InetSocketAddress(ipv6Addr, 0);
        socket.bind(socketAddress);
        socket.connect(new InetSocketAddress(ServerAddress, Integer.valueOf(ServerPort)));
        mSocketRead = socket.getInputStream();
        mSocketWrite = socket.getOutputStream();
    }

    public native String getString();

    public void writeSocket(byte[] data) throws IOException {
        synchronized (socket) {
            mSocketWrite.write(data);
        }
    }
    public byte[] readSocket() throws IOException {
        synchronized (socket) {
            byte[] buf = new byte[65536];
            int size = mSocketRead.read(buf);
            if (size < 0) {
                throw new IOException("read returns minus size");
            }
            else {
                byte[] ret = new byte[size];
                System.arraycopy(buf, 0, ret, 0, size);
                return ret;
            }
        }
    }
}
