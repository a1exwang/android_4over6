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
import android.annotation.TargetApi;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class IVIVpnService extends VpnService {
    public static final String VPN_SERVICE_INTENT_KEY = "IVIVpnService.key";
    public static final int VPN_SERVICE_CONNECT = 0;
    public static final int VPN_SERVICE_DISCONNECT = 1;

    private static final String TAG = "IVIVpnService";
    private static String ServerAddress = "2402:f000:1:4417::900";
    private static final String PCAP_FILE = "packets.pcap";
    private String mServerPort = "5678";
    private static final long StatisticsInterval = 500;
    long lastStatisticsTime = 0;

    Pcap pcap;

    VpnCallbacks vpnCallbacks = new VpnCallbacks() {
        @Override
        public void onHeartbeat() {
            Log.d("IVIVpnService", "on heartbeat");
        }

        @Override
        public void onStatistics(int rBytes, int rPackets, int sBytes, int sPackets) {
            if (System.currentTimeMillis() - lastStatisticsTime > StatisticsInterval) {
                lastStatisticsTime = System.currentTimeMillis();
                Intent intent = new Intent(MainActivity.BROADCAST_NAME);
                intent.putExtra(MainActivity.BROADCAST_INTENT_BYTES_SENT, sBytes);
                intent.putExtra(MainActivity.BROADCAST_INTENT_BYTES_RECEIVED, rBytes);
                intent.putExtra(MainActivity.BROADCAST_INTENT_PACKETS_SEND, sPackets);
                intent.putExtra(MainActivity.BROADCAST_INTENT_PACKETS_RECEIVED, rPackets);
                sendBroadcast(intent);
            }
        }

        @Override
        public int onReceiveDhcpAndCreateTun(String dhcpString) {
            //Socket socket =
            return configure(dhcpString);
        }

        @Override
        public void onPacketReceived(int length, byte type, byte[] packet) {
            //Log.d("IVIVpnService", "onPacketReceived " + String.valueOf(length));
            //pcap.addPacket(packet);
            //pcap.saveToSDCardFile("vpn.pcap");
        }

        @Override
        public void onPacketSent(int length, byte type, byte[] packet) {
            //Log.d("IVIVpnService", "onPacketSent " + String.valueOf(length));
            //pcap.addPacket(packet);
            //pcap.saveToSDCardFile("vpn.pcap");
        }
    };

    static Handler mVpnThreadHandler;
    static final int HT_MESSAGE_READ_SOCKET = 100;
    static final int HT_MESSAGE_STOP_VPN = 101;
    static final int HT_MESSAGE_START_VPN = 102;
    static final int HT_MESSAGE_VPN_CONNECTED = 103;

    static final byte[] VPN_CONTROL_PACKET_SHUTDOWN = { 100 };

    static final Thread looperThread = new Thread() {
        @Override
        public void run() {
            final InputStream[] is = {null};
            final OutputStream[] os = {null};
            final boolean[] connected = {false};

            Looper.prepare();

            mVpnThreadHandler = new Handler() {
                @Override
                public void handleMessage(Message message) {
                    byte[] socketBuf;
                    int size;
                    switch (message.what) {
                        case HT_MESSAGE_VPN_CONNECTED:
                            Object[] ios = (Object[]) message.obj;
                            is[0] = (InputStream) ios[0];
                            os[0] = (OutputStream) ios[1];
                            connected[0] = true;
                            break;
                        case HT_MESSAGE_READ_SOCKET:
                            size = message.arg1;
                            socketBuf = (byte[])message.obj;
                            Log.d(TAG, "read from jni socket bytes " + size);
                            break;
                        case HT_MESSAGE_START_VPN:
                            IVIVpnService self = (IVIVpnService)message.obj;
                            self.startVpnLoop();
                            break;
                        case HT_MESSAGE_STOP_VPN:
                            try {
                                os[0].write(VPN_CONTROL_PACKET_SHUTDOWN);
                                connected[0] = false;
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            break;
                    }
                }
            };
            Looper.loop();
        }
    };

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void startVpnLoop() {
        new Thread() {
            @Override
            public void run() {
                ServerSocket serverSocket;
                Socket clientSocket;

                try {
                    serverSocket = new ServerSocket(0);
                    final int port = serverSocket.getLocalPort();
                    new Thread() {
                        @Override
                        public void run() {
                            VpnDevices vpnDevices = new VpnDevices(vpnCallbacks, port);
                            vpnDevices.startVpn();
                        }
                    }.start();

                    clientSocket = serverSocket.accept();
                    serverSocket.close();
                    InputStream is = clientSocket.getInputStream();
                    OutputStream os = clientSocket.getOutputStream();
                    byte[] buf = new byte[2048];
                    int readCount = is.read(buf);
                    if (readCount != 4 || !(buf[0] == (byte) 0xff &&
                            buf[1] == (byte) 0xee &&
                            buf[2] == (byte) 0xdd &&
                            buf[3] == (byte) 0xcc)) {
                        throw new RuntimeException("magic error");
                    }
                    buf[0] = (byte) 0xff;
                    buf[1] = (byte) 0xee;
                    buf[2] = (byte) 0xdd;
                    buf[3] = (byte) 0xcc;
                    os.write(buf, 0, 4);

                    // connected
                    Message message = mVpnThreadHandler.obtainMessage(HT_MESSAGE_VPN_CONNECTED);
                    message.obj = new Object[]{is, os};
                    message.sendToTarget();

                    while (clientSocket.isConnected()) {
                        readCount = is.read(buf);
                        if (readCount < 0)
                            break;

                        message = mVpnThreadHandler.obtainMessage(HT_MESSAGE_READ_SOCKET);
                        message.arg1 = readCount;
                        message.obj = buf;
                        message.sendToTarget();
                    }
                    clientSocket.close();

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }.start();

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private int configure(String parameters) {
        // Configure a builder while parsing the parameters.
        Builder builder = new Builder();
        String[] parameterArray = parameters.split(" ");
        if (parameterArray.length >= 5) {
            String ip = parameterArray[0];
            String route = parameterArray[1];
            builder.setMtu(1500);
            builder.addAddress(ip, 24);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("166.111.8.28");
            builder.setBlocking(true);
            try {
                builder.addDisallowedApplication("wang.a1ex.android_4over6");
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
            //for (int i = 0; i < 3; ++i)
            //    builder.addDnsServer(parameterArray[2 + i]);
        }
        // Create a new interface using the builder and save the parameters.
        ParcelFileDescriptor tunIf = builder.setSession(ServerAddress).establish();
        Log.i(TAG, "New interface: " + parameters);
        return tunIf.getFd();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Message message;
        switch (intent.getIntExtra(VPN_SERVICE_INTENT_KEY, -1)) {
            case VPN_SERVICE_CONNECT:
                for (int i = 0; i < 5; ++i) {
                    if (mVpnThreadHandler != null) {
                        message = mVpnThreadHandler.obtainMessage(HT_MESSAGE_START_VPN);
                        message.obj = this;
                        message.sendToTarget();
                        break;
                    }
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                break;
            case VPN_SERVICE_DISCONNECT:
                message = mVpnThreadHandler.obtainMessage(HT_MESSAGE_STOP_VPN);
                message.sendToTarget();
                break;
            default:
                if (looperThread.getState() == Thread.State.NEW) {
                    looperThread.start();
                }
        }
        return START_STICKY;
    }
    @Override
    public void onDestroy() {
    }

}