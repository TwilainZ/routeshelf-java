package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class Ipv6RoutesTest {
    @Test public void ipv6TcpBlackholesDoNotOccupyIpv4Workers()throws Exception { blockedFamily(false); }
    @Test public void ipv6TlsBlackholesDoNotOccupyIpv4Workers()throws Exception { blockedFamily(true); }
    @Test public void ipv4TcpBlackholesDoNotDelayTheWorkingIpv6Family()throws Exception {
        List<String> ips=new ArrayList<>();for(int i=1;i<=32;i++)ips.add("142.250.1."+i);ips.add("2001:4860::8888");
        CountDownLatch release=new CountDownLatch(1);
        DirectRoutes routes=new DirectRoutes(host->ips,new DirectRoutes.Probe(){
            public void connect(String ip)throws IOException {
                if(IpLiteral.ipv6(ip))return;
                try{release.await(6,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
                throw new IOException("fixture IPv4 blackhole");
            }
            public void check(DirectRoutes.Route route){}
        },new DirectRoutesTest.Memory(),message->{},3000);
        long began=System.nanoTime();
        try{assertEquals("2001:4860::8888",routes.select("www.googleapis.com",Collections.emptySet()).ip);}
        finally{release.countDown();}
        assertTrue(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(2));
    }
    private void blockedFamily(boolean tls)throws Exception {
        List<String> addresses=new ArrayList<>();
        for(int i=1;i<=32;i++)addresses.add("2001:4860::"+Integer.toHexString(i));
        addresses.add("142.250.1.1");
        CountDownLatch release=new CountDownLatch(1);
        DirectRoutes.Probe probe=new DirectRoutes.Probe(){
            void waitForClose()throws IOException {
                try{release.await(6,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
                throw new IOException("fixture IPv6 blackhole");
            }
            public void connect(String ip)throws IOException {if(!tls&&IpLiteral.ipv6(ip))waitForClose();}
            public void check(DirectRoutes.Route route)throws IOException {if(tls&&IpLiteral.ipv6(route.ip))waitForClose();}
        };
        DirectRoutes routes=new DirectRoutes(host->addresses,probe,new DirectRoutesTest.Memory(),message->{},3000);
        long began=System.nanoTime();
        try{assertEquals("142.250.1.1",routes.select("www.googleapis.com",Collections.emptySet()).ip);}
        finally{release.countDown();}
        assertTrue("IPv4 waited for blocked IPv6",System.nanoTime()-began<TimeUnit.SECONDS.toNanos(2));
    }
    @Test public void ipv6RoutesCacheAndKeepTheExistingTlsIdentityFallback()throws Exception {
        DirectRoutesTest.Memory store=new DirectRoutesTest.Memory();
        DirectRoutes routes=new DirectRoutes(host->Collections.singletonList("2001:4860::8888"),route->{
            if(route.tls==DirectRoutes.TlsMode.NORMAL)throw new IOException("fixture blocked identity");
        },store,message->{});
        DirectRoutes.Route route=routes.select("www.googleapis.com",Collections.emptySet());
        assertEquals("2001:4860::8888",route.ip);assertNotEquals(DirectRoutes.TlsMode.NORMAL,route.tls);
        assertEquals(route.key(),routes.select(route.host,Collections.emptySet()).key());
    }
    @Test public void familyAvailabilityChangeInvalidatesTheSameAndroidNetwork() {
        NetworkPathTracker tracker=new NetworkPathTracker();
        assertFalse(tracker.update(7,false,false));assertTrue(tracker.update(7,false,true));
        assertFalse(tracker.update(7,false,true));assertTrue(tracker.update(7,false,false));
    }
}
