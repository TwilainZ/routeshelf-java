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
    enum TlsMode { NORMAL, NO_SNI }
    static final class Route {
        final String host, ip;
        final TlsMode tls;
        final long checkedAt;
        Route(String host, String ip, TlsMode tls, long checkedAt) {
            this.host = host; this.ip = ip; this.tls = tls; this.checkedAt = checkedAt;
        }
        String key() { return ip + "/" + tls; }
    }
    interface Candidates { List<String> get(String host) throws IOException; }
    interface Probe { void check(Route route) throws IOException; }
    interface Store {
        Route load(String host);
        void save(String host, Route route);
        void remove(String host);
        void clear();
    }
    interface Observer { void update(String message); }
    private final Candidates candidates;
    private final Probe probe;
    private final Store store;
    private final Observer observer;
    private final long budgetMillis;
    private final ReentrantLock gate = new ReentrantLock();
    private final Object cacheLock = new Object();
    private volatile long generation;
    private final java.util.Map<String, Long> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> rejected = new java.util.concurrent.ConcurrentHashMap<>();

    DirectRoutes(Candidates candidates, Probe probe, Store store, Observer observer) {
        this(candidates, probe, store, observer, 12000);
    }
    DirectRoutes(Candidates candidates, Probe probe, Store store, Observer observer, long budgetMillis) {
        this.candidates = candidates; this.probe = probe; this.store = store;
        this.observer = observer; this.budgetMillis = budgetMillis;
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
            Route cached = store.load(host);
            if(cached!=null&&!automaticAddress(cached.ip)) {
                synchronized(cacheLock){store.remove(host);}cached=null;
            }
            if (cached != null && cached.host.equals(host) && automaticAddress(cached.ip)
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
            if (cached != null && automaticAddress(cached.ip)) ips.add(cached.ip);
            for (String ip : candidates.get(host)) {
                if (automaticAddress(ip)) ips.add(ip);
                if (ips.size() >= 16) break;
            }
            List<Route> attempts = new ArrayList<>();
            // Both modes enter the bounded worker queue, so one blocked mode cannot starve the other.
            for (String ip : ips) for (TlsMode mode : TlsMode.values()) {
                Route route = new Route(host, ip, mode, now);
                if (!failed.contains(route.key()) && !recentlyRejected(route)) attempts.add(route);
            }
            ExecutorService workers = Executors.newFixedThreadPool(6);
            List<Future<Route>> jobs = new ArrayList<>();
            CompletionService<Route> completed = new ExecutorCompletionService<>(workers);
            int checked = 0;
            try {
                observer.update("正在自动检测 " + host + " 的直连线路（最多 12 秒）…");
                for (Route route : attempts) jobs.add(completed.submit(() -> { probe.check(route); return route; }));
                while (checked < jobs.size()) {
                    if (generation != epoch) throw new IOException("网络已切换，请重试以检测新线路");
                    long remaining = deadline - System.nanoTime();
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
                            store.save(host, fresh);
                        }
                        failures.remove(host);
                        observer.update(description(fresh, false));
                        return fresh;
                    } catch (java.util.concurrent.ExecutionException error) {
                        // Only counts/canonical host enter diagnostics, never request URLs or tokens.
                        observer.update("正在检测 " + host + "：已完成 " + checked + "/" + jobs.size());
                    }
                }
                String message = "未找到 " + host + " 的可用直连线路（已检测 " + checked + "/" + jobs.size()
                    + "）。可重新检测，或在网络设置中选择系统网络；VPN/TUN 可能接管直连连接。";
                observer.update(message);
                failures.put(host, System.nanoTime());
                throw new IOException(message);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                observer.update("直连检测已取消");
                throw interrupted();
            } finally {
                for (Future<Route> job : jobs) job.cancel(true);
                workers.shutdownNow();
            }
        } finally { gate.unlock(); }
    }

    void reset() { synchronized (cacheLock) { generation++; store.clear(); failures.clear(); rejected.clear(); } }
    void reject(Route route) {
        synchronized (cacheLock) {
            rejected.put(route.host + "/" + route.key(),System.nanoTime());
            Route cached = store.load(route.host);
            if (cached != null && cached.key().equals(route.key())) store.remove(route.host);
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
        return "自动直连 · " + route.host + " → " + route.ip
            + (route.tls == TlsMode.NO_SNI ? " · 无 SNI" : " · 标准 TLS")
            + (cached ? " · 已缓存" : " · 已通过证书及连通性检查");
    }
}
