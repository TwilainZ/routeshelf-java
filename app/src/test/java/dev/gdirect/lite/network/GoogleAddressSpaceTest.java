package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import org.junit.Test;

public final class GoogleAddressSpaceTest {
    @Test public void discoveryUsesObservedNeighborhoodsAndAcceptsSlowerTcpWithoutLeavingTheOfficialSpace()throws Exception {
        GoogleAddressSpace space=GoogleAddressSpace.parse(json("142.250.0.0/16"),json("142.250.1.0/24"));
        List<String> candidates=space.discoveryCandidates(Arrays.asList("142.250.2.100","183.56.143.147","142.250.1.100"),512,new Random(17));
        assertEquals("142.250.2.100",candidates.get(0));assertEquals(512,candidates.size());for(String ip:candidates)assertTrue(space.contains(ip));
        assertEquals(Collections.singletonList("142.250.2.100"),GoogleFrontendScan.reachable(Collections.singletonList("142.250.2.100"),space,ip->{
            try{Thread.sleep(350);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }));
    }
    static String json(String... prefixes) {
        StringBuilder result=new StringBuilder("{\"creationTime\":\"2026-10-26T00:00:00Z\",\"syncToken\":\"1\",\"prefixes\":[");
        for(String prefix:prefixes){if(result.charAt(result.length()-1)!='[')result.append(',');result.append("{\"ipv4Prefix\":\"").append(prefix).append("\"}");}
        return result+"]}";
    }
    @Test public void cloudRangesAreSubtractedIncludingOverlapsAndBoundaries()throws Exception {
        GoogleAddressSpace space=GoogleAddressSpace.parse(json("142.250.0.0/24","142.250.1.0/24"),json("142.250.0.64/26","142.250.1.0/24"));
        assertTrue(space.contains("142.250.0.63"));assertFalse(space.contains("142.250.0.64"));assertFalse(space.contains("142.250.0.127"));assertTrue(space.contains("142.250.0.128"));
        assertFalse(space.contains("142.250.1.1"));assertFalse(space.contains("183.56.143.147"));assertFalse(space.contains("10.0.0.1"));
        for(String ip:space.sample(48,new Random(1)))assertTrue(space.contains(ip));
    }
    @Test public void incompleteMalformedOrEmptyFrontendsAreRejected()throws Exception {
        assertThrows(IOException.class,()->GoogleAddressSpace.parse("{}",json("8.8.4.0/24")));
        assertThrows(IOException.class,()->GoogleAddressSpace.parse(json("8.8.8.1/24"),json("8.8.4.0/24")));
        assertThrows(IOException.class,()->GoogleAddressSpace.parse(json("0.0.0.0/0"),json("8.8.4.0/24")));
        assertThrows(IOException.class,()->GoogleAddressSpace.parse(json("8.8.8.0/24"),json("8.8.8.0/24")));
    }
    @Test public void scanConnectsOnlyToPublishedNonCloudCandidatesAndCancellationIsBounded()throws Exception {
        GoogleAddressSpace space=GoogleAddressSpace.parse(json("142.250.0.0/24"),json("142.250.0.128/25"));
        List<String> found=GoogleFrontendScan.reachable(Arrays.asList("183.56.143.147","142.250.0.200","142.250.0.1","142.250.0.2"),space,ip->{assertTrue(space.contains(ip));if(ip.endsWith(".2"))throw new IOException("blocked fixture");});
        assertEquals(Collections.singletonList("142.250.0.1"),found);
        Thread.currentThread().interrupt();try{assertThrows(java.io.InterruptedIOException.class,()->GoogleFrontendScan.reachable(Collections.singletonList("142.250.0.1"),space,ip->{}));}finally{Thread.interrupted();}
    }
}
