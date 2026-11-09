package dev.gdirect.lite.network;

import dev.gdirect.lite.diagnostics.DiagnosticLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Google Public DNS JSON API. Public names only; never a Drive request or credential. */
public final class GoogleDnsDiscovery {
    public static final String HOST="dns.google";
    public static final int MAX_BYTES=32768;
    // Google documents these anycast service addresses; they are not Drive route hints.
    // https://developers.google.com/speed/public-dns/docs/using
    private static final String[] BOOTSTRAP={"8.8.8.8","8.8.4.4"};
    private static final String[] BOOTSTRAP_V6={"2001:4860:4860::8888","2001:4860:4860::8844"};
    public interface Exchange { Call create(DirectRoutes.Route route,Request request)throws IOException; }
    private final Exchange exchange;
    public GoogleDnsDiscovery(OkHttpClient trusted) {
        this(exchange(trusted));
    }
    private static Exchange exchange(OkHttpClient trusted) {
        // IPv6 resolver attempts do not consume the original IPv4 dispatcher slots.
        okhttp3.Dispatcher dispatcher=new okhttp3.Dispatcher();dispatcher.setMaxRequests(8);dispatcher.setMaxRequestsPerHost(8);
        OkHttpClient ipv6=trusted.newBuilder().dispatcher(dispatcher).build();
        return (route,request)->GoogleHttp.direct(IpLiteral.ipv6(route.ip)?ipv6:trusted,route).newBuilder()
            .callTimeout(1500,java.util.concurrent.TimeUnit.MILLISECONDS)
            .connectTimeout(1000,java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(1000,java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build().newCall(request);
    }
    public GoogleDnsDiscovery(Exchange exchange){this.exchange=exchange;}
    public Query start(String host)throws IOException {
        return start(host,false);
    }
    public Query start(String host,boolean ipv6Available)throws IOException {
        if(!DirectNet.googleHost(host))throw new IOException("Not a Google data hostname");
        String name=canonical(host);
        List<String> servers=new ArrayList<>(java.util.Arrays.asList(BOOTSTRAP));
        if(ipv6Available)servers.addAll(java.util.Arrays.asList(BOOTSTRAP_V6));
        int[] types=ipv6Available?new int[]{1,28}:new int[]{1};
        Query query=new Query(servers.size()*types.length*2);
        // Preserve the original A/IPv4 work first; each additional family is independently bounded.
        for(int recordType:types)for(String ip:servers)for(DirectRoutes.TlsMode tls:new DirectRoutes.TlsMode[]{DirectRoutes.TlsMode.NORMAL,DirectRoutes.TlsMode.NO_SNI}) {
            HttpUrl url=new HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("resolve")
            .addQueryParameter("name",name).addQueryParameter("type",recordType==1?"A":"AAAA")
            // Do not forward a user subnet to authoritative DNS servers.
            .addQueryParameter("edns_client_subnet",recordType==1?"0.0.0.0/0":"::/0").build();
        Request request=new Request.Builder().url(url).header("Accept","application/dns-json").get().build();
            try {
                Call call=exchange.create(new DirectRoutes.Route(HOST,ip,tls,0),request);
                query.calls.add(call);long dnsStart=System.nanoTime();
                call.enqueue(new Callback(){
                    @Override public void onFailure(Call ignored,IOException error){DiagnosticLog.get().record(DiagnosticLog.Event.DNS_FAILED,0,HOST,ip,tls,DiagnosticLog.elapsed(dnsStart),0,error);query.remaining.decrementAndGet();}
                    @Override public void onResponse(Call ignored,Response response) {
                        try(Response closed=response) {
                            DiagnosticLog.get().record(DiagnosticLog.Event.DNS_HTTP,0,HOST,ip,tls,DiagnosticLog.elapsed(dnsStart),response.code(),null);
                            if(response.code()!=200||response.body()==null)return;
                            String type=response.header("Content-Type","").split(";",2)[0].trim();
                            if(!type.equalsIgnoreCase("application/dns-json")&&!type.equalsIgnoreCase("application/json"))return;
                            List<String> found=parse(name,read(response.body().byteStream()),recordType);
                            synchronized(query){if(!query.closed)query.addresses.addAll(found);}
                        }catch(IOException ignoredError){/* Failed hints never become usable routes. */}
                        finally{query.remaining.decrementAndGet();}
                    }
                });
            }catch(IOException|RuntimeException unavailable){query.remaining.decrementAndGet();}
        }
        return query;
    }
    public static final class Query implements AutoCloseable {
        public final List<Call> calls=new ArrayList<>();
        public final AtomicInteger remaining;
        public final LinkedHashSet<String> addresses=new LinkedHashSet<>();
        private boolean closed;
        private Query(int count){remaining=new AtomicInteger(count);}
        public boolean finished(){return remaining.get()==0;}
        public synchronized List<String> addresses(){return IpLiteral.interleave(new ArrayList<>(addresses));}
        @Override public void close(){synchronized(this){closed=true;}for(Call call:calls)call.cancel();}
    }
    private static String read(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;
        while((count=input.read(buffer))!=-1){if(bytes.size()+count>MAX_BYTES)throw new IOException("Oversized DNS response");bytes.write(buffer,0,count);}
        return bytes.toString("UTF-8");
    }
    public static List<String> parse(String host,String json)throws IOException {
        return parse(host,json,1);
    }
    public static List<String> parse(String host,String json,int recordType)throws IOException {
        if(json==null||json.length()>MAX_BYTES)throw new IOException("Oversized DNS response");
        if(recordType!=1&&recordType!=28)throw new IOException("Unsupported DNS question");
        try {
            String expected=canonical(host);
            JSONObject root=new JSONObject(json);
            if(root.getInt("Status")!=0||root.optBoolean("TC",false))return Collections.emptyList();
            JSONArray question=root.getJSONArray("Question");
            if(question.length()!=1||question.getJSONObject(0).getInt("type")!=recordType
                    ||!canonical(question.getJSONObject(0).getString("name")).equals(expected))
                throw new IOException("Mismatched DNS question");
            JSONArray answers=root.optJSONArray("Answer");
            if(answers==null)return Collections.emptyList();
            if(answers.length()>128)throw new IOException("Too many DNS records");
            // Follow only this question's bounded CNAME chain, ignoring unrelated records.
            LinkedHashSet<String> names=new LinkedHashSet<>();names.add(expected);
            for(int depth=0;depth<8;depth++) {
                String alias=null;
                for(int i=0;i<answers.length();i++) {
                    JSONObject record=answers.getJSONObject(i);
                    if(record.getInt("type")==5&&canonical(record.getString("name")).equals(expected)) {
                        String next=canonical(record.getString("data"));
                        if(alias!=null&&!alias.equals(next))throw new IOException("Conflicting DNS aliases");
                        alias=next;
                    }
                }
                if(alias==null)break;
                if(!names.add(alias))throw new IOException("DNS alias loop");
                expected=alias;
                if(depth==7)throw new IOException("DNS alias chain too deep");
            }
            LinkedHashSet<String> ips=new LinkedHashSet<>();
            for(int i=0;i<answers.length();i++) {
                JSONObject record=answers.getJSONObject(i);
                if(record.getInt("type")==recordType&&canonical(record.getString("name")).equals(expected)) {
                    String ip=record.getString("data");
                    if(IpLiteral.publicAddress(ip)&&IpLiteral.ipv6(ip)==(recordType==28)&&ips.size()<16)ips.add(IpLiteral.canonical(ip));
                }
            }
            return new ArrayList<>(ips);
        }catch(JSONException|IllegalArgumentException error){throw new IOException("Invalid DNS response");}
    }
    private static String canonical(String name)throws IOException {
        if(name==null)throw new IOException("Missing DNS name");
        String value=name.toLowerCase(Locale.ROOT);
        if(value.endsWith("."))value=value.substring(0,value.length()-1);
        if(value.length()>253||value.isEmpty())throw new IOException("Invalid DNS name");
        for(String label:value.split("\\.",-1))
            if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw new IOException("Invalid DNS label");
        return value;
    }
}
