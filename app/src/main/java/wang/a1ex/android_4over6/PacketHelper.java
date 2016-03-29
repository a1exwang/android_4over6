package wang.a1ex.android_4over6;

/**
 * Created by alexwang on 3/29/16.
 */
public class PacketHelper {
    static byte []getHelloPacket() {
        byte []buffer = new byte[5];
        buffer[0]= 5;buffer[1]=buffer[2]=0;
        buffer[3] = 0;
        buffer[4] = CONNECTION_HELLO;
        return buffer;
    }
    static byte []getHeatbeatPacket() {
        byte []buffer = new byte[5];
        buffer[0]=0;buffer[1]=buffer[2]=0;
        buffer[3] = 0;
        buffer[4] = CONNECTION_HEARTBEAT;
        return buffer;
    }

    static int packetLengthInt(byte[] buffer, int start) {
        return (buffer[start]) +
                (buffer[start + 1] << 8) +
                (buffer[start + 2] << 16) +
                (buffer[start + 3] << 24);
    }

    static void packetLengthIntWrite(byte[] buffer, int start, int value) {
        buffer[start + 3] = (byte)(value >> 24);
        buffer[start + 2] = (byte)((value >> 16) & 0xFF);
        buffer[start + 1] = (byte)((value >> 8) & 0xFF);
        buffer[start + 0] = (byte)((value) & 0xFF);
    }

    public static final byte CONNECTION_HELLO = 100;
    public static final byte CONNECTION_INFO = 101;
    public static final byte CONNECTION_SEND_DATA = 102;
    public static final byte CONNECTION_RECEIVE_DATA = 103;
    public static final byte CONNECTION_HEARTBEAT = 104;
    public static final int MAX_MTU = 65536;

    public static class IVIPacket {
        public byte type;
        public int length;
        public byte[] data;
    }

    public static final int PACKET_LENGTH_OFFSET = 4;
    public static final int PACKET_DATA_OFFSET = 5;

    public static byte []buildPacket(byte type, byte[] data, int offset, int length) {
        int packetLength = length + 5;
        byte []ret = new byte[packetLength];
        packetLengthIntWrite(ret, 0, packetLength);
        ret[PACKET_LENGTH_OFFSET] = type;
        for (int i = 0; i < length; ++i) {
            ret[PACKET_DATA_OFFSET + i] = data[offset + i];
        }
        return ret;
    }

    public static IVIPacket parsePacket(byte[] packet, int offset, int length) {
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
}
