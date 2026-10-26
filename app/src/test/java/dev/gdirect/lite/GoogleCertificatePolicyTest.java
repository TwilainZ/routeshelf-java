package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.net.InetAddress;
import java.util.Collections;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;

public final class GoogleCertificatePolicyTest {
    @Test public void otherwiseTrustedEmSignChainIsRejectedBeforeAuthorizationEvenOnReusedTls()throws Exception {
        HeldCertificate root=new HeldCertificate.Builder().commonName("Fixture root").certificateAuthority(1).build();
        HeldCertificate intermediate=new HeldCertificate.Builder().commonName("emSign TLS CA fixture").certificateAuthority(0).signedBy(root).build();
        HeldCertificate leaf=new HeldCertificate.Builder().commonName("www.googleapis.com").addSubjectAlternativeName("www.googleapis.com").signedBy(intermediate).build();
        HandshakeCertificates keys=new HandshakeCertificates.Builder().heldCertificate(leaf,intermediate.certificate(),root.certificate()).build();
        HandshakeCertificates trust=new HandshakeCertificates.Builder().addTrustedCertificate(root.certificate()).build();
        try(MockWebServer server=new MockWebServer()) {
            server.useHttps(keys.sslSocketFactory(),false);server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));server.start(InetAddress.getByName("127.0.0.1"),0);
            OkHttpClient client=GoogleHttp.direct(new OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager()).retryOnConnectionFailure(false).build(),
                new DirectRoutes.Route("www.googleapis.com","127.0.0.1",DirectRoutes.TlsMode.NO_SNI,0));
            HttpUrl url=server.url("/").newBuilder().host("www.googleapis.com").build();
            server.enqueue(new MockResponse().setBody("fixture"));
            try(Response response=client.newCall(new Request.Builder().url(url).build()).execute()){assertEquals("fixture",response.body().string());}
            assertEquals(1,server.getRequestCount());
            OkHttpClient protectedClient=client.newBuilder().addNetworkInterceptor(GoogleCertificatePolicy.CHECK).build();
            assertThrows(javax.net.ssl.SSLPeerUnverifiedException.class,()->protectedClient.newCall(new Request.Builder().url(url).header("Authorization","Bearer fixture-only").build()).execute());
            assertEquals(1,server.getRequestCount());client.connectionPool().evictAll();
        }
    }
    @Test public void officialCatalogRequestHasNoCredentialsAndCannotFollowRedirects()throws Exception {
        try(GoogleHttpTest.Fixture f=new GoogleHttpTest.Fixture("www.gstatic.com")) {
            OkHttpClient client=GoogleHttp.direct(new OkHttpClient.Builder().sslSocketFactory(f.clientTrust.sslSocketFactory(),f.clientTrust.trustManager()).build(),
                new DirectRoutes.Route("www.gstatic.com","127.0.0.1",DirectRoutes.TlsMode.NO_SNI,0)).newBuilder()
                .addInterceptor(chain->chain.proceed(chain.request().newBuilder().url(chain.request().url().newBuilder().port(f.server.getPort()).build()).build()))
                .addNetworkInterceptor(GoogleCertificatePolicy.CHECK).build();
            f.server.enqueue(new MockResponse().setBody("catalog fixture"));assertEquals("catalog fixture",GoogleRangeCatalog.download(client,"goog.json"));
            RecordedRequest request=f.server.takeRequest();assertEquals("/ipranges/goog.json",request.getPath());assertNull(request.getHeader("Authorization"));
            f.server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","https://other.example/"));
            assertThrows(java.io.IOException.class,()->GoogleRangeCatalog.download(client,"cloud.json"));assertEquals(2,f.server.getRequestCount());
        }
    }
}
