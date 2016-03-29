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
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class IVIVpnService extends VpnService implements Handler.Callback, Runnable {
    private static final String TAG = "IVIVpnService";
    private String mServerAddress = "2402:f000:1:4417::900";
    private String mServerPort = "5678";
    private Handler mHandler;
    private Thread mThread;
    private static final int HEARTBEAT_INTERVAL = 5000;
    Socket socket;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mHandler == null) {
            mHandler = new Handler(this);
        }
        if (mThread != null) {
            mThread.interrupt();
        }
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
            socket = new Socket();
            protect(socket);

            socket.connect(new InetSocketAddress(mServerAddress, Integer.valueOf(mServerPort)));

            final InputStream socketRead = socket.getInputStream();

            final OutputStream socketWrite = socket.getOutputStream();
            Message message = writeSocketHandler.obtainMessage(MSG_WRITE_SOCKET_SET_SOCKET_WRITE_STREAM);
            message.obj = socketWrite;
            writeSocketHandler.sendMessage(message);

            new Thread() {
                @Override
                public void run() {
                    Looper.prepare();
                    writeSocketHandler = new WriteSocketHandler();
                    Looper.loop();
                }
            }.start();
            new Thread() {
                @Override
                public void run() {
                    Looper.prepare();
                    writeTunHandler = new WriteTunHandler();
                    Looper.loop();
                }
            }.start();
            new Thread() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            writeSocketHandler.sendEmptyMessage(MSG_WRITE_SOCKET_HEARTBEAT);
                            sleep(HEARTBEAT_INTERVAL);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }.start();


            socketReadThread(socketRead);
        } catch (Exception e) {
            Log.e(TAG, "Got " + e.toString());
        }
    }

    static final int MSG_WRITE_SOCKET_HELLO = 0;
    static final int MSG_WRITE_SOCKET_HEARTBEAT = 1;
    static final int MSG_WRITE_SOCKET_DATA = 2;
    static final int MSG_WRITE_TUN_DATA = 3;
    static final int MSG_WRITE_SOCKET_SET_SOCKET_WRITE_STREAM = 4;
    static final int MSG_WRITE_TUN_SET_TUN_WRITE_STREAM = 5;

    static final int MSG_QUIT = -1;

    WriteSocketHandler writeSocketHandler;
    WriteTunHandler writeTunHandler;

    static class WriteSocketHandler extends Handler {
        OutputStream socketWrite;
        @Override
        public void handleMessage(Message message) {
            try {
                switch (message.what) {
                    case MSG_WRITE_SOCKET_SET_SOCKET_WRITE_STREAM:
                        socketWrite = (OutputStream) message.obj;
                        break;
                    case MSG_WRITE_SOCKET_HELLO:
                        socketWrite.write(PacketHelper.getHelloPacket());
                        break;
                    case MSG_WRITE_SOCKET_DATA:
                        byte[] data = (byte[])message.obj;
                        if (data.length > 0) {
                            byte[] packet = PacketHelper.buildPacket(PacketHelper.CONNECTION_SEND_DATA, data, 0, data.length);
                            socketWrite.write(packet);
                        }
                        break;
                    case MSG_WRITE_SOCKET_HEARTBEAT:
                        socketWrite.write(PacketHelper.getHeatbeatPacket());
                        break;
                    case MSG_QUIT:
                        getLooper().quit();
                        break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    static class WriteTunHandler extends Handler {
        OutputStream tunWrite;
        @Override
        public void handleMessage(Message message) {
            try {
                switch (message.what) {
                    case MSG_WRITE_TUN_SET_TUN_WRITE_STREAM:
                        tunWrite = (OutputStream)message.obj;
                        break;
                    case MSG_WRITE_TUN_DATA:
                        byte[] data = (byte[]) message.obj;
                        tunWrite.write(data);
                        break;
                    case MSG_QUIT:
                        getLooper().quit();
                        break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static final int MSG_READ_SOCKET_INFO = 20;
    static final int MSG_READ_SOCKET_SET_INPUT_STREAM = 21;
    static final int MSG_READ_SOCKET_RECEIVE_DATA = 22;
    static final int MSG_READ_SOCKET_HEARTBEAT = 23;
    class ReadSocketHandler extends Handler {
        InputStream socketRead;
        @Override
        public void handleMessage(Message message) {
            switch(message.what) {
                case MSG_READ_SOCKET_INFO:
                    String info = new String((byte[])message.obj);
                    tunFd = configure(info);
                    final InputStream tunRead = new FileInputStream(tunFd.getFileDescriptor());
                    FileOutputStream tunWrite = new FileOutputStream(tunFd.getFileDescriptor());
                    Message msg = writeTunHandler.obtainMessage(MSG_WRITE_TUN_SET_TUN_WRITE_STREAM);
                    msg.obj = tunWrite;
                    writeTunHandler.sendMessage(msg);

                    new Thread() {
                        @Override
                        public void run() {
                            tunReadThread(tunRead);
                        }
                    }.start();
                    break;
                case MSG_READ_SOCKET_RECEIVE_DATA:
                    Message msg = writeTunHandler.obtainMessage(MSG_WRITE_TUN_DATA);
                    msg.obj = message.obj;
                    writeTunHandler.sendMessage(msg);
                    break;
                case MSG_READ_SOCKET_SET_INPUT_STREAM:
                    socketRead = (InputStream)message.obj;
                    break;
                case MSG_READ_SOCKET_HEARTBEAT:
                    message = mHandler.obtainMessage();
                    message.obj = "heartbeat";
                    mHandler.sendMessage(message);
                    Log.w(TAG, "receive heartbeat");
                    break;
            }
        }
    }
    ReadSocketHandler readSocketHandler;

    class ReadTunHandler extends Handler {
        @Override
        public void handleMessage(Message message) {

        }
    }

    ParcelFileDescriptor tunFd;
    private void tunReadThread(InputStream inputStream){
        byte []buffer = new byte[PacketHelper.MAX_MTU];
        while (true) {
            try {
                int count = inputStream.read(buffer);
                Log.w(TAG, "socket read: " + String.valueOf(count));
                byte[] data = new byte[count];
                System.arraycopy(buffer, 0, data, 0, count);

                Message message = writeSocketHandler.obtainMessage(MSG_WRITE_SOCKET_DATA);
                message.obj = data;
                writeSocketHandler.sendMessage(message);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    private void socketReadThread(InputStream inputStream) {
        while (true) {
            ByteBuffer packet = ByteBuffer.allocate(32767);
            int readSize = 0;
            try {
                readSize = inputStream.read(packet.array());
            } catch (IOException e1) {
                e1.printStackTrace();
            }

            if (readSize < 5)
                continue;


            PacketHelper.IVIPacket iviPacket = PacketHelper.parsePacket(packet.array(), 0, readSize);
            if (iviPacket == null)
                continue;

            Log.w(TAG, "socket read: " + readSize);
            Message message;
            switch (iviPacket.type) {
                case PacketHelper.CONNECTION_INFO:

                    break;
                case PacketHelper.CONNECTION_RECEIVE_DATA:

                    break;
                case PacketHelper.CONNECTION_HEARTBEAT:

                    break;
            }
        }
    }

//    // receive data from server and send to tun device
//    private void txThread(InputStream socketRead, OutputStream tunWrite) {
//        while (true) {
//            ByteBuffer packet = ByteBuffer.allocate(32767);
//            int readSize = 0;
//            try {
//                readSize = socketRead.read(packet.array());
//                Log.w(TAG, "txThread: " + readSize);
//            } catch (IOException e1) {
//                e1.printStackTrace();
//            }
//
//            if (readSize >= 5) {
//                PacketHelper.IVIPacket iviPacket = PacketHelper.parsePacket(packet.array(), 0, readSize);
//                if (iviPacket != null) {
//                    switch (iviPacket.type) {
//                        case PacketHelper.CONNECTION_RECEIVE_DATA:
//                            try {
//                                tunWrite.write(iviPacket.data);
//                            } catch (IOException e) {
//                                e.printStackTrace();
//                            }
//                            break;
//                        case PacketHelper.CONNECTION_HEARTBEAT:
//                            Message message = mHandler.obtainMessage();
//                            message.obj = "heartbeat";
//                            mHandler.sendMessage(message);
//                            Log.w(TAG, "receive heartbeat");
//                            break;
//                    }
//                }
//            }
//        }
//    }
//
//    // receive data from tun device and send to server
//    private void rxThread(InputStream tunRead, OutputStream socketWrite) {
//        byte []buffer = new byte[PacketHelper.MAX_MTU];
//        while (true) {
//            try {
//                int count = tunRead.read(buffer);
//                Log.w(TAG, "rxThread: " + String.valueOf(count));
//                if (count == 0) {
//                    Thread.sleep(300);
//                }
//                else {
//                    byte[] packet = PacketHelper.buildPacket(PacketHelper.CONNECTION_SEND_DATA, buffer, 0, count);
//                    socketWrite.write(packet);
//                    socketWrite.flush();
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            } catch (InterruptedException e) {
//                break;
//            }
//        }
//    }

    // send heartbeat package
//    private void heartbeatThread(OutputStream socketWrite) {
//        while (true) {
//            try {
//                Log.w(TAG, "heartbeatThread: ");
//                socketWrite.write(PacketHelper.getHeatbeatPacket());
//                Thread.sleep(HEARTBEAT_INTERVAL);
//            } catch (InterruptedException e) {
//                break;
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//    }

    private ParcelFileDescriptor configure(String parameters) {
        ParcelFileDescriptor ret;
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

        ret = builder.setSession(mServerAddress)
                .establish();
        return ret;
    }
}