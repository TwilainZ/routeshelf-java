package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;

public final class GoogleDnsDiscoveryTest {
    static final String NAME="www.googleapis.com";
    static String answer(String records) {
        return "{\"Status\":0,\"Question\":[{\"name\":\"www.googleapis.com.\",\"type\":1}],\"Answer\":["+records+"]}";
    }
    static String record(String name,int type,String data) {
        return "{\"name\":\""+name+"\",\"type\":"+type+",\"TTL\":300,\"data\":\""+data+"\"}";
    }
    @Test public void followsOnlyMatchingAliasAndFiltersPrivateFakeAndUnrelatedAddresses()throws Exception {
        String records=record(NAME,5,"alias.google.com.")+","+record("alias.google.com",1,"142.250.1.1")+","
            +record("alias.google.com",1,"10.0.0.1")+","+record("alias.google.com",1,"198.18.1.2")+","
            +record("other.example",1,"8.8.8.8")+","+record("alias.google.com",1,"142.250.1.1");
        assertEquals(Collections.singletonList("142.250.1.1"),GoogleDnsDiscovery.parse(NAME,answer(records)));
        assertThrows(IOException.class,()->GoogleDnsDiscovery.parse("drive.google.com",answer(records)));
        assertThrows(IOException.class,()->GoogleDnsDiscovery.parse(NAME,answer(record(NAME,5,NAME))));
        assertThrows(IOException.class,()->GoogleDnsDiscovery.parse(NAME,answer(record(NAME,5,"a.google.com")+","+record(NAME,5,"b.google.com"))));
        assertThrows(IOException.class,()->GoogleDnsDiscovery.parse(NAME,"x".repeat(32769)));
        assertTrue(GoogleDnsDiscovery.parse(NAME,"{\"Status\":3}").isEmpty());
    }
    @Test public void unavailableEncryptedDnsLeavesExistingDiscoverySourcesUsable()throws Exception {
        GoogleDnsDiscovery unavailable=new GoogleDnsDiscovery((route,request)->{throw new IOException("fixture DNS blocked");});
        try(GoogleDnsDiscovery.Query query=unavailable.start(NAME)) {
            assertTrue(query.finished());assertTrue(query.addresses().isEmpty());
            DirectRoutes routes=new DirectRoutes(host->DirectRoutes.candidates(Collections.emptyList(),query.addresses(),Collections.singletonList("142.250.1.1")),
                route->{},new DirectRoutesTest.Memory(),message->{});
            assertEquals("142.250.1.1",routes.select(NAME,Collections.emptySet()).ip);
        }
    }

    static GoogleDnsDiscovery local(GoogleHttpTest.Fixture f) {
        return new GoogleDnsDiscovery((route,request)->{
            assertEquals("dns.google",route.host);assertTrue(Arrays.asList("8.8.8.8","8.8.4.4").contains(route.ip));
            assertEquals("https://dns.google/resolve?name=www.googleapis.com&type=A&edns_client_subnet=0.0.0.0%2F0",request.url().toString());
            assertNull(request.header("Authorization"));
            OkHttpClient trusted=new OkHttpClient.Builder().sslSocketFactory(f.clientTrust.sslSocketFactory(),f.clientTrust.trustManager()).build();
            OkHttpClient client=GoogleHttp.direct(trusted,new DirectRoutes.Route("dns.google","127.0.0.1",route.tls,0))
                .newBuilder().callTimeout(5,TimeUnit.SECONDS).build();
            return client.newCall(request.newBuilder().url(request.url().newBuilder().port(f.server.getPort()).build()).build());
        });
    }
    static void await(GoogleDnsDiscovery.Query query)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!query.finished()&&System.nanoTime()<end)Thread.sleep(10);
        assertTrue("discovery did not finish",query.finished());
    }
    @Test public void coldDiscoveryUsesVerifiedHttpsWithoutSystemDnsOrCredentials()throws Exception {
        try(GoogleHttpTest.Fixture f=new GoogleHttpTest.Fixture("dns.google")) {
            for(int i=0;i<4;i++)f.server.enqueue(new MockResponse().setHeader("Content-Type","application/dns-json").setBody(answer(record(NAME,1,"142.250.1.1"))));
            try(GoogleDnsDiscovery.Query query=local(f).start(NAME)) {
                await(query);assertEquals(Collections.singletonList("142.250.1.1"),query.addresses());
                for(int i=0;i<4;i++) {
                    RecordedRequest request=f.server.takeRequest(1,TimeUnit.SECONDS);
                    assertNotNull(request);assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"));
                    assertEquals("dns.google:"+f.server.getPort(),request.getHeader("Host"));
                }
            }
        }
    }
    @Test public void wrongCertificateCannotSupplyAnyBootstrapCandidates()throws Exception {
        try(GoogleHttpTest.Fixture f=new GoogleHttpTest.Fixture("other.example")) {
            try(GoogleDnsDiscovery.Query query=local(f).start(NAME)) {await(query);assertTrue(query.addresses().isEmpty());}
            assertEquals(0,f.server.getRequestCount());
        }
    }
    @Test public void redirectsAndOversizedRepliesAreNotAccepted()throws Exception {
        try(GoogleHttpTest.Fixture f=new GoogleHttpTest.Fixture("dns.google")) {
            for(int i=0;i<2;i++)f.server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","https://other.example/resolve"));
            for(int i=0;i<2;i++)f.server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("x".repeat(32769)));
            try(GoogleDnsDiscovery.Query query=local(f).start(NAME)){await(query);assertTrue(query.addresses().isEmpty());}
            assertEquals(4,f.server.getRequestCount());
        }
    }
    @Test public void cancelledDiscoveryClosesOutstandingCallsAndRejectsNonGoogleNames()throws Exception {
        try(GoogleHttpTest.Fixture f=new GoogleHttpTest.Fixture("dns.google")) {
            GoogleDnsDiscovery discovery=local(f);
            assertThrows(IOException.class,()->discovery.start("other.example"));
            for(int i=0;i<4;i++)f.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            GoogleDnsDiscovery.Query query=discovery.start(NAME);
            assertNotNull(f.server.takeRequest(2,TimeUnit.SECONDS));query.close();await(query);
            assertTrue(query.addresses().isEmpty());
            for(okhttp3.Call call:query.calls)assertTrue(call.isCanceled());
        }
    }
}
