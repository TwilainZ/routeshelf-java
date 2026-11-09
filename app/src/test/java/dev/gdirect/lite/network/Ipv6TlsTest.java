package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

/** Actual IPv6 loopback sockets and ephemeral fixture roots; never a public-IP probe. */
public final class Ipv6TlsTest {
    @Test public void allTlsModesUseIpv6WithoutChangingLogicalAuthorityOrCredentials()throws Exception {
        for(DirectRoutes.TlsMode mode:DirectRoutes.TlsMode.values())try(GoogleHttpTest.Fixture fixture=
            new GoogleHttpTest.Fixture(mode==DirectRoutes.TlsMode.GOOGLE_FRONT?"g.cn":GoogleHttpTest.HOST,InetAddress.getByName("::1"))) {
            fixture.server.enqueue(new MockResponse().setBody("IPv6 fixture body"));
            okhttp3.OkHttpClient client=fixture.client(mode,true);
            javax.net.ssl.HttpsURLConnection connection=GoogleHttp.open(fixture.url(),()->client,()->{});
            connection.setRequestProperty("Authorization","Bearer IPv6_LOOPBACK_ONLY");
            try {
                assertEquals(200,connection.getResponseCode());
                assertEquals("IPv6 fixture body",new String(connection.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                RecordedRequest request=fixture.server.takeRequest(2,TimeUnit.SECONDS);
                assertEquals(GoogleHttpTest.HOST+":"+fixture.server.getPort(),request.getHeader("Host"));
                assertEquals("Bearer IPv6_LOOPBACK_ONLY",request.getHeader("Authorization"));
                assertEquals(mode==DirectRoutes.TlsMode.NO_SNI,fixture.sni.get(2,TimeUnit.SECONDS).isEmpty());
                if(mode==DirectRoutes.TlsMode.GOOGLE_FRONT)assertEquals("g.cn",new String(fixture.sni.get().get(0).getEncoded(),java.nio.charset.StandardCharsets.US_ASCII));
                if(mode==DirectRoutes.TlsMode.NO_SNI)assertEquals(InetAddress.getByName("::1"),InetAddress.getByName(fixture.tlsPeer.get()));
            }finally{connection.disconnect();}
        }
    }
    @Test public void wrongCertificateOnIpv6CannotReceiveAnHttpAuthorization()throws Exception {
        try(GoogleHttpTest.Fixture fixture=new GoogleHttpTest.Fixture("wrong.invalid",InetAddress.getByName("::1"))) {
            okhttp3.OkHttpClient client=fixture.client(DirectRoutes.TlsMode.NORMAL,true);
            javax.net.ssl.HttpsURLConnection connection=GoogleHttp.open(fixture.url(),()->client,()->{});
            connection.setRequestProperty("Authorization","Bearer NEVER_SENT");
            try{assertThrows(java.io.IOException.class,connection::getResponseCode);assertEquals(0,fixture.server.getRequestCount());}
            finally{connection.disconnect();}
        }
    }
}
