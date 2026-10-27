package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.net.URL;
import java.io.IOException;
import java.util.*;
import org.junit.Test;

public final class DriveMediaRedirectTest {
    @Test public void apiMediaRedirectStripsAllCredentialsButKeepsRangeAndVersion()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture");session.lease().rememberKey("file","key");
        List<DriveTransportTest.Fake> seen=new ArrayList<>();
        DriveTransport transport=new DriveTransport(session.lease(),url->{
            DriveTransportTest.Fake c=new DriveTransportTest.Fake(url,seen.isEmpty()?302:206);
            c.uploadLocation="https://content-fixture.googleusercontent.com/signed-content";seen.add(c);return c;
        },()->fail("content redirect cannot invalidate Google authorization"));
        javax.net.ssl.HttpsURLConnection result=transport.openRange(new URL("https://www.googleapis.com/drive/v3/files/file?alt=media"),"bytes=0-3","\"v1\"");
        assertEquals(2,seen.size());assertTrue(seen.get(0).closed);assertEquals("Bearer fixture",seen.get(0).getRequestProperty("Authorization"));
        assertNotNull(seen.get(0).getRequestProperty("X-Goog-Drive-Resource-Keys"));
        assertNull(result.getRequestProperty("Authorization"));assertNull(result.getRequestProperty("X-Goog-Drive-Resource-Keys"));
        assertNull(result.getRequestProperty("Cookie"));assertNull(result.getRequestProperty("Referer"));
        assertEquals("bytes=0-3",result.getRequestProperty("Range"));assertEquals("\"v1\"",result.getRequestProperty("If-Range"));result.disconnect();
    }
    @Test public void redirectCannotEscapeGoogleContentOrLoopForever()throws Exception {
        for(String bad:new String[]{"https://googleusercontent.com.evil.example/content","http://x.googleusercontent.com/content","https://user@x.googleusercontent.com/content","https://x.googleusercontent.com:444/content","https://www.googleapis.com/drive/v3/files","https://x.googleusercontent.com/content#fragment"})
            assertThrows(IOException.class,()->DriveTransport.requireContentUrl(new URL(bad)));
        DriveSession session=new DriveSession();session.connect("fixture");List<DriveTransportTest.Fake> seen=new ArrayList<>();
        DriveTransport transport=new DriveTransport(session.lease(),url->{DriveTransportTest.Fake c=new DriveTransportTest.Fake(url,302);c.uploadLocation="https://x.googleusercontent.com/again";seen.add(c);return c;},()->{});
        assertThrows(IOException.class,()->transport.open(new URL("https://www.googleapis.com/drive/v3/files/file?alt=media")));
        assertEquals(5,seen.size());for(DriveTransportTest.Fake c:seen)assertTrue(c.closed);
    }
}
