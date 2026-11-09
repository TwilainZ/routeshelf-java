package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NetworkPathTrackerTest {
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
