package dev.gdirect.lite;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Independently designed, bounded discovery. Candidates are hints, never trust anchors. */
final class DirectRoutes {
    static final long MAX_AGE = TimeUnit.HOURS.toMillis(24);
    enum TlsMode { NORMAL, GOOGLE_FRONT, NO_SNI }
    static final class Route {
        final String host, ip;
        final TlsMode tls;
        final long checkedAt;
        Route(String host, String ip, TlsMode tls, long checkedAt) {
            this.host = host; this.ip = ip; this.tls = tls; this.checkedAt = checkedAt;
        }
        String key() { return ip + "/" + tls; }
    }
    interface Candidates {
        List<String> get(String host) throws IOException;
        default List<String> expand(String host,List<String> observed)throws IOException{return java.util.Collections.emptyList();}
    }
    interface Probe { void check(Route route) throws IOException; }
    interface Store {
        Route load(String host);
        void save(String host, Route route);
        void remove(String host);
        void clear();
        default List<Route> loadAll(String host) { Route route=load(host);return route==null?java.util.Collections.emptyList():java.util.Collections.singletonList(route); }
        default void saveAll(String host,List<Route> routes) { if(!routes.isEmpty())save(host,routes.get(0)); }
        default void reject(Route route) { remove(route.host); }
    }
    interface Observer { void update(String message); }
    private final Candidates candidates;
    private final Probe probe;
    private final Store store;
    private final Observer observer;
    private final long budgetMillis;
    private final java.util.function.Predicate<String> permitted;
    private final ReentrantLock gate = new ReentrantLock();
    private final Object cacheLock = new Object();
    private volatile long generation;
    private final java.util.Map<String, Long> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> rejected = new java.util.concurrent.ConcurrentHashMap<>();

    DirectRoutes(Candidates candidates, Probe probe, Store store, Observer observer) {
        this(candidates, probe, store, observer, 45000);
    }
    DirectRoutes(Candidates candidates, Probe probe, Store store, Observer observer, long budgetMillis) {
        this(candidates,probe,store,observer,budgetMillis,DirectRoutes::automaticAddress);
    }
    DirectRoutes(Candidates candidates,Probe probe,Store store,Observer observer,long budgetMillis,java.util.function.Predicate<String> permitted) {
        this.candidates = candidates; this.probe = probe; this.store = store;
        this.observer = observer; this.budgetMillis = budgetMillis;this.permitted=permitted;
    }

