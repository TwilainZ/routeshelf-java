package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;

/** Real TLS over loopback with ephemeral fixture certificates; production trust is never disabled. */
public final class GoogleHttpTest {
    private static final String HOST = "www.googleapis.com";
    static final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final CompletableFuture<List<SNIServerName>> sni = new CompletableFuture<>();
        final HandshakeCertificates clientTrust;
        Fixture(String certificateHost) throws Exception {
            HeldCertificate ca = new HeldCertificate.Builder().certificateAuthority(0).commonName("Unit test CA").build();
            HeldCertificate leaf = new HeldCertificate.Builder().commonName(certificateHost)
                .addSubjectAlternativeName(certificateHost).signedBy(ca).build();
            HandshakeCertificates serverKeys = new HandshakeCertificates.Builder()
                .heldCertificate(leaf,ca.certificate()).build();
            clientTrust = new HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate()).build();
            server.useHttps(new ObserveSni(serverKeys.sslSocketFactory(),sni),false);
            server.setProtocols(Collections.singletonList(Protocol.HTTP_1_1));
            server.start(InetAddress.getByName("127.0.0.1"),0);
        }
        URL url() throws Exception { return new URL("https://" + HOST + ":" + server.getPort() + "/drive/v3/files?alt=media"); }
        OkHttpClient client(DirectRoutes.TlsMode mode, boolean trusted) throws Exception {
            OkHttpClient.Builder builder = new OkHttpClient.Builder().protocols(Collections.singletonList(Protocol.HTTP_1_1));
            if (trusted) builder.sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager());
            return GoogleHttp.direct(builder.build(),new DirectRoutes.Route(HOST,"127.0.0.1",mode,0));
        }
        @Override public void close() throws Exception { server.shutdown(); }
    }

    @Test public void directDnsPreservesRealHostAuthorizationAndCertificateIdentityWithAndWithoutSni() throws Exception {
        for (DirectRoutes.TlsMode mode : DirectRoutes.TlsMode.values()) try (Fixture f = new Fixture(HOST)) {
            f.server.enqueue(new MockResponse().setBody("private fixture"));
            OkHttpClient client = f.client(mode,true);
            HttpsURLConnection connection = GoogleHttp.open(f.url(), () -> client, () -> { });
            connection.setRequestProperty("Authorization","Bearer test-fixture-only");
            assertEquals(200,connection.getResponseCode());
            assertEquals("private fixture",new String(connection.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            RecordedRequest request = f.server.takeRequest(2,TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(HOST + ":" + f.server.getPort(),request.getHeader("Host"));
            assertEquals("Bearer test-fixture-only",request.getHeader("Authorization"));
            assertEquals(HOST, connection.getURL().getHost());
            assertEquals(mode == DirectRoutes.TlsMode.NO_SNI, f.sni.get(2,TimeUnit.SECONDS).isEmpty());
            connection.disconnect();
        }
    }

    @Test public void wrongHostnameNeverReceivesAuthorizationEvenWhenSniIsOmitted() throws Exception {
        for (DirectRoutes.TlsMode mode : DirectRoutes.TlsMode.values()) try (Fixture f = new Fixture("other.example")) {
            f.server.enqueue(new MockResponse().setBody("must not be read"));
            OkHttpClient client = f.client(mode,true);
            assertThrows(IOException.class, () -> client.newCall(new Request.Builder().url(f.url())
                .header("Authorization","Bearer test-fixture-only").build()).execute());
            assertEquals(0,f.server.getRequestCount());
        }
    }

    @Test public void untrustedChainIsRejectedInBothModes() throws Exception {
        for (DirectRoutes.TlsMode mode : DirectRoutes.TlsMode.values()) try (Fixture f = new Fixture(HOST)) {
            OkHttpClient client = f.client(mode,false);
            assertThrows(IOException.class, () -> client.newCall(new Request.Builder().url(f.url())
                .header("Authorization","Bearer test-fixture-only").build()).execute());
            assertEquals(0,f.server.getRequestCount());
        }
    }

    @Test public void adapterPreservesRangeHeadersAndDoesNotFollowAuthenticatedRedirects() throws Exception {
        try (Fixture f = new Fixture(HOST)) {
            f.server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 0-3/9")
                .setHeader("ETag","\"fixture\"").setBody("abcd"));
            OkHttpClient client = f.client(DirectRoutes.TlsMode.NORMAL,true);
            HttpsURLConnection c = GoogleHttp.open(f.url(),() -> client,() -> { });
            c.setRequestProperty("Range","bytes=0-3"); c.setRequestProperty("If-Range","\"fixture\"");
            assertEquals(206,c.getResponseCode());
            assertEquals(4,c.getContentLengthLong());
            assertEquals("bytes 0-3/9",c.getHeaderField("Content-Range"));
            assertEquals("bytes=0-3",f.server.takeRequest().getHeader("Range"));
            c.disconnect();
            f.server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","https://example.net/steal"));
            c = GoogleHttp.open(f.url(),() -> client,() -> { });
            c.setRequestProperty("Authorization","Bearer test-fixture-only");
            assertEquals(302,c.getResponseCode()); c.disconnect();
            assertEquals(2,f.server.getRequestCount());
        }
    }

    @Test public void connectionFailureSelectsAnotherRouteBeforeReplayingGet() throws Exception {
        try (Fixture f = new Fixture(HOST)) {
            f.server.enqueue(new MockResponse().setBody("ok"));
            OkHttpClient untrusted = f.client(DirectRoutes.TlsMode.NORMAL,false);
            OkHttpClient trusted = f.client(DirectRoutes.TlsMode.NO_SNI,true);
            AtomicInteger selections = new AtomicInteger(), failures = new AtomicInteger();
            HttpsURLConnection c = GoogleHttp.open(f.url(),() -> selections.getAndIncrement() == 0 ? untrusted : trusted,
                failures::incrementAndGet);
            assertEquals(200,c.getResponseCode()); c.disconnect();
            assertEquals(1,failures.get()); assertEquals(2,selections.get());
            assertEquals(1,f.server.getRequestCount());
        }
    }

    @Test public void disconnectDuringDiscoveryPreventsSubsequentTokenTransmission() throws Exception {
        try (Fixture f = new Fixture(HOST)) {
            DriveSession session = new DriveSession(); session.connect("test-fixture-only");
            DriveSession.Lease lease = session.lease();
            OkHttpClient client = f.client(DirectRoutes.TlsMode.NORMAL,true);
            HttpsURLConnection c = GoogleHttp.open(f.url(),() -> { session.invalidate(lease); return client; },
                () -> { }, lease::requireCurrent);
            c.setRequestProperty("Authorization","Bearer test-fixture-only");
            assertThrows(DriveSession.AuthorizationRequired.class,c::getResponseCode);
            assertEquals(0,f.server.getRequestCount()); c.disconnect();
        }
    }

    @Test public void midstreamFailureInvalidatesRouteWithoutReplayingPartialContent() throws Exception {
        try (Fixture f = new Fixture(HOST)) {
            f.server.enqueue(new MockResponse().setBody("abcdefgh".repeat(1024))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            OkHttpClient client = f.client(DirectRoutes.TlsMode.NORMAL,true);
            AtomicInteger failures = new AtomicInteger();
            HttpsURLConnection c = GoogleHttp.open(f.url(),() -> client,failures::incrementAndGet);
            assertEquals(200,c.getResponseCode());
            assertThrows(IOException.class,() -> c.getInputStream().readAllBytes());
            assertEquals(1,failures.get()); assertEquals(1,f.server.getRequestCount()); c.disconnect();
        }
    }

    private static final class ObserveSni extends SSLSocketFactory {
        final SSLSocketFactory delegate;
        final CompletableFuture<List<SNIServerName>> names;
        ObserveSni(SSLSocketFactory delegate, CompletableFuture<List<SNIServerName>> names) { this.delegate=delegate; this.names=names; }
        Socket observe(Socket socket) {
            ((SSLSocket)socket).addHandshakeCompletedListener(event -> names.complete(((ExtendedSSLSession)event.getSession()).getRequestedServerNames()));
            return socket;
        }
        public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        public Socket createSocket(Socket s,String h,int p,boolean close) throws IOException { return observe(delegate.createSocket(s,h,p,close)); }
        public Socket createSocket(String h,int p) throws IOException { return observe(delegate.createSocket(h,p)); }
        public Socket createSocket(String h,int p,InetAddress l,int lp) throws IOException { return observe(delegate.createSocket(h,p,l,lp)); }
        public Socket createSocket(InetAddress h,int p) throws IOException { return observe(delegate.createSocket(h,p)); }
        public Socket createSocket(InetAddress h,int p,InetAddress l,int lp) throws IOException { return observe(delegate.createSocket(h,p,l,lp)); }
    }
}
