package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class DirectRoutesTest {
    static final String HOST = "www.googleapis.com";
    static final class Memory implements DirectRoutes.Store {
        final Map<String,DirectRoutes.Route> values = new ConcurrentHashMap<>();
        public DirectRoutes.Route load(String host) { return values.get(host); }
        public void save(String host,DirectRoutes.Route route) { values.put(host,route); }
        public void remove(String host) { values.remove(host); }
        public void clear() { values.clear(); }
    }
    @Test public void reusesFreshCachePerHostAndReprobesExpiredCache() throws Exception {
        Memory memory = new Memory(); AtomicInteger discoveries = new AtomicInteger();
        DirectRoutes routes = new DirectRoutes(host -> { discoveries.incrementAndGet(); return Arrays.asList("8.8.8.8","8.8.4.4"); },
            route -> { },memory,message -> { });
        DirectRoutes.Route first = routes.select(HOST,Collections.emptySet());
        int prior = discoveries.get();
        assertSame(first,routes.select(HOST,Collections.emptySet())); assertEquals(prior,discoveries.get());
        routes.select("drive.usercontent.google.com",Collections.emptySet()); assertEquals(prior+1,discoveries.get());
        memory.save(HOST,new DirectRoutes.Route(HOST,first.ip,first.tls,System.currentTimeMillis()-DirectRoutes.MAX_AGE-1));
        prior = discoveries.get(); routes.select(HOST,Collections.emptySet()); assertEquals(prior+1,discoveries.get());
    }
    @Test public void failedRouteIsNotReusedAndNetworkResetClearsCache() throws Exception {
        Memory memory = new Memory();
        DirectRoutes routes = new DirectRoutes(host -> Arrays.asList("8.8.8.8","8.8.4.4"),route -> { },memory,message -> { });
        DirectRoutes.Route first = routes.select(HOST,Collections.emptySet());
        routes.reject(first); assertNull(memory.load(HOST));
        DirectRoutes.Route second = routes.select(HOST,Collections.emptySet());
        assertNotEquals(first.key(),second.key());
        routes.reset(); assertTrue(memory.values.isEmpty());
    }
    @Test public void blockedProbeCannotHangSelectionAndCancellationDoesNotPersistSuccess() throws Exception {
        Memory memory = new Memory(); AtomicReference<CountDownLatch> started = new AtomicReference<>(new CountDownLatch(1));
        DirectRoutes routes = new DirectRoutes(host -> Collections.singletonList("8.8.8.8"),route -> {
            started.get().countDown();
            try { new CountDownLatch(1).await(2,TimeUnit.SECONDS); }
            catch (InterruptedException error) { throw new IOException("interrupted"); }
        },memory,message -> { },100);
        long start = System.nanoTime();
        assertThrows(IOException.class,() -> routes.select(HOST,Collections.emptySet()));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);
        assertTrue(memory.values.isEmpty());
        routes.reset(); started.set(new CountDownLatch(1)); AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { routes.select(HOST,Collections.emptySet()); }
            catch (IOException error) { result.set(error); }
        });
        worker.start(); assertTrue(started.get().await(1,TimeUnit.SECONDS)); worker.interrupt(); worker.join(1000);
        assertFalse(worker.isAlive()); assertNotNull(result.get()); assertTrue(memory.values.isEmpty());
    }
    @Test public void normalTlsFailureCanUseVerifiedNoSniAndRejectsNonPublicCandidates() throws Exception {
        Memory memory = new Memory();
        DirectRoutes routes = new DirectRoutes(host -> Arrays.asList("198.18.0.1","127.0.0.1","10.1.1.1","8.8.8.8"),route -> {
            assertEquals("8.8.8.8",route.ip);
            if (route.tls == DirectRoutes.TlsMode.NORMAL) throw new IOException("blocked");
        },memory,message -> { });
        assertEquals(DirectRoutes.TlsMode.NO_SNI,routes.select(HOST,Collections.emptySet()).tls);
        for (String bad : new String[]{"10.0.0.1","127.0.0.1","198.19.1.1","169.254.1.1","100.64.0.1","192.0.2.1","198.51.100.2","203.0.113.1","224.1.1.1","8.08.8.8","google.com","256.1.1.1"})
            assertFalse(bad,DirectRoutes.publicIpv4(bad));
        assertThrows(IOException.class,() -> routes.select("example.net",Collections.emptySet()));
    }

    @Test public void failedDiscoveryHasCooldownAndExplicitResetAllowsRetry() throws Exception {
        Memory memory = new Memory(); AtomicInteger discoveries = new AtomicInteger();
        DirectRoutes routes = new DirectRoutes(host -> { discoveries.incrementAndGet(); return Collections.singletonList("8.8.8.8"); },
            route -> { throw new IOException("fixture failure"); },memory,message -> { });
        assertThrows(IOException.class,() -> routes.select(HOST,Collections.emptySet()));
        assertThrows(IOException.class,() -> routes.select(HOST,Collections.emptySet()));
        assertEquals(1,discoveries.get()); routes.reset();
        assertThrows(IOException.class,() -> routes.select(HOST,Collections.emptySet())); assertEquals(2,discoveries.get());
    }

    @Test public void networkSwitchDiscardsOldDiscoveryResult() throws Exception {
        Memory memory = new Memory(); CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        DirectRoutes routes = new DirectRoutes(host -> Collections.singletonList("8.8.8.8"),route -> {
            started.countDown();
            try { if (!release.await(2,TimeUnit.SECONDS)) throw new IOException("fixture timeout"); }
            catch (InterruptedException error) { throw new IOException("fixture canceled"); }
        },memory,message -> { });
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { routes.select(HOST,Collections.emptySet()); }
            catch (IOException error) { result.set(error); }
        });
        worker.start(); assertTrue(started.await(1,TimeUnit.SECONDS)); routes.reset(); release.countDown(); worker.join(1000);
        assertFalse(worker.isAlive()); assertNotNull(result.get()); assertTrue(memory.values.isEmpty());
    }

    @Test public void networkResetDuringCacheLoadCannotReturnAnOldRoute() throws Exception {
        AtomicReference<DirectRoutes> selector=new AtomicReference<>();
        DirectRoutes.Route stale=new DirectRoutes.Route(HOST,"8.8.8.8",DirectRoutes.TlsMode.NORMAL,System.currentTimeMillis());
        DirectRoutes.Store store=new DirectRoutes.Store() {
            public DirectRoutes.Route load(String host){selector.get().reset();return stale;}
            public void save(String host,DirectRoutes.Route route){fail("stale route saved");}
            public void remove(String host){}
            public void clear(){}
        };
        selector.set(new DirectRoutes(host->Collections.emptyList(),route->{fail("stale probe");},store,message->{}));
        assertThrows(IOException.class,()->selector.get().select(HOST,Collections.emptySet()));
    }

    @Test public void staleHistoryCannotExcludeFreshDnsAndDnsCannotExcludeBootstrapHints() {
        java.util.List<String> history = new java.util.ArrayList<>(), fresh = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) { history.add("8.8.8." + i); fresh.add("8.8.4." + i); }
        java.util.List<String> merged = DirectRoutes.candidates(fresh,history);
        assertEquals(16,merged.size()); assertTrue(merged.contains(fresh.get(0))); assertTrue(merged.contains(history.get(0)));
        assertEquals(history.subList(0,16),DirectRoutes.candidates(Collections.singletonList("198.18.0.1"),history));
        assertTrue(DirectRoutes.publicIpv4("192.2.1.1")); assertFalse(DirectRoutes.publicIpv4("192.0.2.1"));
    }
}