    static boolean publicIpv4(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = value.split("\\.");
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].length() > 1 && parts[i].startsWith("0")) return false;
            n[i] = Integer.parseInt(parts[i]);
            if (n[i] > 255) return false;
        }
        return n[0] != 0 && n[0] != 10 && n[0] != 127 && n[0] < 224
            && !(n[0] == 100 && n[1] >= 64 && n[1] <= 127)
            && !(n[0] == 169 && n[1] == 254) && !(n[0] == 172 && n[1] >= 16 && n[1] <= 31)
            && !(n[0] == 192 && (n[1] == 168 || (n[1] == 0 && (n[2] == 0 || n[2] == 2))))
            && !(n[0] == 198 && (n[1] == 18 || n[1] == 19 || (n[1] == 51 && n[2] == 100)))
            && !(n[0] == 203 && n[1] == 0 && n[2] == 113);
    }

    // Identified by the service author's correction as a third-party reverse proxy.
    // A public address and a valid certificate alone do not establish Google ownership.
    static boolean automaticAddress(String ip) {
        return publicIpv4(ip) && !"183.56.143.147".equals(ip);
    }

    static List<String> candidates(List<String> freshDns, List<String> history) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String value : freshDns) {
            if (automaticAddress(value)) ordered.add(value);
            if (ordered.size() >= 8) break;
        }
        for (String value : history) {
            if (automaticAddress(value)) ordered.add(value);
            if (ordered.size() >= 16) break;
        }
        return new ArrayList<>(ordered);
    }

    static List<String> candidates(List<String> system,List<String> encrypted,List<String> history) {
        LinkedHashSet<String> fresh=new LinkedHashSet<>();
        // Neither a poisoned system answer set nor one resolver may consume all slots.
        for(int i=0;i<Math.max(system.size(),encrypted.size())&&fresh.size()<8;i++) {
            if(i<encrypted.size()&&automaticAddress(encrypted.get(i)))fresh.add(encrypted.get(i));
            if(i<system.size()&&fresh.size()<8&&automaticAddress(system.get(i)))fresh.add(system.get(i));
        }
        return candidates(new ArrayList<>(fresh),history);
    }

    Route select(String host, Set<String> failed) throws IOException {
        if (!DirectNet.googleHost(host)) throw new IOException("不允许的直连域名");
        try { gate.lockInterruptibly(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw interrupted(); }
        try {
            long epoch = generation;
            long now = System.currentTimeMillis();
            for(Route alternative:store.loadAll(host)) {
                if(alternative!=null&&alternative.host.equals(host)&&permitted.test(alternative.ip)&&alternative.checkedAt<=now
                    &&now-alternative.checkedAt<MAX_AGE&&!failed.contains(alternative.key())&&!recentlyRejected(alternative)) {
                    if(generation!=epoch)throw new IOException("网络已切换，请重新检测直连线路");
                    observer.update(description(alternative,true));return alternative;
                }
            }
            Route cached = store.load(host);
            if(cached!=null&&!permitted.test(cached.ip)) {
                synchronized(cacheLock){store.remove(host);}cached=null;
            }
            if (cached != null && cached.host.equals(host) && permitted.test(cached.ip)
                    && cached.checkedAt <= now && now - cached.checkedAt < MAX_AGE
                    && !failed.contains(cached.key()) && !recentlyRejected(cached)) {
                if (generation != epoch) throw new IOException("网络已切换，请重新检测直连线路");
                observer.update(description(cached, true));
                return cached;
            }
            if (generation != epoch) throw new IOException("网络已切换，请重新检测直连线路");
            Long recent = failures.get(host);
            if (recent != null && System.nanoTime() - recent < TimeUnit.SECONDS.toNanos(10))
                throw new IOException("刚才未找到可用直连线路；请稍后重试或点重新检测直连");
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
            observer.update("正在自动收集 " + host + " 的候选线路…");
            LinkedHashSet<String> ips = new LinkedHashSet<>();
            if (cached != null && permitted.test(cached.ip)) ips.add(cached.ip);
            for (String ip : candidates.get(host)) {
                if (permitted.test(ip)) ips.add(ip);
                if (ips.size() >= 48) break;
            }
            Route found=verify(host,ips,failed,now,epoch,Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(10)));
            if(found!=null)return found;
            if(System.nanoTime()<deadline) {
                LinkedHashSet<String> expanded=new LinkedHashSet<>();
                for(String ip:candidates.expand(host,new ArrayList<>(ips)))if(permitted.test(ip)){expanded.add(ip);if(expanded.size()>=48)break;}
                found=verify(host,expanded,failed,now,epoch,deadline);if(found!=null)return found;
            }
            String message="未找到 "+host+" 的可用连接。可在连接设置中重新检测或切换模式；VPN/TUN 可能接管连接。";
            observer.update(message);failures.put(host,System.nanoTime());throw new IOException(message);
        } finally { gate.unlock(); }
    }

    private Route verify(String host,Set<String> ips,Set<String> failed,long now,long epoch,long deadline)throws IOException {
        List<Route> attempts = new ArrayList<>();
        // Identity alternatives enter the bounded queue; a blocked mode cannot starve every other mode.
        for (String ip : ips) for (TlsMode mode : TlsMode.values()) {
            Route route = new Route(host, ip, mode, now);
            if (!failed.contains(route.key()) && !recentlyRejected(route)) attempts.add(route);
        }
        ExecutorService workers = Executors.newFixedThreadPool(6);
        List<Future<Route>> jobs = new ArrayList<>();
        CompletionService<Route> completed = new ExecutorCompletionService<>(workers);
        int checked = 0;
        try {
            observer.update("正在验证 " + host + " 的连接与证书，可随时取消…");
            for (Route route : attempts) jobs.add(completed.submit(() -> { probe.check(route); return route; }));
            List<Route> verified=new ArrayList<>();
            long collectUntil=deadline;
            while (checked < jobs.size()) {
                if (generation != epoch) throw new IOException("网络已切换，请重试以检测新线路");
                long remaining = Math.min(deadline,collectUntil) - System.nanoTime();
                if (remaining <= 0) break;
                Future<Route> ready = completed.poll(remaining, TimeUnit.NANOSECONDS);
                if (ready == null) break;
                checked++;
                try {
                    Route found = ready.get();
                    Route fresh = new Route(host, found.ip, found.tls, System.currentTimeMillis());
                    synchronized (cacheLock) {
                        if (Thread.currentThread().isInterrupted()) throw interrupted();
                        if (generation != epoch) throw new IOException("网络已切换，请重试");
                        verified.add(fresh);
                        store.saveAll(host,new ArrayList<>(verified));
                    }
                    failures.remove(host);
                    observer.update(description(verified.get(0), false));
                    if(verified.size()==1)collectUntil=Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800));
                    if(verified.size()>=8)return verified.get(0);
                } catch (java.util.concurrent.ExecutionException error) {
                    // Only counts/canonical host enter diagnostics, never request URLs or tokens.
                    observer.update("正在检测 " + host + "：已完成 " + checked + "/" + jobs.size());
                }
            }
            if(!verified.isEmpty()){observer.update(description(verified.get(0),false));return verified.get(0);}
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            observer.update("直连检测已取消");
            throw interrupted();
        } finally {
            for (Future<Route> job : jobs) job.cancel(true);
            workers.shutdownNow();
        }
    }

    void reset() { synchronized (cacheLock) { generation++; store.clear(); failures.clear(); rejected.clear(); } }
    void reject(Route route) {
        synchronized (cacheLock) {
            rejected.put(route.host + "/" + route.key(),System.nanoTime());
            store.reject(route);
        }
    }
    private boolean recentlyRejected(Route route) {
        String key = route.host + "/" + route.key();
        Long time = rejected.get(key);
        if (time == null) return false;
        if (System.nanoTime() - time < TimeUnit.SECONDS.toNanos(30)) return true;
        rejected.remove(key,time); return false;
    }
    private static InterruptedIOException interrupted() { return new InterruptedIOException("直连检测已取消"); }
    static String description(Route route, boolean cached) {
        return (route.ip.equals("183.56.143.147")?"第三方中转 · ":"Google 前端 · ") + route.host + " → " + route.ip
            + (route.tls == TlsMode.NO_SNI ? " · 无 SNI" : route.tls==TlsMode.GOOGLE_FRONT?" · Google 前端身份":" · 标准 TLS")
            + (cached ? " · 已缓存" : " · 已通过证书及连通性检查");
    }
}
