package dev.gdirect.lite.transfer;

import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.network.ReadRetry;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/** Resumes only after asking the server for its committed offset; never blindly replays a PUT. */
public final class UploadTransfer {
    public static final class Rejected extends IOException { public Rejected(String reason){super(reason);} }
    public static final int CHUNK=512*1024;
    public static final class Reply {
        public final long committed;
        public final String complete;
        public Reply(long committed,String complete){this.committed=committed;this.complete=complete;}
    }
    public interface Remote {
        Reply put(long start,byte[] bytes,long total)throws IOException;
        Reply query(long total)throws IOException;
    }
    public interface Progress { void update(long committed,long total); }
    public static String run(File source,Remote remote,ReadRetry.Check check,Progress progress)throws IOException {
        long total=source.length(),offset=0;
        if(total==0)throw new IOException("空文件应使用元数据创建");
        try(RandomAccessFile input=new RandomAccessFile(source,"r")) {
            while(offset<total) {
                check.run();input.seek(offset);
                byte[] block=new byte[(int)Math.min(CHUNK,total-offset)];input.readFully(block);
                long end=offset+block.length;
                int attempts=0;
                while(offset<end) {
                    check.run();Reply reply;
                    try { reply=remote.put(offset,Arrays.copyOfRange(block,(int)(offset-(end-block.length)),block.length),total); }
                    catch(IOException uncertain) {
                        check.run();
                        if(uncertain instanceof Rejected || uncertain instanceof DriveSession.AuthorizationRequired || uncertain instanceof javax.net.ssl.SSLException
                            || (uncertain instanceof java.io.InterruptedIOException && !(uncertain instanceof java.net.SocketTimeoutException)))throw uncertain;
                        if(++attempts>3)throw new IOException("上传暂时中断，请重试；不会自动重复创建文件");
                        java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
                        reply=ReadRetry.run(()->{if(first.getAndSet(false)&&uncertain instanceof ReadRetry.Unavailable)throw uncertain;return remote.query(total);},check);
                    }
                    check.run();
                    if(reply.committed<offset||reply.committed>end)throw new IOException("上传服务器确认了无效偏移");
                    if(reply.complete!=null) {
                        if(reply.committed!=total||end!=total)throw new IOException("上传提前结束，已停止");
                        progress.update(total,total);return reply.complete;
                    }
                    if(reply.committed==offset && ++attempts>3)throw new IOException("上传没有进展，请稍后重试");
                    offset=reply.committed;progress.update(offset,total);
                }
            }
            check.run();Reply finalReply=remote.query(total);
            if(finalReply.complete!=null&&finalReply.committed==total)return finalReply.complete;
            throw new IOException("Google 尚未确认上传完成，请刷新列表核对");
        }
    }
    private UploadTransfer(){}
}
