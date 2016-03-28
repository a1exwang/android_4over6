package wang.a1ex.android_4over6;

/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Vector;

public class IVIVpnService extends VpnService implements Handler.Callback, Runnable {
    private static final String TAG = "IVIVpnService";
    private String mServerAddress = "2402:f000:1:4417::900";
    private String mServerPort = "5678";
    private Handler mHandler;
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private String mParameters;
    private static final int HEARTBEAT_INTERVAL = 5000;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The handler is only used to show messages.
        if (mHandler == null) {
            mHandler = new Handler(this);
        }
        // Stop the previous session by interrupting the thread.
        if (mThread != null) {
            mThread.interrupt();
        }
        // Start a new session by creating a new thread.
        mThread = new Thread(this, "IVIVpnThread");
        mThread.start();
        return START_STICKY;
    }
    @Override
    public void onDestroy() {
        if (mThread != null) {
            mThread.interrupt();
        }
    }
    @Override
    public boolean handleMessage(Message message) {
        if (message != null) {
            if (message.obj != null) {
                String str = (String) message.obj;
                Toast.makeText(this, str, Toast.LENGTH_SHORT).show();
            }
            else {
                Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
            }

        }
        return true;
    }
    @Override
    public synchronized void run() {
        try {
            Log.i(TAG, "Starting");
            startVpn();
        } catch (Exception e) {
            Log.e(TAG, "Got " + e.toString());
        }
    }

    byte []getHelloPacket() {
        byte []buffer = new byte[5];
        buffer[0]= 5;buffer[1]=buffer[2]=0;
        buffer[3] = 0;
        buffer[4] = CONNECTION_HELLO;
        return buffer;
    }
    byte []getHeatbeatPacket() {
        byte []buffer = new byte[5];
        buffer[0]=0;buffer[1]=buffer[2]=0;
        buffer[3] = 0;
        buffer[4] = CONNECTION_HEARTBEAT;
        return buffer;
    }

    int packetLengthInt(byte[] buffer, int start) {
        return (buffer[start]) +
                (buffer[start + 1] << 8) +
                (buffer[start + 2] << 16) +
                (buffer[start + 3] << 24);
    }

    void packetLengthIntWrite(byte[] buffer, int start, int value) {
        buffer[start + 3] = (byte)(value >> 24);
        buffer[start + 2] = (byte)((value >> 16) & 0xFF);
        buffer[start + 1] = (byte)((value >> 8) & 0xFF);
        buffer[start + 0] = (byte)((value) & 0xFF);
    }

    static final byte CONNECTION_HELLO = 100;
    static final byte CONNECTION_INFO = 101;
    static final byte CONNECTION_SEND_DATA = 102;
    static final byte CONNECTION_RECEIVE_DATA = 103;
    static final byte CONNECTION_HEARTBEAT = 104;
    static final int MAX_MTU = 65536;

    private static class IVIPacket {
        public byte type;
        public int length;
        public byte[] data;
    }

    static final int PACKET_LENGTH_OFFSET = 4;
    static final int PACKET_DATA_OFFSET = 5;

    private byte []buildPacket(byte type, byte[] data, int offset, int length) {
        int packetLength = length + 5;
        byte []ret = new byte[packetLength];
        packetLengthIntWrite(ret, 0, packetLength);
        ret[PACKET_LENGTH_OFFSET] = type;
        for (int i = 0; i < length; ++i) {
            ret[PACKET_DATA_OFFSET + i] = data[offset + i];
        }
        return ret;
    }

    private IVIPacket parsePacket(byte[] packet, int offset, int length) {
        if (length >= 5) {
            IVIPacket ret = new IVIPacket();
            int packetLength = packetLengthInt(packet, offset);
            if (packetLength >= 5) {
                ret.length = packetLength - 5;
                ret.type = packet[4];
                if (ret.length < 5000) {
                    ret.data = new byte[ret.length];
                    for (int i = 0; i < ret.length; ++i) {
                        ret.data[i] = packet[5 + i];
                    }
                    return ret;
                }
                else {
                    return null;
                }
            }
            else {
                int a = 1;
                return null;
            }
        }
        else {
            return null;
        }

    }

    // receive data from server and send to tun device
    private void txThread(InputStream socketRead, OutputStream tunWrite) {
        while (true) {
            ByteBuffer packet = ByteBuffer.allocate(32767);
            int readSize = 0;
            try {
                readSize = socketRead.read(packet.array());
                Log.w(TAG, "txThread: " + readSize);
            } catch (IOException e1) {
                e1.printStackTrace();
            }

            if (readSize >= 5) {
                IVIPacket iviPacket = parsePacket(packet.array(), 0, readSize);
                if (iviPacket != null) {
                    switch (iviPacket.type) {
                        case CONNECTION_RECEIVE_DATA:
                            try {
                                tunWrite.write(iviPacket.data);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            break;
                        case CONNECTION_HEARTBEAT:
                            Message message = mHandler.obtainMessage();
                            message.obj = "heartbeat";
                            mHandler.sendMessage(message);
                            Log.w(TAG, "receive heartbeat");
                            break;
                    }
                }
            }
        }
    }

    // receive data from tun device and send to server
    private void rxThread(InputStream tunRead, OutputStream socketWrite) {
        byte []buffer = new byte[MAX_MTU];
        while (true) {
            try {
                int count = tunRead.read(buffer);
                Log.w(TAG, "rxThread: " + String.valueOf(count));
                if (count == 0) {
                    Thread.sleep(300);
                }
                else {
                    byte[] packet = buildPacket(CONNECTION_SEND_DATA, buffer, 0, count);
                    socketWrite.write(packet);
                    socketWrite.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void heartbeatThread(OutputStream socketWrite) {
        while (true) {
            try {
                Log.w(TAG, "heartbeatThread: ");
                socketWrite.write(getHeatbeatPacket());
//                Message message = new Message();
//                message.obj = "send heartbeat";
//                mHandler.sendMessage(message);
                Thread.sleep(HEARTBEAT_INTERVAL);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    String getLocalIpv6Address() {
        try {
            String str = "";
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = (NetworkInterface) en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf
                        .getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress() && inetAddress.toString().contains(":")) {
                        String ipaddress = inetAddress.getHostAddress();
                        str = ipaddress.substring(0, ipaddress.length() - 2);
                        return str;
                    }
                }
            }

            Log.w(TAG, "getLocalIpv6Address: " + str);
            return str;
        } catch (SocketException ex) {
            Log.e(TAG, "Exception in Get IP Address: " + ex.toString());
        }
        return null;
    }

    Socket socket;

    private void startVpn() throws Exception {
        try {
            if (socket != null) {
                socket.close();
            }

            String ipv6Addr = getLocalIpv6Address();

            socket = new Socket();
            SocketAddress socketAddress = new InetSocketAddress(ipv6Addr, 0);
            socket.bind(socketAddress);
            protect(socket);

            socket.connect(new InetSocketAddress(mServerAddress, Integer.valueOf(mServerPort)));

            final InputStream socketRead = socket.getInputStream();
            final OutputStream socketWrite = socket.getOutputStream();
            mHandler.sendEmptyMessage(R.string.connected);

            socketWrite.write(getHelloPacket());

            final FileInputStream tunRead;
            final FileOutputStream tunWrite;

            byte[] buffer = new byte[MAX_MTU];
            int readSize = socketRead.read(buffer);
            IVIPacket iviPacket = parsePacket(buffer, 0, readSize);
            if (iviPacket != null && iviPacket.type == CONNECTION_INFO) {
                // establish tun device
                String info = new String(iviPacket.data, 0, iviPacket.length);
                Message msg = mHandler.obtainMessage();
                msg.obj = info;
                mHandler.sendMessage(msg);
                configure(info);
                tunRead = new FileInputStream(mInterface.getFileDescriptor());
                tunWrite = new FileOutputStream(mInterface.getFileDescriptor());
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        txThread(socketRead, tunWrite);
                    }
                }).start();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        heartbeatThread(socketWrite);
                    }
                }).start();
                rxThread(tunRead, socketWrite);
            }
            else {
                throw new RuntimeException("Protocol Error");
            }
        } catch (Exception e) {
            Log.e(TAG, "Got " + e.toString());
        }
    }

    private void configure(String parameters) throws Exception {
        // If the old interface has exactly the same parameters, use it!
        if (mInterface != null && parameters.equals(mParameters)) {
            Log.i(TAG, "Using the previous interface");
            return;
        }
        // Configure a builder while parsing the parameters.
        Builder builder = new Builder();
        String[] parameterArray = parameters.split(" ");
        if (parameterArray.length >= 5) {
            String ip = parameterArray[0];
            String route = parameterArray[1];
            builder.setMtu(1500);
            builder.addAddress(ip, 0);
            builder.addRoute(route, 0);
            builder.addDnsServer("166.111.8.28");
            //for (int i = 0; i < 3; ++i)
            //    builder.addDnsServer(parameterArray[2 + i]);
        }
        // Close the old interface since the parameters have been changed.
        try {
            mInterface.close();
        } catch (Exception e) {
            // ignore
        }
        // Create a new interface using the builder and save the parameters.
        mInterface = builder.setSession(mServerAddress)
                .establish();
        mParameters = parameters;
        Log.i(TAG, "New interface: " + parameters);
    }

}