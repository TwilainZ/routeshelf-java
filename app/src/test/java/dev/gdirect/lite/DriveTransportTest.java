package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Test;

public final class DriveTransportTest {
    static final class Fake extends HttpsURLConnection {
        final int code;
        boolean closed;
        byte[] body = "{\"files\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String retryAfter;
        Fake(URL url, int code) { super(url); this.code = code; }
        @Override public int getResponseCode() { return code; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public String getHeaderField(String name) { return "Retry-After".equals(name) ? retryAfter : ("Location".equals(name) ? "https://example.net/steal" : null); }
        @Override public void disconnect() { closed = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "TEST-ONLY"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }

    @Test public void nonApiDestinationsAreRejectedBeforeOpening() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        AtomicInteger opened = new AtomicInteger();
        DriveTransport transport = new DriveTransport(session.lease(), url -> { opened.incrementAndGet(); return new Fake(url,200); }, () -> {});
        for (String bad : new String[]{"http://www.googleapis.com/drive/v3/files", "https://www.googleapis.com.example.net/drive/v3/files", "https://drive.google.com/drive/v3/files", "https://8.8.8.8/drive/v3/files", "https://user@www.googleapis.com/drive/v3/files", "https://www.googleapis.com:444/drive/v3/files", "https://www.googleapis.com/other", "https://www.googleapis.com/drive/v3/files#fragment"})
            assertThrows(IOException.class, () -> transport.open(new URL(bad)));
        assertEquals(0, opened.get());
    }

    @Test public void redirectsNeverReceiveASecondAuthenticatedRequest() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        for (int code : new int[]{301,302,303,307,308}) {
            Fake c = new Fake(url,code);
            AtomicInteger opened = new AtomicInteger();
            DriveTransport transport = new DriveTransport(session.lease(), target -> {opened.incrementAndGet(); return c;}, () -> {});
            assertThrows(IOException.class, () -> transport.open(url));
            assertEquals(1, opened.get());
            assertFalse(c.getInstanceFollowRedirects());
            assertTrue(c.closed);
        }
    }

    @Test public void unauthorizedInvalidatesSessionWithoutLeakingResponse() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        DriveSession.Lease lease = session.lease();
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        Fake c = new Fake(url,401);
        DriveTransport transport = new DriveTransport(lease, ignored -> c, () -> session.invalidate(lease));
        IOException error = assertThrows(DriveSession.AuthorizationRequired.class, () -> transport.open(url));
        assertFalse(error.getMessage().contains("unit-test-token"));
        assertFalse(session.connected());
        assertTrue(c.closed);
    }

    @Test public void validApiReceivesHeaderAndBoundedJson() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        Fake c = new Fake(url,200);
        DriveTransport transport = new DriveTransport(session.lease(), ignored -> c, () -> {});
        assertEquals("{\"files\":[]}", transport.json(url));
        assertEquals("Bearer unit-test-token", c.getRequestProperty("Authorization"));
        assertTrue(c.closed);
        c.body = new byte[1024*1024+1];
        assertThrows(IOException.class, () -> transport.json(url));
    }

    @Test public void throttledReadRetriesWithoutFollowingRedirectsOrChangingCredentials() throws Exception {
        DriveSession session = new DriveSession(); session.connect("unit-test-token");
        URL url = new URL("https://www.googleapis.com/drive/v3/files");
        AtomicInteger calls = new AtomicInteger();
        java.util.List<Fake> connections = new java.util.ArrayList<>();
        DriveTransport transport = new DriveTransport(session.lease(), target -> {
            Fake c = new Fake(target,calls.incrementAndGet() < 3 ? 429 : 200);
            c.retryAfter="0"; connections.add(c); return c;
        }, () -> fail("must not invalidate a valid session"));
        assertEquals("{\"files\":[]}",transport.json(url));
        assertEquals(3,calls.get());
        for(Fake c:connections) {
            assertEquals("Bearer unit-test-token",c.getRequestProperty("Authorization"));
            assertFalse(c.getInstanceFollowRedirects()); assertTrue(c.closed);
        }
    }
}
