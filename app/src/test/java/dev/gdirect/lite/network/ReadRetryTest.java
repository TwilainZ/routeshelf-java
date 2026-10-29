package dev.gdirect.lite.network;

import dev.gdirect.lite.drive.DriveSession;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ReadRetryTest {
    @Test public void transientReadRestartsWithBoundedBackoff() throws Exception {
        AtomicInteger attempts=new AtomicInteger(); AtomicLong waited=new AtomicLong();
        assertEquals("ok",ReadRetry.run(() -> {
            if(attempts.incrementAndGet()<3) throw new java.net.SocketTimeoutException(); return "ok";
        },()->{},waited::addAndGet));
        assertEquals(3,attempts.get()); assertEquals(1500,waited.get());
        attempts.set(0);
        assertThrows(IOException.class,()->ReadRetry.run(()->{attempts.incrementAndGet();throw new java.net.SocketException();},()->{},ignored->{}));
        assertEquals(3,attempts.get());
    }
    @Test public void securityFailuresAndLongServerDelaysAreNotRetried() {
        for(IOException error:new IOException[]{new javax.net.ssl.SSLHandshakeException("fixture"),new DriveSession.AuthorizationRequired(),new IOException("disk"),new ReadRetry.Unavailable(429,"60")}) {
            AtomicInteger attempts=new AtomicInteger();
            assertSame(error,assertThrows(IOException.class,()->ReadRetry.run(()->{attempts.incrementAndGet();throw error;},()->{},ignored->fail())));
            assertEquals(1,attempts.get());
        }
    }
    @Test public void retryAfterAndCancellationAreHonored() throws Exception {
        assertEquals(2000,ReadRetry.delay("2",0));
        assertEquals(2000,ReadRetry.delay("Thu, 01 Jan 1970 00:00:02 GMT",0));
        assertEquals(-1,ReadRetry.delay("invalid",0));
        assertEquals(Long.MAX_VALUE,ReadRetry.delay("999999999999999999999999",0));
        AtomicInteger attempts=new AtomicInteger(),checks=new AtomicInteger();
        assertThrows(DriveSession.AuthorizationRequired.class,()->ReadRetry.run(()->{attempts.incrementAndGet();throw new ReadRetry.Unavailable(503,"1");},()->{if(checks.incrementAndGet()>2)throw new DriveSession.AuthorizationRequired();},ignored->{}));
        assertEquals(1,attempts.get());
    }
}
