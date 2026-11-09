package dev.gdirect.lite.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded TCP -> TLS pipeline. A TCP answer never counts as a verified service. */
final class RouteProbeQueue implements AutoCloseable {
    static final class Result {
        final DirectRoutes.Route route;
        Result(DirectRoutes.Route route){this.route=route;}
    }
    private final ExecutorService tcp=Executors.newFixedThreadPool(24);
    private final ExecutorService tls=Executors.newFixedThreadPool(6);
    private final ExecutorService tcp6=Executors.newFixedThreadPool(4);
    private final ExecutorService tls6=Executors.newFixedThreadPool(2);
    private final BlockingQueue<Result> results=new LinkedBlockingQueue<>();
    private final AtomicInteger pending=new AtomicInteger();
    private final AtomicBoolean closed=new AtomicBoolean();

    RouteProbeQueue(List<DirectRoutes.Route> attempts,DirectRoutes.Probe probe) {
        Map<String,List<DirectRoutes.Route>> addresses=new LinkedHashMap<>();
        for(DirectRoutes.Route route:attempts)addresses.computeIfAbsent(route.ip,k->new ArrayList<>()).add(route);
        pending.set(addresses.size());
        for(Map.Entry<String,List<DirectRoutes.Route>> entry:addresses.entrySet()) {
            boolean ipv6=IpLiteral.ipv6(entry.getKey());
            (ipv6?tcp6:tcp).execute(()->{
            try {
                if(closed.get())return;
                probe.connect(entry.getKey());
                for(DirectRoutes.Route route:entry.getValue()) {
                    if(closed.get())break;
                    pending.incrementAndGet();
                    try { (ipv6?tls6:tls).execute(()->{
                        DirectRoutes.Route verified=null;
                        try{if(!closed.get()){probe.check(route);verified=route;}}
                        catch(IOException|RuntimeException ignored) { }
                        finally{results.offer(new Result(verified));}
                    }); } catch(java.util.concurrent.RejectedExecutionException stopped){pending.decrementAndGet();}
                }
            }catch(IOException|RuntimeException ignored) { }
            finally{results.offer(new Result(null));}
            });
        }
    }
    boolean active(){return pending.get()>0||!results.isEmpty();}
    Result poll(long nanos)throws InterruptedException{
        Result result=results.poll(nanos,TimeUnit.NANOSECONDS);if(result!=null)pending.decrementAndGet();return result;
    }
    @Override public void close(){closed.set(true);tcp.shutdownNow();tls.shutdownNow();tcp6.shutdownNow();tls6.shutdownNow();}
}
