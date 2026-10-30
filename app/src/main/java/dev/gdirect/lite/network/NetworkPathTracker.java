package dev.gdirect.lite.network;

/** Network handles and VPN presence are routing state, never exported diagnostics. */
public final class NetworkPathTracker {
    private boolean initialized,vpn;
    private long network;
    public synchronized boolean update(long handle,boolean throughVpn){
        boolean changed=initialized&&(network!=handle||vpn!=throughVpn);
        initialized=true;network=handle;vpn=throughVpn;return changed;
    }
}
