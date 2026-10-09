package com.projectgamers.splitgate.engine;

/** (istemci ip:port, hedef ip:port) dortlusu. */
final class FlowKey {
    final int srcIp, srcPort, dstIp, dstPort;

    FlowKey(int srcIp, int srcPort, int dstIp, int dstPort) {
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.dstIp = dstIp;
        this.dstPort = dstPort;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FlowKey)) return false;
        FlowKey k = (FlowKey) o;
        return srcIp == k.srcIp && srcPort == k.srcPort && dstIp == k.dstIp && dstPort == k.dstPort;
    }

    @Override
    public int hashCode() {
        int h = srcIp;
        h = h * 31 + srcPort;
        h = h * 31 + dstIp;
        h = h * 31 + dstPort;
        return h;
    }
}
