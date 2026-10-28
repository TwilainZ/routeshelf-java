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
    static final String KNOWN_RELAY="183.56.143.147";
    private SharedPreferences preferences;
    private DirectRoutes routes;
    private GoogleRangeCatalog catalog;
    private GoogleBootstrap bootstrap;
    private volatile boolean bootstrapOnly;
    private volatile GoogleAddressSpace addressSpace;
    private final OkHttpClient base = GoogleTrust.clientBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).addNetworkInterceptor(GoogleCertificatePolicy.CHECK).build();
    private final GoogleDnsDiscovery encryptedDns = new GoogleDnsDiscovery(base);
    private final GoogleHttp.DirectClients directClients = new GoogleHttp.DirectClients(base);
    private volatile String summary = "自动直连：浏览或下载时自动检测，无需手工填写 IP";

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        java.io.File[] abandonedPreviews=getCacheDir().listFiles((dir,name)->name.startsWith("drive-preview-"));
        if(abandonedPreviews!=null)for(java.io.File file:abandonedPreviews)file.delete();
        preferences = getSharedPreferences("direct-network", MODE_PRIVATE);
        try{bootstrap=GoogleBootstrap.bundled();}catch(IOException error){throw new IllegalStateException("内置 Google 候选不可读取",error);}
        // Discard historical routes discovered before the relay's ownership was corrected.
        if(preferences.getInt("routing-policy-version",0)<2) {
            SharedPreferences.Editor migration=preferences.edit();
            for(String key:preferences.getAll().keySet())if(key.startsWith("route:"))migration.remove(key);
            migration.remove("known-ip-hints").putInt("routing-policy-version",2).apply();
        }
        android.util.AtomicFile rangeFile=new android.util.AtomicFile(new java.io.File(getFilesDir(),"google-ipranges.json"));
        catalog=new GoogleRangeCatalog(new GoogleRangeCatalog.Store(){
            public String read()throws IOException {
                try(java.io.InputStream in=rangeFile.openRead();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
                    byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>3*1024*1024)throw new IOException("地址缓存过大");bytes.write(buffer,0,n);}return bytes.toString("UTF-8");
                }
            }
            public void write(String value)throws IOException {
                java.io.FileOutputStream out=null;
                try{out=rangeFile.startWrite();out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));rangeFile.finishWrite(out);}
                catch(IOException failure){if(out!=null)rangeFile.failWrite(out);throw failure;}
            }
        },name->GoogleRangeCatalog.download(base,name));
        routes = new DirectRoutes(new DirectRoutes.Candidates(){
            public List<String> get(String host)throws IOException{return candidates(host);}
            public List<String> expand(String host,List<String> observed)throws IOException{return expandedCandidates(observed);}
        }, this::probe, new DirectRoutes.Store() {
            @Override public DirectRoutes.Route load(String host) {
                String[] fields = preferences.getString("route:" + host, "").split("\\|", -1);
                try {
                    if (fields.length == 3 && routeAddress(fields[0])) return new DirectRoutes.Route(host, fields[0],
                        DirectRoutes.TlsMode.valueOf(fields[1]), Long.parseLong(fields[2]));
                } catch (IllegalArgumentException ignored) { }
                return null;
            }
            @Override public void save(String host, DirectRoutes.Route route) {
                SharedPreferences.Editor editor = preferences.edit();
                List<String> keys = new ArrayList<>();
                for (String key : preferences.getAll().keySet()) if (key.startsWith("route:") && !key.equals("route:" + host)) keys.add(key);
                Collections.sort(keys);
                for (int i = 0; i < keys.size() - 31; i++) {editor.remove(keys.get(i));editor.remove("backups:"+keys.get(i).substring(6));}
                LinkedHashSet<String> hints = new LinkedHashSet<>();
                hints.add(route.ip);
                for (String value : preferences.getString("known-ip-hints", "").split(",")) {
                    if (DirectRoutes.automaticAddress(value) && hints.size() < 16) hints.add(value);
                }
                editor.putString("known-ip-hints",String.join(",",hints));
                editor.putString("route:" + host, route.ip + "|" + route.tls + "|" + route.checkedAt).apply();
            }
            @Override public void remove(String host) { preferences.edit().remove("route:" + host).apply(); }
            @Override public List<DirectRoutes.Route> loadAll(String host) {
                List<DirectRoutes.Route> result=new ArrayList<>();
                try {
                    org.json.JSONArray rows=new org.json.JSONArray(preferences.getString("backups:"+host,"[]"));
                    for(int i=0;i<Math.min(8,rows.length());i++) {
                        org.json.JSONObject row=rows.getJSONObject(i);String ip=row.getString("ip");
                        if(routeAddress(ip))result.add(new DirectRoutes.Route(host,ip,DirectRoutes.TlsMode.valueOf(row.getString("tls")),row.getLong("at")));
                    }
                }catch(org.json.JSONException|IllegalArgumentException ignored){}
                if(result.isEmpty()){DirectRoutes.Route primary=load(host);if(primary!=null)result.add(primary);}
                return result;
            }
            @Override public void saveAll(String host,List<DirectRoutes.Route> values) {
                org.json.JSONArray rows=new org.json.JSONArray();
                try {
                    for(DirectRoutes.Route value:values)rows.put(new org.json.JSONObject().put("ip",value.ip).put("tls",value.tls.name()).put("at",value.checkedAt));
                    preferences.edit().putString("backups:"+host,rows.toString()).apply();
                    if(!values.isEmpty())save(host,values.get(0));
                }catch(org.json.JSONException error){throw new IllegalStateException(error);}
            }
            @Override public void reject(DirectRoutes.Route failed) {
                List<DirectRoutes.Route> remaining=loadAll(failed.host);
                remaining.removeIf(value->value.key().equals(failed.key()));
                if(remaining.isEmpty())preferences.edit().remove("route:"+failed.host).remove("backups:"+failed.host).apply();
                else saveAll(failed.host,remaining);
            }
            @Override public void clear() {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : preferences.getAll().keySet()) if (key.startsWith("route:")||key.startsWith("backups:")) editor.remove(key);
                editor.apply();
            }
        }, text -> summary = (bootstrapOnly?"内置候选验证 · 公开报告 "+bootstrap.reportDate+"\n":"")+text,45000,this::routeAddress);
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
    boolean relayEnabled(){return preferences.getBoolean("allow-known-relay",false);}
    String builtInCandidates(){return bootstrap.description();}
    private boolean routeAddress(String ip){return (relayEnabled()&&KNOWN_RELAY.equals(ip))||(addressSpace!=null&&addressSpace.contains(ip));}
    void relayEnabled(boolean enabled){if(enabled!=relayEnabled()){preferences.edit().putBoolean("allow-known-relay",enabled).apply();reset();}}
    private void prepareSpace()throws IOException {
        summary="正在核对 Google 官方地址范围…";
        GoogleBootstrap.Scope scope=bootstrap.scope(catalog);
        addressSpace=scope.space;bootstrapOnly=scope.bundled;
        if(bootstrapOnly)summary="官方列表暂不可达，先验证内置公开报告候选；没有通过验证的地址不会使用";
    }
    void systemNetwork(boolean enabled) {
        preferences.edit().putBoolean("system-network",enabled).apply();
        summary = enabled ? "系统网络" : "自动直连：浏览或下载时自动检测";
    }
    void reset() { routes.reset(); directClients.clear(); if(catalog!=null)catalog.retryRefresh(); }

    private List<String> candidates(String host) throws IOException {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        // Network changes discard usable routes, but retain bounded historical hints for a fresh TLS probe.
        for (String value : preferences.getString("known-ip-hints", "").split(","))
            if (DirectRoutes.automaticAddress(value)) result.add(value);
        // Reuse other successfully verified hosts only as candidate hints; each new host is probed separately.
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith("route:")) {
                String value = preferences.getString(key, "").split("\\|", -1)[0];
                if (DirectRoutes.automaticAddress(value)) result.add(value);
            }
        }
        GoogleAddressSpace space=addressSpace;
        if(space==null){if(relayEnabled())return Collections.singletonList(KNOWN_RELAY);throw new IOException("尚未取得 Google 官方地址范围");}
        if(bootstrapOnly) {
            List<String> builtIn=bootstrap.candidates(space);
            if(relayEnabled())builtIn.add(0,KNOWN_RELAY);
            return builtIn;
        }
        summary="正在收集 " + host + " 的系统及加密 DNS 候选…";
        try (GoogleDnsDiscovery.Query encrypted = encryptedDns.start(host)) {
            // Android's asynchronous resolver supports actual cancellation; an interrupted
            // getAllByName can otherwise occupy every DNS worker after a network outage.
            List<Future<List<String>>> lookups = new ArrayList<>();
            List<CancellationSignal> cancellations = new ArrayList<>();
            List<String> freshDns = new ArrayList<>();
            for (String name : new LinkedHashSet<>(java.util.Arrays.asList(host,"www.googleapis.com","drive.usercontent.google.com"))) {
                java.util.concurrent.CompletableFuture<List<String>> lookup = new java.util.concurrent.CompletableFuture<>();
                CancellationSignal cancellation = new CancellationSignal();
                lookups.add(lookup); cancellations.add(cancellation);
                try {
                    DnsResolver.getInstance().query(null,name,DnsResolver.TYPE_A,DnsResolver.FLAG_EMPTY,
                        Runnable::run,cancellation,new DnsResolver.Callback<List<InetAddress>>() {
                            @Override public void onAnswer(List<InetAddress> addresses,int rcode) {
                                List<String> ips = new ArrayList<>();
                                for (InetAddress address : addresses)
                                    if (address.getAddress().length==4 && DirectRoutes.automaticAddress(address.getHostAddress()))
                                        ips.add(address.getHostAddress());
                                lookup.complete(ips);
                            }
                            @Override public void onError(DnsResolver.DnsException error) {
                                lookup.complete(Collections.emptyList());
                            }
                        });
                } catch (RuntimeException unavailable) { lookup.complete(Collections.emptyList()); }
            }
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1800);
            try {
                while ((!lookups.isEmpty() || !encrypted.finished()) && System.nanoTime() < end) {
                    for (java.util.Iterator<Future<List<String>>> iterator = lookups.iterator(); iterator.hasNext();) {
                        Future<List<String>> lookup = iterator.next();
                        if (!lookup.isDone()) continue;
                        try { freshDns.addAll(lookup.get()); }
                        catch (java.util.concurrent.ExecutionException ignored) { }
                        iterator.remove();
                    }
                    if (!lookups.isEmpty() || !encrypted.finished()) Thread.sleep(20);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("直连检测已取消");
            } finally {
                for (CancellationSignal cancellation : cancellations) cancellation.cancel();
                for (Future<List<String>> lookup : lookups) lookup.cancel(true);
            }
            List<String> encryptedAnswers=encrypted.addresses();
            freshDns.removeIf(ip->!space.contains(ip));encryptedAnswers.removeIf(ip->!space.contains(ip));result.removeIf(ip->!space.contains(ip));
            List<String> history=new ArrayList<>(result);
            List<String> output=bootstrap.candidates(space);
            for(String ip:DirectRoutes.candidates(freshDns,encryptedAnswers,history.subList(0,Math.min(4,history.size()))))
                if(output.size()<32&&!output.contains(ip))output.add(ip);
            for(String ip:history)if(output.size()<16&&!output.contains(ip))output.add(ip);
            if(relayEnabled())output.add(0,KNOWN_RELAY);
            return output;
        }
    }

    private List<String> expandedCandidates(List<String> observed)throws IOException {
        GoogleAddressSpace space=addressSpace;if(space==null)return Collections.emptyList();
        summary="正在扩大 Google 前端检测范围，可随时取消…";
        List<String> plan=space.discoveryCandidates(observed,512,new java.security.SecureRandom());
        return GoogleFrontendScan.reachable(plan,space,GoogleFrontendScan::tcp);
    }

    private void probe(DirectRoutes.Route route) throws IOException {
        if(!routeAddress(route.ip))throw new IOException("未允许此连接端点");
        OkHttpClient client = GoogleHttp.direct(base, route).newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS).build();
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
            if (!DirectRoutes.automaticAddress(manualIp)) throw new IOException("手工地址必须是公网 IPv4，不能使用内网、fake-IP 或已确认的第三方中转");
            prepareSpace();
            if(!addressSpace.contains(manualIp))throw new IOException("该地址不属于当前 Google 前端范围");
            DirectRoutes.Route route = new DirectRoutes.Route(url.getHost(),manualIp,DirectRoutes.TlsMode.NORMAL,0);
            OkHttpClient manual = GoogleHttp.direct(base,route);
            return GoogleHttp.open(url, () -> manual, () -> { }, guard);
        }
        if (systemNetwork()) return GoogleHttp.open(url,()->base,()->{},guard);
        Set<String> failed = new java.util.HashSet<>();
        DirectRoutes.Route[] selected = new DirectRoutes.Route[1];
        return GoogleHttp.open(url, () -> {
            prepareSpace();
            selected[0] = routes.select(url.getHost(),failed);
            return directClients.get(selected[0]);
        }, () -> {
            if (selected[0] != null) { failed.add(selected[0].key()); routes.reject(selected[0]); }
        }, guard);
    }
    void checkRoutes() throws IOException {
        prepareSpace();
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
