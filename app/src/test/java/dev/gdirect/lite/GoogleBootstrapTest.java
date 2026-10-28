package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class GoogleBootstrapTest {
    private String resource()throws Exception {
        try(java.io.InputStream in=getClass().getResourceAsStream("/google-bootstrap.json")) {
            assertNotNull(in);return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Test public void packagedCandidatesHaveReportProvenanceAndStayInsideTheSmallSubset()throws Exception {
        GoogleBootstrap hints=GoogleBootstrap.bundled();assertEquals("2026-10-26",hints.reportDate);
        List<String> ips=hints.candidates(hints.subset);assertEquals(6,ips.size());assertEquals("192.179.50.100",ips.get(0));
        for(String ip:ips)assertTrue(hints.subset.contains(ip));
        assertFalse(hints.subset.contains("183.56.143.147"));assertFalse(hints.subset.contains("192.179.62.100"));
        assertTrue(hints.description().contains("不保证当前可用"));
    }
    @Test public void freshOfficialCloudExclusionsOverrideBundledHints()throws Exception {
        GoogleBootstrap hints=GoogleBootstrap.bundled();
        GoogleAddressSpace live=GoogleAddressSpace.parse(GoogleAddressSpaceTest.json("192.179.50.0/24","192.179.60.0/24","192.179.61.0/24"),GoogleAddressSpaceTest.json("192.179.50.0/24"));
        List<String> result=hints.candidates(live);assertEquals(4,result.size());assertFalse(result.contains("192.179.50.100"));assertFalse(result.contains("192.179.50.132"));
    }
    @Test public void coldOfflineCatalogUsesOnlyBundledSubsetAndLaterOfficialRecoveryReplacesIt()throws Exception {
        GoogleBootstrap hints=GoogleBootstrap.bundled();AtomicInteger calls=new AtomicInteger();
        GoogleRangeCatalog catalog=new GoogleRangeCatalog(new GoogleRangeCatalogTest.Memory(),name->{
            if(calls.get()==0)throw new IOException("offline fixture");
            return name.equals("goog.json")?GoogleRangeCatalogTest.complete(0,64):GoogleRangeCatalogTest.complete(0,32);
        });
        GoogleBootstrap.Scope cold=hints.scope(catalog);assertTrue(cold.bundled);assertTrue(cold.space.contains("192.179.60.100"));
        assertFalse(cold.space.contains("142.250.40.1"));calls.incrementAndGet();catalog.retryRefresh();
        GoogleBootstrap.Scope live=hints.scope(catalog);assertFalse(live.bundled);assertTrue(live.space.contains("142.250.40.1"));
        assertFalse(live.space.contains("192.179.60.100"));assertTrue(hints.candidates(live.space).isEmpty());
    }
    @Test public void cancellationCannotTurnIntoAnOfflineFallback()throws Exception {
        GoogleBootstrap hints=GoogleBootstrap.bundled();GoogleRangeCatalog catalog=new GoogleRangeCatalog(new GoogleRangeCatalogTest.Memory(),name->{throw new AssertionError("cancelled fetch ran");});
        Thread.currentThread().interrupt();
        try{assertThrows(java.io.InterruptedIOException.class,()->hints.scope(catalog));}finally{Thread.interrupted();}
    }
    @Test public void malformedPrivateBroadOrDuplicateBlocksAreRejected()throws Exception {
        for(List<String> blocks:Arrays.asList(Collections.<String>emptyList(),Collections.singletonList("10.0.0.0/24"),Collections.singletonList("192.179.60.0/16"),Collections.singletonList("192.179.60.1/24"),Arrays.asList("192.179.60.0/24","192.179.60.0/24")))
            assertThrows(IOException.class,()->GoogleAddressSpace.fromBootstrapPrefixes(blocks));
        for(String ip:new String[]{"183.56.143.147","10.0.0.1","192.179.62.100"}) {
            JSONObject data=new JSONObject(resource()).put("candidates",new JSONArray().put(ip));
            assertThrows(IOException.class,()->GoogleBootstrap.parse(data.toString()));
        }
        JSONObject duplicate=new JSONObject(resource()).put("candidates",new JSONArray().put("192.179.60.100").put("192.179.60.100"));
        assertThrows(IOException.class,()->GoogleBootstrap.parse(duplicate.toString()));
    }
    @Test public void builtinAddressesNeverBecomeRoutesUntilProbeSucceeds()throws Exception {
        GoogleBootstrap hints=GoogleBootstrap.bundled();DirectRoutesTest.Memory memory=new DirectRoutesTest.Memory();AtomicInteger probes=new AtomicInteger();
        DirectRoutes blocked=new DirectRoutes(host->hints.candidates(hints.subset),route->{probes.incrementAndGet();throw new IOException("unavailable fixture");},memory,text->{});
        assertThrows(IOException.class,()->blocked.select("www.googleapis.com",Collections.emptySet()));assertTrue(probes.get()>0);assertTrue(memory.values.isEmpty());
        DirectRoutes available=new DirectRoutes(host->hints.candidates(hints.subset),route->{
            if(!route.ip.equals("192.179.60.100")||route.tls!=DirectRoutes.TlsMode.GOOGLE_FRONT)throw new IOException("unavailable fixture");
        },memory,text->{});
        DirectRoutes.Route selected=available.select("www.googleapis.com",Collections.emptySet());
        assertEquals("192.179.60.100",selected.ip);assertEquals(DirectRoutes.TlsMode.GOOGLE_FRONT,selected.tls);
    }
}
