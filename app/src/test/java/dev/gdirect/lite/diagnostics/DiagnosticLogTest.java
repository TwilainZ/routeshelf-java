package dev.gdirect.lite.diagnostics;

import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.network.DirectRoutes;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;

public final class DiagnosticLogTest {
    @Test public void untrustedHostIpAndExceptionTextCannotLeakIntoLogs() {
        DiagnosticLog log=new DiagnosticLog();String secret="CANARY_ACCOUNT_TOKEN_FILE_RESOURCEKEY_8264";
        IOException error=new IOException(secret){@Override public String getMessage(){throw new AssertionError("Exception messages must not be read");}};
        log.record(DiagnosticLog.Event.REQUEST_FAILED,log.operation(),secret+".googleusercontent.com",secret,DirectRoutes.TlsMode.GOOGLE_FRONT,14,403,error);
        log.record(DiagnosticLog.Event.PROBE_FAILED,1,"https://example.test/"+secret,"192.168.1.2",null,10,0,error);
        assertFalse(log.encoded().contains(secret));assertFalse(log.report().contains(secret));assertFalse(log.report().contains("192.168"));
        assertTrue(log.report().contains("DRIVE_CONTENT"));assertTrue(log.report().contains("code=403"));assertTrue(log.report().contains("error=IO"));
    }
    @Test public void restoredRecordsAreReconstructedRatherThanExportedVerbatim()throws Exception {
        DiagnosticLog old=new DiagnosticLog();old.event(DiagnosticLog.Event.APP_START);JSONObject record=new JSONObject(old.encoded());
        String secret="RESTORE_CANARY_PRIVATE_URL_COOKIE";record.put("unexpected",secret).put("ip",secret);
        DiagnosticLog log=new DiagnosticLog();log.restore(record+"\n"+new JSONObject(record.toString()).put("event",secret)+"\n",0);
        assertEquals(1,log.size());assertFalse(log.encoded().contains(secret));assertFalse(log.report().contains(secret));
    }
    @Test public void memoryAndSerializedSizeAreBounded()throws Exception {
        DiagnosticLog log=new DiagnosticLog();for(int i=0;i<800;i++)log.record(DiagnosticLog.Event.PROBE_FAILED,i,"www.googleapis.com","192.179.60.100",DirectRoutes.TlsMode.GOOGLE_FRONT,3,i,null);
        assertTrue(log.size()<=DiagnosticLog.MAX_ENTRIES);assertTrue(log.encoded().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=DiagnosticLog.MAX_BYTES);
        String[] entries=log.encoded().split("\n");assertEquals(799,new JSONObject(entries[entries.length-1]).getInt("code"));assertTrue(new JSONObject(entries[0]).getInt("code")>0);
    }
    @Test public void concurrentRequestsDoNotCorruptSnapshot()throws Exception {
        DiagnosticLog log=new DiagnosticLog();ExecutorService pool=Executors.newFixedThreadPool(8);
        try{java.util.List<Future<?>> work=new java.util.ArrayList<>();for(int n=0;n<8;n++)work.add(pool.submit(()->{for(int i=0;i<100;i++)log.record(DiagnosticLog.Event.REQUEST_RESPONSE,log.operation(),"www.googleapis.com",null,null,2,200,null);}));for(Future<?> item:work)item.get(3,TimeUnit.SECONDS);}
        finally{pool.shutdownNow();}
        java.util.Set<Long> ids=new java.util.HashSet<>();for(String line:log.encoded().split("\n")){JSONObject item=new JSONObject(line);assertEquals(200,item.getInt("code"));assertTrue(ids.add(item.getLong("op")));}assertEquals(log.size(),ids.size());
    }
    @Test public void clearDuringRestoreCannotResurrectOldEntries() {
        DiagnosticLog old=new DiagnosticLog();old.event(DiagnosticLog.Event.AUTH_BEGIN);DiagnosticLog log=new DiagnosticLog();long epoch=log.clearVersion();
        log.clear();log.restore(old.encoded(),epoch);assertEquals(0,log.size());
    }
    @Test public void restoreKeepsCurrentSessionEventsAfterOldRecords() {
        DiagnosticLog old=new DiagnosticLog();old.event(DiagnosticLog.Event.NETWORK_LOST);DiagnosticLog current=new DiagnosticLog();current.event(DiagnosticLog.Event.APP_START);
        current.restore(old.encoded(),current.clearVersion());assertEquals(2,current.size());assertTrue(current.report().indexOf("NETWORK_LOST")<current.report().indexOf("APP_START"));
    }
    @Test public void errorCategoriesDoNotRequireProviderMessagesOrStackTraces() {
        assertEquals(DiagnosticLog.Failure.CERTIFICATE,DiagnosticLog.failure(new javax.net.ssl.SSLPeerUnverifiedException("secret")));
        assertEquals(DiagnosticLog.Failure.TLS,DiagnosticLog.failure(new javax.net.ssl.SSLHandshakeException("secret")));
        assertEquals(DiagnosticLog.Failure.TIMEOUT,DiagnosticLog.failure(new IOException(new java.net.SocketTimeoutException("secret"))));
        assertEquals(DiagnosticLog.Failure.DNS,DiagnosticLog.failure(new java.net.UnknownHostException("secret")));
        assertEquals(DiagnosticLog.Failure.NETWORK,DiagnosticLog.failure(new java.net.SocketException("secret")));
        assertEquals(DiagnosticLog.Failure.AUTHORIZATION,DiagnosticLog.failure(new DriveSession.AuthorizationRequired()));
    }
    @Test public void corruptOrOversizedOldFilesDoNotLoseLiveRecords() {
        DiagnosticLog log=new DiagnosticLog();log.event(DiagnosticLog.Event.APP_START);log.restore("x".repeat(DiagnosticLog.MAX_BYTES+1),0);
        assertEquals(1,log.size());log.restore("not-json\n{\"v\":2}\n",0);assertEquals(1,log.size());
    }
}
