package dev.gdirect.lite.network;

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
    @Test public void expandsOnlyAfterFastCandidatesFailAndKeepsRelayOptInExplicit()throws Exception {
        AtomicInteger expanded=new AtomicInteger();
        DirectRoutes.Candidates source=new DirectRoutes.Candidates(){
            public java.util.List<String> get(String host){return Collections.singletonList("8.8.8.8");}
            public java.util.List<String> expand(String host,java.util.List<String> observed){expanded.incrementAndGet();assertTrue(observed.contains("8.8.8.8"));return Collections.singletonList("8.8.4.4");}
        };
        DirectRoutes fast=new DirectRoutes(source,route->{},new Memory(),message->{});
        fast.select(HOST,Collections.emptySet());assertEquals(0,expanded.get());
        DirectRoutes deeper=new DirectRoutes(source,route->{if(route.ip.equals("8.8.8.8"))throw new IOException("blocked");},new Memory(),message->{});
        assertEquals("8.8.4.4",deeper.select(HOST,Collections.emptySet()).ip);assertEquals(1,expanded.get());
        String relay="183.56.143.147";
        DirectRoutes optIn=new DirectRoutes(host->Collections.singletonList(relay),route->{},new Memory(),message->{},1000,relay::equals);
        assertEquals(relay,optIn.select(HOST,Collections.emptySet()).ip);
        assertFalse(DirectRoutes.automaticAddress(relay));
    }
    @Test public void verifiedBackupsAvoidASecondDiscoveryAndFrontendIdentityCanRecover()throws Exception {
        java.util.List<DirectRoutes.Route> pool=new java.util.ArrayList<>();Memory memory=new Memory(){
            @Override public java.util.List<DirectRoutes.Route> loadAll(String host){return new java.util.ArrayList<>(pool);}
            @Override public void saveAll(String host,java.util.List<DirectRoutes.Route> values){pool.clear();pool.addAll(values);save(host,values.get(0));}
        };
        AtomicInteger discoveries=new AtomicInteger();
        DirectRoutes routes=new DirectRoutes(host->{discoveries.incrementAndGet();return Arrays.asList("8.8.8.8","8.8.4.4");},route->{
            if(route.tls!=DirectRoutes.TlsMode.GOOGLE_FRONT)throw new IOException("real name blocked");
        },memory,message->{});
        DirectRoutes.Route first=routes.select(HOST,Collections.emptySet());assertEquals(DirectRoutes.TlsMode.GOOGLE_FRONT,first.tls);assertEquals(2,pool.size());
        routes.reject(first);DirectRoutes.Route backup=routes.select(HOST,Collections.singleton(first.key()));
        assertNotEquals(first.ip,backup.ip);assertEquals(1,discoveries.get());
    }
    @Test public void consentedRelaySucceedsBeforeCatalogDnsOrExpansion()throws Exception {
        String relay="183.56.143.147";AtomicInteger probes=new AtomicInteger();
        DirectRoutes.Candidates source=new DirectRoutes.Candidates(){
            public java.util.List<String> preferred(String host){return Collections.singletonList(relay);}
            public java.util.List<String> get(String host){throw new AssertionError("Catalog/DNS must not block working relay");}
            public java.util.List<String> expand(String host,java.util.List<String> observed){throw new AssertionError("Unexpected scan");}
        };
        Memory memory=new Memory();DirectRoutes routes=new DirectRoutes(source,route->{
            probes.incrementAndGet();if(route.tls!=DirectRoutes.TlsMode.GOOGLE_FRONT)throw new IOException("blocked TLS mode");
        },memory,message->{},1000,relay::equals);
        assertEquals(relay,routes.select(HOST,Collections.emptySet()).ip);assertTrue(probes.get()>0);
        assertEquals(relay,memory.load(HOST).ip);
    }
    @Test public void failedPreferredRelayFallsBackToNormalGoogleDiscovery()throws Exception {
        String relay="183.56.143.147";AtomicInteger catalogs=new AtomicInteger();
        DirectRoutes.Candidates source=new DirectRoutes.Candidates(){
            public java.util.List<String> preferred(String host){return Collections.singletonList(relay);}
            public java.util.List<String> get(String host){catalogs.incrementAndGet();return Collections.singletonList("8.8.8.8");}
        };
        DirectRoutes routes=new DirectRoutes(source,route->{if(route.ip.equals(relay))throw new IOException("relay down");},new Memory(),message->{},2000,DirectRoutes::publicIpv4);
        assertEquals("8.8.8.8",routes.select(HOST,Collections.emptySet()).ip);assertEquals(1,catalogs.get());
    }
    @Test public void relayPreferenceCannotOverrideGoogleOnlyPermission()throws Exception {
        String relay="183.56.143.147";
        DirectRoutes.Candidates source=new DirectRoutes.Candidates(){
            public java.util.List<String> preferred(String host){return Collections.singletonList(relay);}
            public java.util.List<String> get(String host){return Collections.singletonList("8.8.8.8");}
        };
        DirectRoutes routes=new DirectRoutes(source,route->{assertNotEquals(relay,route.ip);},new Memory(),message->{});
        assertEquals("8.8.8.8",routes.select(HOST,Collections.emptySet()).ip);
    }
    @Test public void stalledPreferenceLeavesBudgetForGoogleFallback()throws Exception {
        String relay="183.56.143.147";
        DirectRoutes.Candidates source=new DirectRoutes.Candidates(){
            public java.util.List<String> preferred(String host){return Collections.singletonList(relay);}
            public java.util.List<String> get(String host){return Collections.singletonList("8.8.8.8");}
        };
        DirectRoutes routes=new DirectRoutes(source,route->{if(route.ip.equals(relay))try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}},new Memory(),message->{},1200,DirectRoutes::publicIpv4);
        assertEquals("8.8.8.8",routes.select(HOST,Collections.emptySet()).ip);
    }
    @Test public void cancellationDuringPreferredProbeSkipsExpensiveDiscovery()throws Exception {
        String relay="183.56.143.147";CountDownLatch entered=new CountDownLatch(1);Memory memory=new Memory();
        DirectRoutes.Candidates source=new DirectRoutes.Candidates(){
            public java.util.List<String> preferred(String host){return Collections.singletonList(relay);}
            public java.util.List<String> get(String host){throw new AssertionError("Cancelled check started catalog/DNS");}
        };
        DirectRoutes routes=new DirectRoutes(source,route->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){throw new IOException(e);}},memory,message->{},4000,relay::equals);
        AtomicReference<Throwable> result=new AtomicReference<>();Thread worker=new Thread(()->{try{routes.select(HOST,Collections.emptySet());}catch(Throwable e){result.set(e);}});
        worker.start();assertTrue(entered.await(2,TimeUnit.SECONDS));worker.interrupt();worker.join(2000);
        assertFalse(worker.isAlive());assertTrue(result.get() instanceof java.io.InterruptedIOException);assertTrue(memory.values.isEmpty());
    }

    @Test public void failedDiagnosticObserverCannotBreakRouteSelection()throws Exception {
        DirectRoutes.Trace trace=new DirectRoutes.Trace(){public void event(DirectRoutes.Phase phase,long operation,String host,DirectRoutes.Route route,long ms,Throwable error){throw new IllegalStateException("logging unavailable");}};
        DirectRoutes routes=new DirectRoutes(host->Collections.singletonList("8.8.8.8"),route->{},new Memory(),message->{},1000,DirectRoutes::automaticAddress,trace);
        assertEquals("8.8.8.8",routes.select(HOST,Collections.emptySet()).ip);
    }
    static final String HOST = "www.googleapis.com";
    static class Memory implements DirectRoutes.Store {
        final Map<String,DirectRoutes.Route> values = new ConcurrentHashMap<>();
        public DirectRoutes.Route load(String host) { return values.get(host); }
        public void save(String host,DirectRoutes.Route route) { values.put(host,route); }
        public void remove(String host) { values.remove(host); }
        public void clear() { values.clear(); }
    }
    @Test public void knownThirdPartyRelayCannotReturnThroughDnsHistoryOrCachedRoute()throws Exception {
        String relay="183.56.143.147";
        assertTrue(DirectRoutes.publicIpv4(relay));assertFalse(DirectRoutes.automaticAddress(relay));
        assertTrue(DirectRoutes.candidates(Collections.singletonList(relay),Collections.singletonList(relay),Collections.singletonList(relay)).isEmpty());
        Memory memory=new Memory();memory.save(HOST,new DirectRoutes.Route(HOST,relay,DirectRoutes.TlsMode.NO_SNI,System.currentTimeMillis()));
        AtomicInteger probes=new AtomicInteger();
        DirectRoutes routes=new DirectRoutes(host->Arrays.asList(relay,"8.8.8.8"),route->{
            assertNotEquals(relay,route.ip);probes.incrementAndGet();
        },memory,message->{});
        assertEquals("8.8.8.8",routes.select(HOST,Collections.emptySet()).ip);assertTrue(probes.get()>0);
        assertNotEquals(relay,memory.load(HOST).ip);
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
            if (route.tls != DirectRoutes.TlsMode.NO_SNI) throw new IOException("blocked");
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

    @Test public void pollutedSystemDnsCannotStarveEncryptedAnswersOrHistoricalHints() {
        java.util.List<String> system=new java.util.ArrayList<>(),encrypted=new java.util.ArrayList<>(),history=new java.util.ArrayList<>();
        for(int i=1;i<=20;i++){system.add("9.9.9."+i);encrypted.add("8.8.4."+i);history.add("8.8.8."+i);}
        java.util.List<String> all=DirectRoutes.candidates(system,encrypted,history);
        assertEquals(16,all.size());assertTrue(all.contains(system.get(0)));assertTrue(all.contains(encrypted.get(0)));assertTrue(all.contains(history.get(0)));
        DirectRoutes routes=new DirectRoutes(host->DirectRoutes.candidates(system,encrypted,Collections.emptyList()),route->{
            if(!route.ip.equals(encrypted.get(0)))throw new IOException("polluted fixture");
        },new Memory(),message->{});
        try{assertEquals(encrypted.get(0),routes.select(HOST,Collections.emptySet()).ip);}catch(IOException e){throw new AssertionError(e);}
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
