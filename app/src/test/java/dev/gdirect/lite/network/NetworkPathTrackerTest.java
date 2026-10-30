package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NetworkPathTrackerTest {
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
