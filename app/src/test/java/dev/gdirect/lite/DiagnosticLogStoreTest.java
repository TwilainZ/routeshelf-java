package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public final class DiagnosticLogStoreTest {
    private static void until(java.util.function.BooleanSupplier condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);assertTrue(condition.getAsBoolean());}
    static class Memory implements DiagnosticLogStore.Store {
        volatile String value="";final AtomicInteger writes=new AtomicInteger();
        public String read()throws IOException{return value;}
        public void write(String text)throws IOException{value=text;writes.incrementAndGet();}
        public void delete(){value="";}
    }
    @Test public void recordsSurviveStoreReload()throws Exception {
        Memory memory=new Memory();DiagnosticLog first=new DiagnosticLog();
        try(DiagnosticLogStore store=new DiagnosticLogStore(first,memory)){first.event(DiagnosticLog.Event.PREFERRED_FALLBACK);until(()->memory.writes.get()>0);assertTrue(store.available());}
        DiagnosticLog reloaded=new DiagnosticLog();try(DiagnosticLogStore store=new DiagnosticLogStore(reloaded,memory)){until(()->reloaded.size()==1);assertTrue(reloaded.report().contains("PREFERRED_FALLBACK"));}
    }
    @Test public void blockedDiskWriterDoesNotBlockRecordingOrCauseOneWritePerEvent()throws Exception {
        CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);Memory memory=new Memory(){public void write(String text)throws IOException{writing.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}super.write(text);}};
        DiagnosticLog log=new DiagnosticLog();try(DiagnosticLogStore store=new DiagnosticLogStore(log,memory)) {
            for(int i=0;i<200;i++)log.event(DiagnosticLog.Event.REQUEST_READ_BEGIN);assertTrue(writing.await(3,TimeUnit.SECONDS));
            CountDownLatch recorded=new CountDownLatch(1);Thread writer=new Thread(()->{log.event(DiagnosticLog.Event.REQUEST_RESPONSE);recorded.countDown();});writer.start();
            try{assertTrue(recorded.await(1,TimeUnit.SECONDS));assertEquals(201,log.size());assertEquals(0,memory.writes.get());}finally{release.countDown();writer.join(1000);}
            until(()->memory.writes.get()>0);assertTrue(memory.writes.get()<10);
        }finally{release.countDown();}
    }
    @Test public void persistenceFailureLeavesUsableMemoryLog()throws Exception {
        Memory memory=new Memory(){public void write(String text)throws IOException{throw new IOException("PRIVATE_CANARY_PATH");}};
        DiagnosticLog log=new DiagnosticLog();try(DiagnosticLogStore store=new DiagnosticLogStore(log,memory)) {
            log.event(DiagnosticLog.Event.APP_START);until(()->!store.available());log.event(DiagnosticLog.Event.LIST_BEGIN);
            assertEquals(2,log.size());assertFalse(log.report().contains("PRIVATE_CANARY"));
        }
    }
    @Test public void clearingDuringSlowInitialReadCannotRestoreDeletedHistory()throws Exception {
        DiagnosticLog old=new DiagnosticLog();old.event(DiagnosticLog.Event.AUTH_BEGIN);CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1),cleared=new CountDownLatch(1);
        Memory memory=new Memory(){public String read()throws IOException{String snapshot=value;reading.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}return snapshot;}};memory.value=old.encoded();
        DiagnosticLog log=new DiagnosticLog();try(DiagnosticLogStore store=new DiagnosticLogStore(log,memory)) {
            assertTrue(reading.await(2,TimeUnit.SECONDS));store.clear(cleared::countDown);release.countDown();assertTrue(cleared.await(2,TimeUnit.SECONDS));
            assertEquals(0,log.size());assertEquals("",memory.value);
        }finally{release.countDown();}
    }
}
