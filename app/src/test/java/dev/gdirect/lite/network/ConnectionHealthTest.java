package dev.gdirect.lite.network;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ConnectionHealthTest {
    private static final class Fixture {
        final AtomicLong clock=new AtomicLong();final ConnectionHealth health=new ConnectionHealth(clock::get);
        Fixture(){health.online(true);}
        ConnectionHealth.Ticket api(){return health.begin(ConnectionHealth.Service.API,health.generation());}
        ConnectionHealth.Ticket download(){return health.begin(ConnectionHealth.Service.DOWNLOAD,health.generation());}
    }
    @Test public void noConnectionEvidenceExistsOnStartup(){Fixture f=new Fixture();assertEquals(ConnectionHealth.State.UNKNOWN,f.health.snapshot().api.state);assertEquals(ConnectionHealth.Evidence.NONE,f.health.snapshot().download.evidence);}
    @Test public void selectingACachedRouteDoesNotEstablishConnectivity(){Fixture f=new Fixture();ConnectionHealth.Ticket t=f.api();f.health.route(t,new DirectRoutes.Route("www.googleapis.com","192.179.50.100",DirectRoutes.TlsMode.NORMAL,0));assertEquals(ConnectionHealth.State.CHECKING,f.health.snapshot().api.state);assertEquals(ConnectionHealth.Evidence.NONE,f.health.snapshot().api.evidence);}
    @Test public void apiAndContentProbesRemainSeparateFromRealDownloads(){Fixture f=new Fixture();f.health.verified(f.api(),ConnectionHealth.Evidence.PROBE);f.health.verified(f.download(),ConnectionHealth.Evidence.PROBE);assertEquals(ConnectionHealth.State.READY,f.health.snapshot().api.state);assertEquals(ConnectionHealth.Evidence.PROBE,f.health.snapshot().download.evidence);}
    @Test public void http401ReportsAuthorizationRatherThanANetworkOutage(){Fixture f=new Fixture();f.health.response(f.api(),401);assertEquals(ConnectionHealth.State.RESPONSE_ERROR,f.health.snapshot().api.state);assertEquals(ConnectionHealth.Problem.AUTHORIZATION,f.health.snapshot().api.problem);}
    @Test public void http403And404KeepPermissionAndFileErrorsDistinct(){Fixture f=new Fixture();f.health.response(f.api(),403);assertEquals(ConnectionHealth.Problem.PERMISSION,f.health.snapshot().api.problem);f.health.response(f.download(),404);assertEquals(ConnectionHealth.Problem.NOT_FOUND,f.health.snapshot().download.problem);}
    @Test public void bodyFailureReplacesAnEarlierSuccessfulHeaderResponse(){Fixture f=new Fixture();ConnectionHealth.Ticket t=f.download();f.health.response(t,200);f.health.failed(t,ConnectionHealth.Problem.NETWORK);assertEquals(ConnectionHealth.State.FAILED,f.health.snapshot().download.state);assertEquals(ConnectionHealth.Evidence.NONE,f.health.snapshot().download.evidence);}
    @Test public void lateFailureFromAnOlderRequestCannotOverwriteANewerSuccess(){Fixture f=new Fixture();ConnectionHealth.Ticket old=f.api(),fresh=f.api();f.health.response(fresh,200);f.health.failed(old,ConnectionHealth.Problem.TIMEOUT);assertEquals(ConnectionHealth.State.READY,f.health.snapshot().api.state);}
    @Test public void pathOrPolicyResetDiscardsAllOldCallbacksAndRouteDetails(){Fixture f=new Fixture();ConnectionHealth.Ticket t=f.api();f.health.route(t,new DirectRoutes.Route("www.googleapis.com","192.179.50.100",DirectRoutes.TlsMode.NORMAL,0));f.health.reset();f.health.response(t,200);assertEquals(ConnectionHealth.State.UNKNOWN,f.health.snapshot().api.state);assertEquals("",f.health.snapshot().api.ip);}
    @Test public void disconnectAndReconnectNeverReviveOldSuccess(){Fixture f=new Fixture();ConnectionHealth.Ticket t=f.api();f.health.response(t,200);f.health.online(false);assertFalse(f.health.snapshot().online);f.health.online(true);f.health.response(t,200);assertEquals(ConnectionHealth.State.UNKNOWN,f.health.snapshot().api.state);}
    @Test public void evidenceExpiresAndANewRequestCanCheckAgain(){Fixture f=new Fixture();f.health.response(f.api(),200);f.clock.set(ConnectionHealth.FRESH_MILLIS);assertEquals(ConnectionHealth.State.STALE,f.health.snapshot().api.state);f.api();assertEquals(ConnectionHealth.State.CHECKING,f.health.snapshot().api.state);f.health.failed(f.api(),ConnectionHealth.Problem.NETWORK);f.clock.addAndGet(ConnectionHealth.FRESH_MILLIS);assertEquals(ConnectionHealth.State.STALE,f.health.snapshot().api.state);}
    @Test public void cancellationDoesNotPoisonAnAlreadyVerifiedService(){Fixture f=new Fixture();f.health.response(f.api(),200);f.health.cancelled(f.api());assertEquals(ConnectionHealth.State.READY,f.health.snapshot().api.state);}
    @Test public void cancelledInitialCheckReturnsToUnknown(){Fixture f=new Fixture();f.health.cancelled(f.api());assertEquals(ConnectionHealth.State.UNKNOWN,f.health.snapshot().api.state);}
    @Test public void anUnstartedOldEpochRequestCannotAffectTheNewPath(){Fixture f=new Fixture();long old=f.health.generation();f.health.reset();ConnectionHealth.Ticket t=f.health.begin(ConnectionHealth.Service.API,old);f.health.failed(t,ConnectionHealth.Problem.NETWORK);assertEquals(ConnectionHealth.State.UNKNOWN,f.health.snapshot().api.state);}
    @Test public void onlyACompletedTransferAddsDownloadEvidenceInTheCurrentGeneration(){Fixture f=new Fixture();long old=f.health.generation();f.health.downloaded(old);assertEquals(ConnectionHealth.Evidence.DOWNLOAD,f.health.snapshot().download.evidence);f.health.reset();f.health.downloaded(old);assertEquals(ConnectionHealth.State.UNKNOWN,f.health.snapshot().download.state);}
}
