package dev.gdirect.lite;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesced private-file I/O on one worker, never on the request path or UI thread. */
final class DiagnosticLogStore implements AutoCloseable {
    interface Store {String read()throws IOException;void write(String text)throws IOException;void delete();}
    private final DiagnosticLog log;
    private final Store store;
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"gdirect-log-store");t.setDaemon(true);return t;});
    private final AtomicBoolean pending=new AtomicBoolean();
    private volatile boolean available=true;
    DiagnosticLogStore(DiagnosticLog log,Store store) {
        this.log=log;this.store=store;
        long clear=log.clearVersion();
        log.onChanged(this::schedule);
        io.execute(()->{try{log.restore(store.read(),clear);}catch(IOException|RuntimeException ignored){/* Missing/corrupt old logs do not prevent new recording. */}finally{schedule();}});
    }
    private void schedule() {
        if(io.isShutdown()||!pending.compareAndSet(false,true))return;
        io.schedule(()->{pending.set(false);persist();},1,TimeUnit.SECONDS);
    }
    private void persist(){try{store.write(log.encoded());available=true;}catch(IOException|RuntimeException error){available=false;}}
    boolean available(){return available;}
    void clear(Runnable completed) {
        log.clear();io.execute(()->{try{store.delete();persist();}catch(RuntimeException error){available=false;}finally{completed.run();}});
    }
    @Override public void close(){log.onChanged(()->{});io.shutdownNow();}
}
