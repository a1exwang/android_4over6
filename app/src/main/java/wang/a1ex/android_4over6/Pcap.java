package wang.a1ex.android_4over6;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by alexwang on 3/29/16.
 */
public class Pcap {
    public static final int LINKTYPE_IEEE802_11 = 105;
    public static final int LINKTYPE_RAW = 101;

    ByteArrayOutputStream buffer;
    long startTime;
    public Pcap() {
        buffer = new ByteArrayOutputStream();
        writeInt32(0xa1b2c3d4);
        writeInt16((short) 2);
        writeInt16((short)4);
        writeInt32(0);
        writeInt32(1);
        writeInt32(65536);
        writeInt32(LINKTYPE_RAW);
        startTime = System.currentTimeMillis();
    }

    public synchronized void addPacket(byte[] packet, int offset, int count) {
        long deltaTime = System.currentTimeMillis() - startTime;
        long sec = deltaTime / 1000000;
        long millis = deltaTime - (sec * 1000000);
        writeInt32((int)sec);
        writeInt32((int)millis);
        writeInt32(count);
        writeInt32(count);
        buffer.write(packet, offset, count);
    }
    public synchronized void addPacket(byte[] packet) {
        addPacket(packet, 0, packet.length);
    }
    public synchronized byte[] toBytes() {
        return buffer.toByteArray();
    }
    public synchronized void saveToSDCardFile(String file) {
        File f = new File(Environment.getExternalStorageDirectory(), file);
        try {
            FileOutputStream op = new FileOutputStream(f);
            byte[] bytes = buffer.toByteArray();
            op.write(bytes);
            op.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void writeInt32(int x) {
        buffer.write(x & 0xFF);
        buffer.write((x >> 8) & 0xFF);
        buffer.write((x >> 16) & 0xFF);
        buffer.write((x >> 24) & 0xFF);
    }
    private void writeInt16(short x) {
        buffer.write(x & 0xFF);
        buffer.write((x >> 8) & 0xFF);
    }
}
