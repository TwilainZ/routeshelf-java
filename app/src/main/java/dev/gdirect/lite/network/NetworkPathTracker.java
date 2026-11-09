package dev.gdirect.lite.network;

/** Network handles and VPN presence are routing state, never exported diagnostics. */
public final class NetworkPathTracker {
    public enum Ipv6State {
        UNKNOWN(0,"IPv6：暂未取得当前网络的路由信息"),
        OFFLINE(1,"IPv6：当前没有活动网络"),
        NO_GLOBAL_ADDRESS(2,"IPv6：当前网络未提供全局地址，自动探测使用 IPv4"),
        NO_DEFAULT_ROUTE(3,"IPv6：已有全局地址，但没有默认路由，自动探测使用 IPv4"),
        READY(4,"IPv6：当前网络具备全局地址和默认路由，自动探测可使用双栈");
        public final int code;public final String description;
        Ipv6State(int code,String description){this.code=code;this.description=description;}
    }
    public static Ipv6State ipv6State(boolean online,boolean properties,java.util.List<String> addresses,boolean defaultRoute) {
        if(!online)return Ipv6State.OFFLINE;
        if(!properties)return Ipv6State.UNKNOWN;
        boolean global=false;
        for(String address:addresses)if(IpLiteral.ipv6(address)&&IpLiteral.publicAddress(address))global=true;
        if(!global)return Ipv6State.NO_GLOBAL_ADDRESS;
        return defaultRoute?Ipv6State.READY:Ipv6State.NO_DEFAULT_ROUTE;
    }
    public static boolean routableIpv6(java.util.List<String> addresses,boolean defaultRoute) {
        return ipv6State(true,true,addresses,defaultRoute)==Ipv6State.READY;
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
