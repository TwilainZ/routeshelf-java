package dev.gdirect.lite.transfer;

import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.network.ReadRetry;

import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public final class UploadTransferTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private File source(int size)throws Exception {
        File file=temporary.newFile();byte[] b=new byte[size];for(int i=0;i<size;i++)b[i]=(byte)(i%251);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(b);}return file;
    }
    @Test public void lostAcknowledgmentQueriesOffsetInsteadOfDuplicatingCommittedBytes()throws Exception {
        File file=source(UploadTransfer.CHUNK+7);ByteArrayOutputStream received=new ByteArrayOutputStream();AtomicInteger sends=new AtomicInteger();
        String result=UploadTransfer.run(file,new UploadTransfer.Remote(){
            public UploadTransfer.Reply put(long start,byte[] data,long total)throws IOException {
                assertEquals(received.size(),start);received.write(data);
                if(sends.incrementAndGet()==1)throw new java.net.SocketException("lost acknowledgment");
                return new UploadTransfer.Reply(total,"complete");
            }
            public UploadTransfer.Reply query(long total){return new UploadTransfer.Reply(received.size(),null);}
        },()->{},(n,t)->{});
        assertEquals("complete",result);assertEquals(2,sends.get());assertArrayEquals(java.nio.file.Files.readAllBytes(file.toPath()),received.toByteArray());
    }
    @Test public void partiallyCommittedChunkResumesFromConfirmedByte()throws Exception {
        File file=source(UploadTransfer.CHUNK);ByteArrayOutputStream received=new ByteArrayOutputStream();AtomicInteger sends=new AtomicInteger();
        UploadTransfer.run(file,new UploadTransfer.Remote(){
            public UploadTransfer.Reply put(long start,byte[] data,long total)throws IOException {
                assertEquals(received.size(),start);
                if(sends.incrementAndGet()==1){received.write(data,0,256*1024);throw new java.net.SocketTimeoutException();}
                received.write(data);return new UploadTransfer.Reply(total,"done");
            }
            public UploadTransfer.Reply query(long total){return new UploadTransfer.Reply(received.size(),null);}
        },()->{},(n,t)->{});
        assertEquals(2,sends.get());assertArrayEquals(java.nio.file.Files.readAllBytes(file.toPath()),received.toByteArray());
    }
    @Test public void invalidOffsetsAndNonProgressCannotLoopOrSkipSource()throws Exception {
        File file=source(10);
        for(long offset:new long[]{-1,11,0}) {
            AtomicInteger calls=new AtomicInteger();
            assertThrows(IOException.class,()->UploadTransfer.run(file,new UploadTransfer.Remote(){
                public UploadTransfer.Reply put(long s,byte[] b,long t){calls.incrementAndGet();return new UploadTransfer.Reply(offset,null);}
                public UploadTransfer.Reply query(long t){fail();return null;}
            },()->{},(n,t)->{}));
            assertTrue(calls.get()<=4);
        }
    }
    @Test public void permissionAndCancellationDoNotReplayUpload()throws Exception {
        File file=source(10);
        for(IOException error:new IOException[]{new UploadTransfer.Rejected("forbidden"),new javax.net.ssl.SSLHandshakeException("bad chain"),new DriveSession.AuthorizationRequired()}) {
            AtomicInteger calls=new AtomicInteger();
            assertSame(error,assertThrows(IOException.class,()->UploadTransfer.run(file,new UploadTransfer.Remote(){
                public UploadTransfer.Reply put(long s,byte[] b,long t)throws IOException{calls.incrementAndGet();throw error;}
                public UploadTransfer.Reply query(long t){fail();return null;}
            },()->{},(n,t)->{})));assertEquals(1,calls.get());
        }
        AtomicBoolean cancelled=new AtomicBoolean();
        assertThrows(InterruptedIOException.class,()->UploadTransfer.run(file,new UploadTransfer.Remote(){
            public UploadTransfer.Reply put(long s,byte[] b,long t)throws IOException{cancelled.set(true);throw new java.net.SocketException();}
            public UploadTransfer.Reply query(long t){fail();return null;}
        },()->{if(cancelled.get())throw new InterruptedIOException();},(n,t)->{}));
    }
    @Test public void longRetryAfterIsNotIgnoredByStatusRecovery()throws Exception {
        File file=source(10);
        assertThrows(ReadRetry.Unavailable.class,()->UploadTransfer.run(file,new UploadTransfer.Remote(){
            public UploadTransfer.Reply put(long s,byte[] b,long t)throws IOException{throw new ReadRetry.Unavailable(429,"60");}
            public UploadTransfer.Reply query(long t){fail();return null;}
        },()->{},(n,t)->{}));
    }
}
