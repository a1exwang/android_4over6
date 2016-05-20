    public interface VpnCallbacks {
        void onHeartBeat();
        void onStatistics(int rBytes, int rPackets, int sBytes, int sPackets);
        int onReceiveDhcpAndCreateTun(String dhcpString) throws Exception;
    }