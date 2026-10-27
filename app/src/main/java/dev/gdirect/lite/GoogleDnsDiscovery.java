package dev.gdirect.lite;

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
final class GoogleDnsDiscovery {
    static final String HOST="dns.google";
    static final int MAX_BYTES=32768;
    // Google documents these anycast service addresses; they are not Drive route hints.
    // https://developers.google.com/speed/public-dns/docs/using
    private static final String[] BOOTSTRAP={"8.8.8.8","8.8.4.4"};
    interface Exchange { Call create(DirectRoutes.Route route,Request request)throws IOException; }
    private final Exchange exchange;
    GoogleDnsDiscovery(OkHttpClient trusted) {
        this((route,request)->GoogleHttp.direct(trusted,route).newBuilder()
            .callTimeout(1500,java.util.concurrent.TimeUnit.MILLISECONDS)
            .connectTimeout(1000,java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(1000,java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build().newCall(request));
    }
    GoogleDnsDiscovery(Exchange exchange){this.exchange=exchange;}
    Query start(String host)throws IOException {
        if(!DirectNet.googleHost(host))throw new IOException("Not a Google data hostname");
        String name=canonical(host);
        HttpUrl url=new HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("resolve")
            .addQueryParameter("name",name).addQueryParameter("type","A")
            // Do not forward a user subnet to authoritative DNS servers.
            .addQueryParameter("edns_client_subnet","0.0.0.0/0").build();
        Request request=new Request.Builder().url(url).header("Accept","application/dns-json").get().build();
        Query query=new Query();
        for(String ip:BOOTSTRAP)for(DirectRoutes.TlsMode tls:new DirectRoutes.TlsMode[]{DirectRoutes.TlsMode.NORMAL,DirectRoutes.TlsMode.NO_SNI}) {
            try {
                Call call=exchange.create(new DirectRoutes.Route(HOST,ip,tls,0),request);
                query.calls.add(call);
                call.enqueue(new Callback(){
                    @Override public void onFailure(Call ignored,IOException error){query.remaining.decrementAndGet();}
                    @Override public void onResponse(Call ignored,Response response) {
                        try(Response closed=response) {
                            if(response.code()!=200||response.body()==null)return;
                            String type=response.header("Content-Type","").split(";",2)[0].trim();
                            if(!type.equalsIgnoreCase("application/dns-json")&&!type.equalsIgnoreCase("application/json"))return;
                            List<String> found=parse(name,read(response.body().byteStream()));
                            synchronized(query){if(!query.closed)query.addresses.addAll(found);}
                        }catch(IOException ignoredError){/* Failed hints never become usable routes. */}
                        finally{query.remaining.decrementAndGet();}
                    }
                });
            }catch(IOException|RuntimeException unavailable){query.remaining.decrementAndGet();}
        }
        return query;
    }
    static final class Query implements AutoCloseable {
        final List<Call> calls=new ArrayList<>();
        final AtomicInteger remaining=new AtomicInteger(4);
        final LinkedHashSet<String> addresses=new LinkedHashSet<>();
        private boolean closed;
        boolean finished(){return remaining.get()==0;}
        synchronized List<String> addresses(){return new ArrayList<>(addresses);}
        @Override public void close(){synchronized(this){closed=true;}for(Call call:calls)call.cancel();}
    }
    private static String read(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;
        while((count=input.read(buffer))!=-1){if(bytes.size()+count>MAX_BYTES)throw new IOException("Oversized DNS response");bytes.write(buffer,0,count);}
        return bytes.toString("UTF-8");
    }
    static List<String> parse(String host,String json)throws IOException {
        if(json==null||json.length()>MAX_BYTES)throw new IOException("Oversized DNS response");
        try {
            String expected=canonical(host);
            JSONObject root=new JSONObject(json);
            if(root.getInt("Status")!=0||root.optBoolean("TC",false))return Collections.emptyList();
            JSONArray question=root.getJSONArray("Question");
            if(question.length()!=1||question.getJSONObject(0).getInt("type")!=1
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
                if(record.getInt("type")==1&&canonical(record.getString("name")).equals(expected)) {
                    String ip=record.getString("data");
                    if(DirectRoutes.publicIpv4(ip)&&ips.size()<16)ips.add(ip);
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
