package dev.gdirect.lite.drive;

import dev.gdirect.lite.network.ConnectionHealth;

/** UI-thread browser recovery. Rendering and repeated ticks must never create a refresh loop. */
public final class BrowseReconnect {
    public enum Action { NONE, SESSION, NETWORK, RETRY, CHECK }
    // DirectRoutes suppresses failed discovery for ten seconds. Wait beyond that window.
    public static final long RETRY_DELAY_MILLIS=11000;
    private long session=-1,path=-1,checkFinished,retryAt;
    private boolean failed,retryable;
    private int retries;

    public void reset(){session=-1;path=-1;checkFinished=0;retryAt=0;failed=false;retryable=false;retries=0;}
    public void started(long session,long path,long checkFinished,boolean automatic){
        if(!automatic||this.session!=session||this.path!=path)retries=0;else retries++;
        this.session=session;this.path=path;this.checkFinished=checkFinished;
        failed=false;retryable=false;retryAt=0;
    }
    public void succeeded(){failed=false;retryable=false;retryAt=0;}
    public void failed(boolean transientNetworkFailure,long now){
        failed=true;retryable=transientNetworkFailure;retryAt=now+RETRY_DELAY_MILLIS;
    }
    public boolean retryPending(){return failed&&retryable&&retries==0;}
    public static boolean transientFailure(Throwable error,ConnectionHealth.Result api){
        boolean network=false,stopped=false;
        for(int i=0;i<8&&error!=null;i++,error=error.getCause()){
            if(error instanceof DriveSession.AuthorizationRequired||error instanceof javax.net.ssl.SSLException
                ||error instanceof java.security.cert.CertificateException)stopped=true;
            if(error instanceof java.net.SocketException||error instanceof java.net.SocketTimeoutException
                ||error instanceof java.net.UnknownHostException)network=true;
            if(error instanceof java.io.InterruptedIOException&&!(error instanceof java.net.SocketTimeoutException))stopped=true;
        }
        return !stopped&&(network||(api.state==ConnectionHealth.State.FAILED
            &&(api.problem==ConnectionHealth.Problem.NETWORK||api.problem==ConnectionHealth.Problem.TIMEOUT)));
    }
    public Action next(long session,long path,boolean online,boolean busy,boolean checking,
            long verifiedCheckFinished,long now){
        if(!online||busy||checking)return Action.NONE;
        if(this.session!=session)return Action.SESSION;
        if(this.path!=path)return Action.NETWORK;
        if(!failed||!retryable)return Action.NONE;
        // A user check may repair an exhausted attempt, once per completed check.
        if(verifiedCheckFinished>checkFinished)return Action.CHECK;
        return retries==0&&now>=retryAt?Action.RETRY:Action.NONE;
    }
}
