package dev.gdirect.lite.drive;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.gdirect.lite.drive.BrowseReconnect.Action.*;

public final class BrowseReconnectTest {
    private final BrowseReconnect recovery=new BrowseReconnect();
    private BrowseReconnect.Action next(long now){return recovery.next(1,10,true,false,false,0,now);}
    @Test public void restoredSessionLoadsWithoutAConnectionScreenVisit(){
        assertEquals(SESSION,next(0));recovery.started(1,10,0,true);
        assertEquals(NONE,next(1));recovery.succeeded();assertEquals(NONE,next(2));
    }
    @Test public void healthyListNeverRefreshesOnRepeatedRenderingOrVerificationAge(){
        recovery.started(1,10,0,false);recovery.succeeded();
        for(int i=0;i<1000;i++)assertEquals(NONE,next(i*60000L));
        assertEquals(NONE,recovery.next(1,10,true,false,false,99,1000000));
    }
    @Test public void transientFailureGetsOneRetryAfterDiscoveryCooldown(){
        recovery.started(1,10,0,false);recovery.failed(true,100);
        assertTrue(recovery.retryPending());assertEquals(NONE,next(11099));assertEquals(RETRY,next(11100));
        recovery.started(1,10,0,true);recovery.failed(true,11200);
        assertFalse(recovery.retryPending());assertEquals(NONE,next(999999));
    }
    @Test public void permissionProtocolAndCertificateFailuresWaitForUserAction(){
        recovery.started(1,10,0,false);recovery.failed(false,0);
        assertFalse(recovery.retryPending());assertEquals(NONE,next(999999));
        assertEquals(NONE,recovery.next(1,10,true,false,false,99,999999));
    }
    @Test public void newNetworkAutomaticallyReloadsOnceWithoutResettingRoutes(){
        recovery.started(1,10,0,false);recovery.succeeded();
        assertEquals(NETWORK,recovery.next(1,11,true,false,false,0,0));
        recovery.started(1,11,0,true);recovery.succeeded();
        for(int i=0;i<100;i++)assertEquals(NONE,recovery.next(1,11,true,false,false,0,i));
    }
    @Test public void networkChangeWaitsForInflightListAndEditsToFinish(){
        recovery.started(1,10,0,false);
        assertEquals(NONE,recovery.next(1,11,true,true,false,0,0));
        recovery.failed(false,0);assertEquals(NETWORK,recovery.next(1,11,true,false,false,0,1));
    }
    @Test public void offlineAndActiveChecksNeverConsumeAPendingRetry(){
        recovery.started(1,10,0,false);recovery.failed(true,0);
        assertEquals(NONE,recovery.next(1,10,false,false,false,0,12000));
        assertEquals(NONE,recovery.next(1,10,true,false,true,0,12000));
        assertEquals(RETRY,next(12000));
    }
    @Test public void manualCheckSuccessImmediatelyRetriesAnEarlierFailedList(){
        recovery.started(1,10,0,false);recovery.failed(true,0);
        assertEquals(CHECK,recovery.next(1,10,true,false,false,20,1));
        recovery.started(1,10,20,true);recovery.failed(true,2);
        assertEquals(NONE,recovery.next(1,10,true,false,false,20,99999));
        assertEquals(CHECK,recovery.next(1,10,true,false,false,30,99999));
    }
    @Test public void checkBeforeTheListAttemptDoesNotTriggerAnotherAttempt(){
        recovery.started(1,10,20,false);recovery.failed(true,0);
        assertEquals(NONE,recovery.next(1,10,true,false,false,20,1));
        assertEquals(NONE,recovery.next(1,10,true,false,false,0,1));
    }
    @Test public void explicitRefreshCanStartANewBoundedRecoveryCycle(){
        recovery.started(1,10,0,false);recovery.failed(true,0);
        recovery.started(1,10,0,true);recovery.failed(true,12000);assertEquals(NONE,next(30000));
        recovery.started(1,10,0,false);recovery.failed(true,30000);assertEquals(RETRY,next(41000));
    }
    @Test public void networkAndAccountChangesReplaceAnExhaustedCycle(){
        recovery.started(1,10,0,false);recovery.started(1,10,0,true);recovery.failed(true,0);
        assertEquals(SESSION,recovery.next(2,10,true,false,false,0,0));
        recovery.started(2,10,0,true);recovery.failed(true,0);
        assertEquals(RETRY,recovery.next(2,10,true,false,false,0,11000));
        recovery.started(2,11,0,true);recovery.failed(true,0);
        assertEquals(RETRY,recovery.next(2,11,true,false,false,0,11000));
    }
    @Test public void disconnectClearsOldAttemptsAndCompletedChecks(){
        recovery.started(1,10,20,false);recovery.failed(true,0);recovery.reset();
        assertFalse(recovery.retryPending());assertEquals(SESSION,next(12000));
    }
    @Test public void aNetworkErrorRemainsRetryableIfAConcurrentProbeAlreadySucceeded(){
        dev.gdirect.lite.network.ConnectionHealth health=new dev.gdirect.lite.network.ConnectionHealth();health.online(true);
        health.verified(health.begin(dev.gdirect.lite.network.ConnectionHealth.Service.API,health.generation()),
            dev.gdirect.lite.network.ConnectionHealth.Evidence.PROBE);
        assertTrue(BrowseReconnect.transientFailure(new java.net.SocketTimeoutException(),health.snapshot().api));
        assertTrue(BrowseReconnect.transientFailure(new java.io.IOException(new java.net.SocketException()),health.snapshot().api));
        assertFalse(BrowseReconnect.transientFailure(new java.io.IOException("Invalid response"),health.snapshot().api));
    }
    @Test public void cancellationAuthorizationAndCertificateErrorsNeverAutoRetry(){
        dev.gdirect.lite.network.ConnectionHealth health=new dev.gdirect.lite.network.ConnectionHealth();health.online(true);
        health.failed(health.begin(dev.gdirect.lite.network.ConnectionHealth.Service.API,health.generation()),
            dev.gdirect.lite.network.ConnectionHealth.Problem.NETWORK);
        for(Throwable error:new Throwable[]{new java.io.InterruptedIOException(),new DriveSession.AuthorizationRequired(),
            new javax.net.ssl.SSLHandshakeException("certificate"),new java.io.IOException(new java.security.cert.CertificateException())})
            assertFalse(BrowseReconnect.transientFailure(error,health.snapshot().api));
    }
    @Test public void failedDiscoveryCanRetryButHttpRejectionsAndProtocolErrorsCannot(){
        dev.gdirect.lite.network.ConnectionHealth health=new dev.gdirect.lite.network.ConnectionHealth();health.online(true);
        health.failed(health.begin(dev.gdirect.lite.network.ConnectionHealth.Service.API,health.generation()),
            dev.gdirect.lite.network.ConnectionHealth.Problem.NETWORK);
        assertTrue(BrowseReconnect.transientFailure(new java.io.IOException(),health.snapshot().api));
        for(int code:new int[]{200,401,403,404,429,500}){
            health.response(health.begin(dev.gdirect.lite.network.ConnectionHealth.Service.API,health.generation()),code);
            assertFalse(BrowseReconnect.transientFailure(new java.io.IOException(),health.snapshot().api));
        }
    }
}
