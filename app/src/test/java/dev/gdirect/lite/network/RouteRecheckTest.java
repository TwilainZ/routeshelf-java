package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class RouteRecheckTest {
    static final String HOST="www.googleapis.com",IP="142.250.1.1";
    @Test public void recheckActuallyProbesCacheWithoutClearingOtherRoutes()throws Exception {
        AtomicInteger clears=new AtomicInteger(),probes=new AtomicInteger();
        DirectRoutesTest.Memory store=new DirectRoutesTest.Memory(){@Override public void clear(){clears.incrementAndGet();super.clear();}};
        store.save(HOST,new DirectRoutes.Route(HOST,IP,DirectRoutes.TlsMode.NORMAL,System.currentTimeMillis()));
        store.save("drive.usercontent.google.com",new DirectRoutes.Route("drive.usercontent.google.com",IP,DirectRoutes.TlsMode.NORMAL,System.currentTimeMillis()));
        DirectRoutes routes=new DirectRoutes(host->{throw new AssertionError("Healthy cache should not need DNS");},route->probes.incrementAndGet(),store,message->{});
        assertEquals(IP,routes.check(HOST).ip);assertEquals(1,probes.get());assertEquals(0,clears.get());
        assertNotNull(store.load("drive.usercontent.google.com"));
        assertEquals(IP,routes.select(HOST,Collections.emptySet()).ip);assertEquals(1,probes.get());
    }
    @Test public void startingARecheckDoesNotInvalidateAnInFlightSelection()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        DirectRoutes routes=new DirectRoutes(host->Collections.singletonList(IP),route->{
            entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
        },new DirectRoutesTest.Memory(),message->{});
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<DirectRoutes.Route> listing=workers.submit(()->routes.select(HOST,Collections.emptySet()));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<DirectRoutes.Route> check=workers.submit(()->routes.check(HOST));release.countDown();
            assertEquals(IP,listing.get(4,TimeUnit.SECONDS).ip);assertEquals(IP,check.get(4,TimeUnit.SECONDS).ip);
        }finally{release.countDown();workers.shutdownNow();}
    }
    @Test public void cancellingCachedVerificationDoesNotPoisonTheWorkingCache()throws Exception {
        DirectRoutesTest.Memory store=new DirectRoutesTest.Memory();
        store.save(HOST,new DirectRoutes.Route(HOST,IP,DirectRoutes.TlsMode.NORMAL,System.currentTimeMillis()));
        CountDownLatch entered=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        DirectRoutes routes=new DirectRoutes(host->Collections.emptyList(),route->{
            entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}
        },store,message->{});
        Thread worker=new Thread(()->{try{routes.check(HOST);}catch(Throwable error){failure.set(error);}});
        worker.start();try{assertTrue(entered.await(2,TimeUnit.SECONDS));worker.interrupt();worker.join(2000);
            assertFalse(worker.isAlive());assertTrue(failure.get() instanceof java.io.InterruptedIOException);
            assertEquals(IP,routes.select(HOST,Collections.emptySet()).ip);
        }finally{worker.interrupt();}
    }
    @Test public void failedCachedEndpointIsReplacedByAFreshlyVerifiedRoute()throws Exception {
        DirectRoutesTest.Memory store=new DirectRoutesTest.Memory();
        store.save(HOST,new DirectRoutes.Route(HOST,IP,DirectRoutes.TlsMode.NORMAL,System.currentTimeMillis()));
        DirectRoutes routes=new DirectRoutes(host->Collections.singletonList("142.250.1.2"),route->{if(route.ip.equals(IP))throw new IOException("fixture closed");},store,message->{});
        assertEquals("142.250.1.2",routes.check(HOST).ip);
    }
    @Test public void recheckKeepsTheExistingVerifiedBackupPool()throws Exception {
        List<DirectRoutes.Route> backups=new ArrayList<>();
        for(int i=1;i<=3;i++)backups.add(new DirectRoutes.Route(HOST,"142.250.1."+i,DirectRoutes.TlsMode.NORMAL,System.currentTimeMillis()));
        DirectRoutesTest.Memory store=new DirectRoutesTest.Memory(){
            @Override public List<DirectRoutes.Route> loadAll(String host){return new ArrayList<>(backups);}
            @Override public void saveAll(String host,List<DirectRoutes.Route> updated){backups.clear();backups.addAll(updated);save(host,updated.get(0));}
        };
        DirectRoutes routes=new DirectRoutes(host->Collections.emptyList(),route->{},store,message->{});
        assertEquals(IP,routes.check(HOST).ip);assertEquals(3,backups.size());
    }
    @Test public void cacheWriteFailureDoesNotRejectAHealthyNetworkRoute()throws Exception {
        DirectRoutesTest.Memory store=new DirectRoutesTest.Memory(){
            @Override public void saveAll(String host,List<DirectRoutes.Route> routes){throw new IllegalStateException("fixture storage unavailable");}
        };
        store.save(HOST,new DirectRoutes.Route(HOST,IP,DirectRoutes.TlsMode.NORMAL,System.currentTimeMillis()));
        DirectRoutes routes=new DirectRoutes(host->Collections.emptyList(),route->{},store,message->{});
        assertThrows(IllegalStateException.class,()->routes.check(HOST));
        assertEquals(IP,store.load(HOST).ip);assertEquals(IP,routes.select(HOST,Collections.emptySet()).ip);
    }
}
