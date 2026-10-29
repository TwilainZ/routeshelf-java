package dev.gdirect.lite.network;

import java.io.IOException;

/** Application-owned, single-flight user check; screens observe, never own the worker. */
public final class ConnectionCheck {
    public enum State { IDLE, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
    public interface Work { String run() throws IOException; }
    /** Only discovery's credential-free, canonical-host report may opt into visible detail. */
    public static final class CheckFailure extends IOException {
        public final String report;
        public CheckFailure(String report){super("Connection check failed");this.report=report.substring(0,Math.min(report.length(),2048));}
    }
    public static final class Snapshot {
        public final State state;public final String detail;
        public Snapshot(State state,String detail){this.state=state;this.detail=detail;}
        public boolean active(){return state==State.RUNNING||state==State.CANCELLING;}
    }
    private volatile Snapshot snapshot=new Snapshot(State.IDLE,"");
    private Thread worker;
    public Snapshot snapshot(){return snapshot;}
    public synchronized boolean start(Work work) {
        if(worker!=null)return false;
        snapshot=new Snapshot(State.RUNNING,"");
        worker=new Thread(()->{
            State outcome=State.SUCCEEDED;String detail="";
            try {detail=work.run();}
            catch(IOException|RuntimeException failure){outcome=State.FAILED;
                detail=failure instanceof CheckFailure?((CheckFailure)failure).report:"未能完成连接检测。请检查网络后重试。";
            }
            synchronized(this) {
                if(snapshot.state==State.CANCELLING||Thread.currentThread().isInterrupted()) {
                    outcome=State.CANCELLED;detail="已取消检测；下次浏览或传输时仍会自动连接。";
                }
                snapshot=new Snapshot(outcome,detail);worker=null;
            }
        },"gdirect-connection-check");
        worker.start();return true;
    }
    public synchronized void clearResult() {if(worker==null)snapshot=new Snapshot(State.IDLE,"");}
    public synchronized void cancel() {
        if(worker!=null){snapshot=new Snapshot(State.CANCELLING,"正在取消检测…");worker.interrupt();}
    }
}
