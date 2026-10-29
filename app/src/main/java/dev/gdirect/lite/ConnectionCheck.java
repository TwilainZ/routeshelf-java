package dev.gdirect.lite;

import java.io.IOException;

/** Application-owned, single-flight user check; screens observe, never own the worker. */
final class ConnectionCheck {
    enum State { IDLE, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
    interface Work { String run() throws IOException; }
    static final class Snapshot {
        final State state;final String detail;
        Snapshot(State state,String detail){this.state=state;this.detail=detail;}
        boolean active(){return state==State.RUNNING||state==State.CANCELLING;}
    }
    private volatile Snapshot snapshot=new Snapshot(State.IDLE,"");
    private Thread worker;
    Snapshot snapshot(){return snapshot;}
    synchronized boolean start(Work work) {
        if(worker!=null)return false;
        snapshot=new Snapshot(State.RUNNING,"");
        worker=new Thread(()->{
            State outcome=State.SUCCEEDED;String detail="";
            try {detail=work.run();}
            catch(IOException|RuntimeException failure){outcome=State.FAILED;detail="未能完成连接检测。请检查网络后重试。";}
            synchronized(this) {
                if(snapshot.state==State.CANCELLING||Thread.currentThread().isInterrupted()) {
                    outcome=State.CANCELLED;detail="已取消检测；下次浏览或传输时仍会自动连接。";
                }
                snapshot=new Snapshot(outcome,detail);worker=null;
            }
        },"gdirect-connection-check");
        worker.start();return true;
    }
    synchronized void clearResult() {if(worker==null)snapshot=new Snapshot(State.IDLE,"");}
    synchronized void cancel() {
        if(worker!=null){snapshot=new Snapshot(State.CANCELLING,"正在取消检测…");worker.interrupt();}
    }
}
