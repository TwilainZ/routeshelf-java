package dev.gdirect.lite.network;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class RelayStartupBenchmark {
 public static void main(String[] args)throws Exception {
  String relay="183.56.143.147";AtomicInteger catalogCalls=new AtomicInteger(),probes=new AtomicInteger();
  DirectRoutes.Candidates candidates=new DirectRoutes.Candidates(){
   public List<String> preferred(String host){return Collections.singletonList(relay);}
   public List<String> get(String host)throws IOException {
    catalogCalls.incrementAndGet();try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    return Collections.singletonList(relay);
   }
  };
  DirectRoutes.Store store=new DirectRoutes.Store(){
   DirectRoutes.Route value;
   public DirectRoutes.Route load(String host){return value;}
   public void save(String host,DirectRoutes.Route route){value=route;}
   public void remove(String host){value=null;}
   public void clear(){value=null;}
  };
  DirectRoutes routes=new DirectRoutes(candidates,route->{probes.incrementAndGet();if(route.tls!=DirectRoutes.TlsMode.GOOGLE_FRONT)throw new IOException("fixture TLS mode unavailable");},store,text->{},45000,relay::equals);
  long start=System.nanoTime();DirectRoutes.Route route=routes.select("www.googleapis.com",Collections.emptySet());
  System.out.println("cold_ms="+(System.nanoTime()-start)/1000000+" catalog_calls="+catalogCalls.get()+" probes="+probes.get()+" tls="+route.tls);
  start=System.nanoTime();routes.select("www.googleapis.com",Collections.emptySet());
  System.out.println("warm_ms="+(System.nanoTime()-start)/1000000+" catalog_calls="+catalogCalls.get());
 }
}
