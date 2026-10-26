package dev.gdirect.lite;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Metadata writes belong to the app process, not to a screen which may rotate or close. */
final class MutationCoordinator implements AutoCloseable {
    static final MutationCoordinator INSTANCE=new MutationCoordinator();
    interface Work {void run()throws IOException;}
    interface Completion {void done(String error);}
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicLong revision=new AtomicLong();
    private volatile String error;
    private volatile long owner=-1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"drive-metadata");t.setDaemon(true);return t;});
    boolean busy(){return running.get();}
    long revision(){return revision.get();}
    String error(){return error;}
    long owner(){return owner;}
    boolean submit(Work work,Completion completion) {return submit(-1,work,completion);}
    boolean submit(long session,Work work,Completion completion) {
        if(!running.compareAndSet(false,true))return false;
        error=null;
        try {
            worker.execute(()->{
                String result=null;
                try{work.run();}catch(IOException e){result=e.getMessage();if(result==null)result="文件操作未确认，请刷新列表核对";}
                catch(RuntimeException e){result="文件操作未确认，请刷新列表核对";}
                finally{owner=session;error=result;revision.incrementAndGet();running.set(false);}
                completion.done(result);
            });
        }catch(RuntimeException e){running.set(false);throw e;}
        return true;
    }
    @Override public void close(){worker.shutdownNow();}
}
