package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ConnectionCheckTest {
    private static ConnectionCheck.Snapshot terminal(ConnectionCheck check)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(check.snapshot().active()&&System.nanoTime()<end)Thread.sleep(1);
        assertFalse("Worker must finish",check.snapshot().active());return check.snapshot();
    }
    @Test public void repeatedStartSharesOneWorkerAndPreservesResult()throws Exception {
        ConnectionCheck check=new ConnectionCheck();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        assertTrue(check.start(()->{calls.incrementAndGet();entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){throw new IOException(e);}return "API and content checked";}));
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        try {assertFalse(check.start(()->{calls.incrementAndGet();return "duplicate";}));assertTrue(check.snapshot().active());}
        finally{release.countDown();}
        assertEquals(ConnectionCheck.State.SUCCEEDED,terminal(check).state);assertEquals(1,calls.get());
        assertEquals("API and content checked",check.snapshot().detail);
    }
    @Test public void failureDoesNotClaimConnectivityOrExposeException()throws Exception {
        ConnectionCheck check=new ConnectionCheck();check.start(()->{throw new IOException("Bearer secret-from-library");});
        ConnectionCheck.Snapshot result=terminal(check);assertEquals(ConnectionCheck.State.FAILED,result.state);
        assertFalse(result.detail.contains("secret"));assertFalse(result.detail.isEmpty());
    }
    @Test public void cancellationHoldsSingleFlightUntilWorkerActuallyStops()throws Exception {
        ConnectionCheck check=new ConnectionCheck();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        check.start(()->{entered.countDown();boolean done=false;while(!done)try{done=release.await(2,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return "late success";});
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        try {check.cancel();assertEquals(ConnectionCheck.State.CANCELLING,check.snapshot().state);
            assertFalse(check.start(()->"must not start"));check.clearResult();assertTrue(check.snapshot().active());}
        finally{release.countDown();}
        assertEquals(ConnectionCheck.State.CANCELLED,terminal(check).state);assertFalse(check.snapshot().detail.contains("late success"));
    }
    @Test public void cancelledCheckCanBeRetried()throws Exception {
        ConnectionCheck check=new ConnectionCheck();CountDownLatch entered=new CountDownLatch(1);
        check.start(()->{entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}return "unexpected";});
        assertTrue(entered.await(2,TimeUnit.SECONDS));check.cancel();assertEquals(ConnectionCheck.State.CANCELLED,terminal(check).state);
        assertTrue(check.start(()->"recovered"));assertEquals(ConnectionCheck.State.SUCCEEDED,terminal(check).state);assertEquals("recovered",check.snapshot().detail);
    }
    @Test public void modeChangeClearsFinishedResult()throws Exception {
        ConnectionCheck check=new ConnectionCheck();check.start(()->"old network");terminal(check);check.clearResult();
        assertEquals(ConnectionCheck.State.IDLE,check.snapshot().state);assertEquals("",check.snapshot().detail);
    }
    @Test public void credentialFreeServiceReportIsRetainedForTroubleshooting()throws Exception {
        ConnectionCheck check=new ConnectionCheck();String report="www.googleapis.com: no verified route; content: checked";
        check.start(()->{throw new ConnectionCheck.CheckFailure(report);});
        assertEquals(ConnectionCheck.State.FAILED,terminal(check).state);assertEquals(report,check.snapshot().detail);
    }
    @Test public void unexpectedFailureReleasesWorkerForRetry()throws Exception {
        ConnectionCheck check=new ConnectionCheck();check.start(()->{throw new IllegalStateException("internal details");});
        assertEquals(ConnectionCheck.State.FAILED,terminal(check).state);
        assertTrue(check.start(()->"ok"));assertEquals(ConnectionCheck.State.SUCCEEDED,terminal(check).state);
    }
}
