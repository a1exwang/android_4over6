package wang.a1ex.android_4over6;
public interface VpnCallbacks {
    void onHeartbeat();
    void onStatistics(int rBytes, int rPackets, int sBytes, int sPackets);
    int onReceiveDhcpAndCreateTun(String dhcpString);

    void onPacketReceived(int length, byte type, byte[] packet);
    void onPacketSent(int length, byte type, byte[] packet);
}