package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class GoogleRangeCatalogTest {
    @Test public void unavailableColdCatalogDoesNotDelayEveryRelayRequestAndResetCanRetry()throws Exception {
        AtomicInteger attempts=new AtomicInteger();GoogleRangeCatalog catalog=new GoogleRangeCatalog(new Memory(),name->{attempts.incrementAndGet();throw new IOException("offline");});
        assertThrows(IOException.class,catalog::load);assertThrows(IOException.class,catalog::load);assertEquals(1,attempts.get());
        catalog.retryRefresh();assertThrows(IOException.class,catalog::load);assertEquals(2,attempts.get());
    }
    static String complete(int start,int count) {
        String[] prefixes=new String[count];for(int i=0;i<count;i++)prefixes[i]="142.250."+(start+i)+".0/24";
        return GoogleAddressSpaceTest.json(prefixes);
    }
    static final class Memory implements GoogleRangeCatalog.Store {String saved="";public String read(){return saved;}public void write(String value){saved=value;}}
    @Test public void onlyCompleteOfficialPairIsStoredAndReusedAcrossInstances()throws Exception {
        Memory memory=new Memory();AtomicInteger calls=new AtomicInteger();
        GoogleRangeCatalog catalog=new GoogleRangeCatalog(memory,name->{calls.incrementAndGet();return name.equals("goog.json")?complete(0,64):complete(0,32);});
        assertTrue(catalog.load().contains("142.250.40.1"));assertFalse(catalog.load().contains("142.250.0.1"));assertEquals(2,calls.get());
        assertTrue(new GoogleRangeCatalog(memory,name->{throw new IOException("offline");}).load().contains("142.250.40.1"));
    }
    @Test public void failedUpdateRetainsBoundedLastGoodCatalogWithoutRetryingEveryRequest()throws Exception {
        Memory memory=new Memory();GoogleRangeCatalog good=new GoogleRangeCatalog(memory,name->name.equals("goog.json")?complete(0,64):complete(0,32));good.load();
        org.json.JSONObject saved=new org.json.JSONObject(memory.saved);saved.put("checked",System.currentTimeMillis()-java.util.concurrent.TimeUnit.DAYS.toMillis(8));memory.saved=saved.toString();
        String old=memory.saved;AtomicInteger attempts=new AtomicInteger();GoogleRangeCatalog offline=new GoogleRangeCatalog(memory,name->{attempts.incrementAndGet();throw new IOException("offline");});
        assertTrue(offline.load().contains("142.250.40.1"));offline.load();assertEquals(1,attempts.get());assertEquals(old,memory.saved);
        offline.retryRefresh();offline.load();assertEquals(2,attempts.get());
        saved.put("checked",System.currentTimeMillis()-java.util.concurrent.TimeUnit.DAYS.toMillis(31));memory.saved=saved.toString();
        GoogleRangeCatalog expired=new GoogleRangeCatalog(memory,name->{throw new IOException("offline");});assertThrows(IOException.class,expired::load);
    }

    @Test public void resettingFromTheUiDoesNotWaitForAnInFlightMetadataDownload()throws Exception {
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),resetDone=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        GoogleRangeCatalog catalog=new GoogleRangeCatalog(new Memory(),name->{
            if(name.equals("goog.json")){started.countDown();try{if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fixture timeout");}catch(InterruptedException e){throw new IOException(e);}}
            return name.equals("goog.json")?complete(0,64):complete(0,32);
        });
        try {
            java.util.concurrent.Future<?> loading=workers.submit(()->{try{catalog.load();}catch(IOException e){throw new RuntimeException(e);}});
            assertTrue(started.await(1,java.util.concurrent.TimeUnit.SECONDS));
            workers.submit(()->{catalog.retryRefresh();resetDone.countDown();});
            assertTrue("UI reset waited for network I/O",resetDone.await(1,java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();loading.get(2,java.util.concurrent.TimeUnit.SECONDS);
        }finally{release.countDown();workers.shutdownNow();}
    }

    @Test public void failedOrTinyColdCatalogDoesNotOpenTheNetworkToArbitraryAddresses()throws Exception {
        Memory memory=new Memory();GoogleRangeCatalog catalog=new GoogleRangeCatalog(memory,name->GoogleAddressSpaceTest.json("8.8.8.0/24"));
        assertThrows(IOException.class,catalog::load);assertEquals("",memory.saved);
        GoogleRangeCatalog blocked=new GoogleRangeCatalog(memory,name->{throw new IOException("blocked");});assertThrows(IOException.class,blocked::load);
    }
}
