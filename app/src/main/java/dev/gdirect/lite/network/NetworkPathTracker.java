package dev.gdirect.lite.network;

/** Network handles and VPN presence are routing state, never exported diagnostics. */
public final class NetworkPathTracker {
    public static boolean routableIpv6(java.util.List<String> addresses,boolean defaultRoute) {
        if(!defaultRoute)return false;
        for(String address:addresses)if(IpLiteral.ipv6(address)&&IpLiteral.publicAddress(address))return true;
        return false;
    }
    private boolean initialized,vpn,ipv6;
    private long network;
    public synchronized boolean update(long handle,boolean throughVpn){
        return update(handle,throughVpn,false);
    }
    public synchronized boolean update(long handle,boolean throughVpn,boolean hasIpv6){
        boolean changed=initialized&&(network!=handle||vpn!=throughVpn||ipv6!=hasIpv6);
        initialized=true;network=handle;vpn=throughVpn;ipv6=hasIpv6;return changed;
    }
}
