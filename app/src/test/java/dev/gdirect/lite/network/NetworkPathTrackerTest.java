package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NetworkPathTrackerTest {
    @Test public void ipv6DiagnosticsDistinguishMissingAddressRouteAndPlatformData(){
        java.util.List<String> global=java.util.Collections.singletonList("2409:8000::1");
        assertEquals(NetworkPathTracker.Ipv6State.OFFLINE,NetworkPathTracker.ipv6State(false,false,global,true));
        assertEquals(NetworkPathTracker.Ipv6State.UNKNOWN,NetworkPathTracker.ipv6State(true,false,global,true));
        assertEquals(NetworkPathTracker.Ipv6State.NO_GLOBAL_ADDRESS,NetworkPathTracker.ipv6State(true,true,java.util.Collections.singletonList("fe80::1"),true));
        assertEquals(NetworkPathTracker.Ipv6State.NO_DEFAULT_ROUTE,NetworkPathTracker.ipv6State(true,true,global,false));
        assertEquals(NetworkPathTracker.Ipv6State.READY,NetworkPathTracker.ipv6State(true,true,global,true));
    }
    @Test public void onlyGlobalIpv6WithADefaultRouteEnablesDiscovery(){
        assertFalse(NetworkPathTracker.routableIpv6(java.util.Arrays.asList("fe80::1","fc00::1","142.250.1.1"),true));
        assertFalse(NetworkPathTracker.routableIpv6(java.util.Collections.singletonList("2001:4860::1"),false));
        assertTrue(NetworkPathTracker.routableIpv6(java.util.Arrays.asList("fe80::1","2001:4860::1"),true));
    }
    @Test public void vpnChangeInvalidatesEvenWhenTheNetworkHandleStaysTheSame(){
        NetworkPathTracker paths=new NetworkPathTracker();assertFalse(paths.update(7,false));
        assertFalse(paths.update(7,false));assertTrue(paths.update(7,true));
        assertFalse(paths.update(7,true));assertTrue(paths.update(7,false));
    }
    @Test public void wifiMobileAndDisconnectionEachInvalidateOnce(){
        NetworkPathTracker paths=new NetworkPathTracker();assertFalse(paths.update(0,false));
        assertTrue(paths.update(7,false));assertTrue(paths.update(8,false));assertTrue(paths.update(0,false));
        assertFalse(paths.update(0,false));assertTrue(paths.update(8,false));
    }
}
