package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import okhttp3.Request;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;

public final class GoogleTrustTest {
    @Test public void packagedTrustStoreContainsOnlyFourSelfSignedGoogleRoots()throws Exception {
        X509Certificate[] roots=GoogleTrust.manager().getAcceptedIssuers();assertEquals(4,roots.length);
        for(X509Certificate cert:roots){assertTrue(cert.getSubjectX500Principal().getName().contains("Google Trust Services"));cert.verify(cert.getPublicKey());}
    }
    @Test public void pretendingToBeGoogleDoesNotGrantTrustOrReceiveCredentials()throws Exception {
        HeldCertificate root=new HeldCertificate.Builder().certificateAuthority(0).commonName("GTS Root R1").organizationalUnit("Google Trust Services LLC").build();
        HeldCertificate leaf=new HeldCertificate.Builder().commonName("g.cn").addSubjectAlternativeName("g.cn").signedBy(root).build();
        try(MockWebServer server=new MockWebServer()) {
            server.useHttps(new HandshakeCertificates.Builder().heldCertificate(leaf,root.certificate()).build().sslSocketFactory(),false);server.start();
            okhttp3.OkHttpClient client=GoogleHttp.direct(GoogleTrust.clientBuilder().callTimeout(2,TimeUnit.SECONDS).build(),
                new DirectRoutes.Route("www.googleapis.com","127.0.0.1",DirectRoutes.TlsMode.GOOGLE_FRONT,0));
            assertThrows(java.io.IOException.class,()->client.newCall(new Request.Builder().url("https://www.googleapis.com:"+server.getPort()+"/drive/v3/files")
                .header("Authorization","Bearer fixture-only").build()).execute());
            assertEquals(0,server.getRequestCount());
        }
    }
}
