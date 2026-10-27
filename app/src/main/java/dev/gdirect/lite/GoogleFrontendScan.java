package dev.gdirect.lite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.*;

/** Mobile-sized TCP preflight. TLS/hostname/API verification still follows; no credentials here. */
final class GoogleFrontendScan {
    interface Probe {void connect(String ip)throws IOException;}
    static List<String> reachable(List<String> candidates,GoogleAddressSpace space,Probe probe)throws IOException {
        ExecutorService pool=Executors.newFixedThreadPool(24);
        CompletionService<String> completed=new ExecutorCompletionService<>(pool);List<Future<String>> jobs=new ArrayList<>();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);
        try {
            for(String ip:new LinkedHashSet<>(candidates)) {
                if(!space.contains(ip))continue;
                jobs.add(completed.submit(()->{probe.connect(ip);return ip;}));if(jobs.size()==512)break;
            }
            List<String> result=new ArrayList<>();int count=0;
            while(count<jobs.size()&&result.size()<32) {
                long left=end-System.nanoTime();if(left<=0)break;
                Future<String> ready=completed.poll(left,TimeUnit.NANOSECONDS);if(ready==null)break;count++;
                try{result.add(ready.get());}catch(ExecutionException ignored){}
            }
            return result;
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Google 前端检测已取消");}
        finally{for(Future<String> job:jobs)job.cancel(true);pool.shutdownNow();}
    }
    static void tcp(String ip)throws IOException {
        try(java.net.Socket socket=new java.net.Socket()){socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName(ip),443),1200);}
    }
}
