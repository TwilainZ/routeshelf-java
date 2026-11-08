package dev.gdirect.lite.network;

import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Current-process evidence only. Cached routes and authorization never establish data reachability. */
public final class ConnectionHealth {
    public enum Service { API, DOWNLOAD }
    public enum State { UNKNOWN, CHECKING, READY, RESPONSE_ERROR, FAILED, STALE }
    public enum Evidence { NONE, PROBE, RESPONSE, DOWNLOAD }
    public enum Problem { NONE, AUTHORIZATION, PERMISSION, NOT_FOUND, SERVER, TIMEOUT, CERTIFICATE, NETWORK }
    public static final long FRESH_MILLIS=TimeUnit.MINUTES.toMillis(5);
    public static final class Ticket {
        final long generation,sequence;final Service service;
        Ticket(long generation,long sequence,Service service){this.generation=generation;this.sequence=sequence;this.service=service;}
    }
    public static final class Result {
        public final State state;public final Evidence evidence;public final Problem problem;
        public final long ageMillis;public final int code;public final String ip;public final DirectRoutes.TlsMode tls;
        Result(State state,Evidence evidence,Problem problem,long age,int code,String ip,DirectRoutes.TlsMode tls){
            this.state=state;this.evidence=evidence;this.problem=problem;ageMillis=age;this.code=code;this.ip=ip;this.tls=tls;
        }
    }
    public static final class Snapshot {
        public final boolean online;public final Result api,download;
        Snapshot(boolean online,Result api,Result download){this.online=online;this.api=api;this.download=download;}
    }
    private static final class Entry {
        State state=State.UNKNOWN;Evidence evidence=Evidence.NONE;Problem problem=Problem.NONE;
        long completed,at=-1;int code;String ip="";DirectRoutes.TlsMode tls;
    }
    private final EnumMap<Service,Entry> entries=new EnumMap<>(Service.class);
    private final LongSupplier clock;
    private long generation,sequence;
    private boolean online;
    public ConnectionHealth(){this(()->TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));}
    public ConnectionHealth(LongSupplier clock){this.clock=clock;clear();}
    private void clear(){for(Service service:Service.values())entries.put(service,new Entry());}
    public synchronized void online(boolean value){if(online!=value){online=value;generation++;clear();}}
    public synchronized void reset(){generation++;clear();}
    public synchronized long generation(){return generation;}
    public synchronized Ticket begin(Service service,long expectedGeneration){
        Ticket ticket=new Ticket(expectedGeneration,++sequence,service);
        if(expectedGeneration==generation){Entry entry=entries.get(service);if(entry.state==State.UNKNOWN||entry.state==State.FAILED||entry.state==State.RESPONSE_ERROR
            ||(entry.at>=0&&clock.getAsLong()-entry.at>=FRESH_MILLIS))entry.state=State.CHECKING;}
        return ticket;
    }
    private Entry current(Ticket ticket){return ticket!=null&&ticket.generation==generation&&ticket.sequence>=entries.get(ticket.service).completed?entries.get(ticket.service):null;}
    public synchronized void route(Ticket ticket,DirectRoutes.Route route){
        Entry entry=current(ticket);if(entry!=null&&route!=null){entry.ip=route.ip;entry.tls=route.tls;}
    }
    public synchronized void verified(Ticket ticket,Evidence evidence){
        Entry entry=current(ticket);if(entry==null)return;
        entry.state=State.READY;entry.evidence=evidence;entry.problem=Problem.NONE;entry.code=0;entry.at=clock.getAsLong();entry.completed=ticket.sequence;
    }
    public synchronized void response(Ticket ticket,int code){
        Entry entry=current(ticket);if(entry==null)return;
        if(code>=200&&code<400){verified(ticket,Evidence.RESPONSE);entry.code=code;return;}
        entry.state=State.RESPONSE_ERROR;entry.evidence=Evidence.RESPONSE;entry.at=clock.getAsLong();entry.completed=ticket.sequence;entry.code=code;
        entry.problem=code==401?Problem.AUTHORIZATION:code==403?Problem.PERMISSION:code==404?Problem.NOT_FOUND:Problem.SERVER;
    }
    public synchronized void failed(Ticket ticket,Problem problem){
        Entry entry=current(ticket);if(entry==null)return;
        entry.state=State.FAILED;entry.evidence=Evidence.NONE;entry.problem=problem;entry.code=0;entry.at=clock.getAsLong();entry.completed=ticket.sequence;
    }
    public synchronized void cancelled(Ticket ticket){
        Entry entry=current(ticket);if(entry==null)return;
        if(entry.state==State.CHECKING)entry.state=State.UNKNOWN;
        entry.completed=ticket.sequence;
    }
    public synchronized void downloaded(long expectedGeneration){
        if(expectedGeneration==generation)verified(new Ticket(generation,++sequence,Service.DOWNLOAD),Evidence.DOWNLOAD);
    }
    public synchronized Snapshot snapshot(){return new Snapshot(online,result(Service.API),result(Service.DOWNLOAD));}
    private Result result(Service service){
        Entry entry=entries.get(service);long age=entry.at<0?-1:Math.max(0,clock.getAsLong()-entry.at);
        State state=entry.state;if((state==State.READY||state==State.FAILED||state==State.RESPONSE_ERROR)&&age>=FRESH_MILLIS)state=State.STALE;
        return new Result(state,entry.evidence,entry.problem,age,entry.code,entry.ip,entry.tls);
    }
    public static Service service(String host){return "www.googleapis.com".equals(host)?Service.API:Service.DOWNLOAD;}
}
