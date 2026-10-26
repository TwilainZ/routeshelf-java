package dev.gdirect.lite;

import android.app.Application;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.DnsResolver;
import android.os.CancellationSignal;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** One routing policy for public transfers and authenticated Drive requests. Auth SDK stays separate. */
public final class GoogleNetwork extends Application {
    private static GoogleNetwork instance;
    private SharedPreferences preferences;
    private DirectRoutes routes;
    private final OkHttpClient base = new OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private final GoogleHttp.DirectClients directClients = new GoogleHttp.DirectClients(base);
    private volatile String summary = "自动直连：浏览或下载时自动检测，无需手工填写 IP";

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        java.io.File[] abandonedPreviews=getCacheDir().listFiles((dir,name)->name.startsWith("drive-preview-"));
        if(abandonedPreviews!=null)for(java.io.File file:abandonedPreviews)file.delete();
        preferences = getSharedPreferences("direct-network", MODE_PRIVATE);
        routes = new DirectRoutes(this::candidates, this::probe, new DirectRoutes.Store() {
            @Override public DirectRoutes.Route load(String host) {
                String[] fields = preferences.getString("route:" + host, "").split("\\|", -1);
                try {
                    if (fields.length == 3) return new DirectRoutes.Route(host, fields[0],
                        DirectRoutes.TlsMode.valueOf(fields[1]), Long.parseLong(fields[2]));
                } catch (IllegalArgumentException ignored) { }
                return null;
            }
            @Override public void save(String host, DirectRoutes.Route route) {
                SharedPreferences.Editor editor = preferences.edit();
                List<String> keys = new ArrayList<>();
                for (String key : preferences.getAll().keySet()) if (key.startsWith("route:") && !key.equals("route:" + host)) keys.add(key);
                Collections.sort(keys);
                for (int i = 0; i < keys.size() - 31; i++) editor.remove(keys.get(i));
                LinkedHashSet<String> hints = new LinkedHashSet<>();
                hints.add(route.ip);
                for (String value : preferences.getString("known-ip-hints", "").split(",")) {
                    if (DirectRoutes.publicIpv4(value) && hints.size() < 16) hints.add(value);
                }
                editor.putString("known-ip-hints",String.join(",",hints));
                editor.putString("route:" + host, route.ip + "|" + route.tls + "|" + route.checkedAt).apply();
            }
            @Override public void remove(String host) { preferences.edit().remove("route:" + host).apply(); }
            @Override public void clear() {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : preferences.getAll().keySet()) if (key.startsWith("route:")) editor.remove(key);
                editor.apply();
            }
        }, text -> summary = text);
        DirectNet.connections = this::open;
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            private Network current;
            @Override public void onAvailable(Network network) {
                if (current != null && !network.equals(current)) reset();
                current = network;
            }
            @Override public void onLost(Network network) {
                if (network.equals(current)) { reset(); current = null; summary = "网络已断开，恢复连接后将自动检测直连线路"; }
            }
        });
    }
    static GoogleNetwork get() { return instance; }
    String summary() { return systemNetwork() ? "系统网络：遵循设备的 DNS 和代理设置" : summary; }
    boolean systemNetwork() { return preferences.getBoolean("system-network", false); }
    void systemNetwork(boolean enabled) {
        preferences.edit().putBoolean("system-network",enabled).apply();
        summary = enabled ? "系统网络" : "自动直连：浏览或下载时自动检测";
    }
    void reset() { routes.reset(); directClients.clear(); }

    private List<String> candidates(String host) throws IOException {
        // This hint already existed in our Java prototype. It is untrusted and must pass real-host TLS.
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("183.56.143.147");
        // Network changes discard usable routes, but retain bounded historical hints for a fresh TLS probe.
        for (String value : preferences.getString("known-ip-hints", "").split(","))
            if (DirectRoutes.publicIpv4(value)) result.add(value);
        // Reuse other successfully verified hosts only as candidate hints; each new host is probed separately.
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith("route:")) {
                String value = preferences.getString(key, "").split("\\|", -1)[0];
                if (DirectRoutes.publicIpv4(value)) result.add(value);
            }
        }
        // Android's asynchronous resolver supports actual cancellation; an interrupted
        // getAllByName can otherwise occupy every DNS worker after a network outage.
        List<Future<List<String>>> lookups = new ArrayList<>();
        List<CancellationSignal> cancellations = new ArrayList<>();
        List<String> freshDns = new ArrayList<>();
        for (String name : new String[]{host,"www.googleapis.com","drive.usercontent.google.com"}) {
            java.util.concurrent.CompletableFuture<List<String>> lookup = new java.util.concurrent.CompletableFuture<>();
            CancellationSignal cancellation = new CancellationSignal();
            lookups.add(lookup); cancellations.add(cancellation);
            try {
                DnsResolver.getInstance().query(null,name,DnsResolver.TYPE_A,DnsResolver.FLAG_EMPTY,
                    Runnable::run,cancellation,new DnsResolver.Callback<List<InetAddress>>() {
                        @Override public void onAnswer(List<InetAddress> addresses,int rcode) {
                            List<String> ips = new ArrayList<>();
                            for (InetAddress address : addresses)
                                if (address.getAddress().length==4 && DirectRoutes.publicIpv4(address.getHostAddress()))
                                    ips.add(address.getHostAddress());
                            lookup.complete(ips);
                        }
                        @Override public void onError(DnsResolver.DnsException error) {
                            lookup.complete(Collections.emptyList());
                        }
                    });
            } catch (RuntimeException unavailable) { lookup.complete(Collections.emptyList()); }
        }
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200);
        try {
            while (!lookups.isEmpty() && System.nanoTime() < end) {
                for (java.util.Iterator<Future<List<String>>> iterator = lookups.iterator(); iterator.hasNext();) {
                    Future<List<String>> lookup = iterator.next();
                    if (!lookup.isDone()) continue;
                    try { freshDns.addAll(lookup.get()); }
                    catch (java.util.concurrent.ExecutionException ignored) { }
                    iterator.remove();
                }
                if (!lookups.isEmpty()) Thread.sleep(20);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("直连检测已取消");
        } finally {
            for (CancellationSignal cancellation : cancellations) cancellation.cancel();
            for (Future<List<String>> lookup : lookups) lookup.cancel(true);
        }
        return DirectRoutes.candidates(freshDns,new ArrayList<>(result));
    }

    private void probe(DirectRoutes.Route route) throws IOException {
        OkHttpClient client = GoogleHttp.direct(base, route).newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS).callTimeout(3,TimeUnit.SECONDS).build();
        boolean api = route.host.equals("www.googleapis.com");
        URL target = new URL("https://" + route.host + (api ? "/drive/v3/about?fields=user" : "/"));
        // No credentials or private file identifiers are used for discovery.
        try (Response response = client.newCall(new Request.Builder().url(target).get().build()).execute()) {
            if (api) {
                if (response.code() != 401) throw new IOException("Unexpected API probe status");
                try {
                    if (new JSONObject(response.peekBody(8192).string()).getJSONObject("error").getInt("code") != 401)
                        throw new IOException("Invalid API probe payload");
                } catch (org.json.JSONException error) { throw new IOException("Invalid API probe payload"); }
            } else if (response.code() < 200 || response.code() >= 500) {
                throw new IOException("Unexpected endpoint status");
            }
        }
    }

    HttpsURLConnection open(URL url, String manualIp) throws IOException {
        return open(url, manualIp, () -> { });
    }
    HttpsURLConnection open(URL url, String manualIp, GoogleHttp.Guard guard) throws IOException {
        if (!"https".equals(url.getProtocol()) || !DirectNet.googleHost(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null)
            throw new IOException("不允许的 Google 数据地址");
        if (manualIp != null) {
            if (!DirectRoutes.publicIpv4(manualIp)) throw new IOException("手工地址必须是公网 IPv4，不能使用内网或 fake-IP");
            DirectRoutes.Route route = new DirectRoutes.Route(url.getHost(),manualIp,DirectRoutes.TlsMode.NORMAL,0);
            OkHttpClient manual = GoogleHttp.direct(base,route);
            return GoogleHttp.open(url, () -> manual, () -> { }, guard);
        }
        if (systemNetwork()) return GoogleHttp.open(url,()->base,()->{},guard);
        Set<String> failed = new java.util.HashSet<>();
        DirectRoutes.Route[] selected = new DirectRoutes.Route[1];
        return GoogleHttp.open(url, () -> {
            selected[0] = routes.select(url.getHost(),failed);
            return directClients.get(selected[0]);
        }, () -> {
            if (selected[0] != null) { failed.add(selected[0].key()); routes.reject(selected[0]); }
        }, guard);
    }
    void checkRoutes() throws IOException {
        List<String> results = new ArrayList<>();
        boolean failed = false;
        for (String host : new String[]{"www.googleapis.com","drive.usercontent.google.com"}) {
            try { results.add(DirectRoutes.description(routes.select(host,Collections.emptySet()),false)); }
            catch (IOException error) {
                results.add(error.getMessage()); failed = true;
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
        summary = String.join("\n",results);
        if (failed) throw new IOException(summary);
    }
}
