package dev.gdirect.lite.network;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small, attributed public-report hints, never a verified route or a full official range snapshot. */
public final class GoogleBootstrap {
    public final String reportDate,network,source;
    public final GoogleAddressSpace subset;
    private final List<String> addresses;
    private GoogleBootstrap(String date,String network,String source,GoogleAddressSpace subset,List<String> addresses) {
        this.reportDate=date;this.network=network;this.source=source;this.subset=subset;
        this.addresses=Collections.unmodifiableList(addresses);
    }
    public static GoogleBootstrap bundled()throws IOException {
        try(InputStream in=GoogleBootstrap.class.getResourceAsStream("/google-bootstrap.json")) {
            if(in==null)throw new IOException("缺少内置 Google 候选");
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>16384)throw new IOException("内置候选过大");out.write(buffer,0,n);}
            return parse(out.toString("UTF-8"));
        }
    }
    public static GoogleBootstrap parse(String json)throws IOException {
        if(json==null||json.length()>16384)throw new IOException("内置候选大小无效");
        try {
            JSONObject root=new JSONObject(json);
            if(root.getInt("schema")!=1)throw new IOException("内置候选版本无效");
            String date=root.getString("reportDate"),network=root.getString("network"),source=root.getString("source");
            java.time.LocalDate.parse(date);
            if(!source.equals("https://mizore.blog/posts/gdirect/")||network.length()>80)throw new IOException("候选来源无效");
            JSONArray prefixes=root.getJSONArray("prefixes"),items=root.getJSONArray("candidates");
            List<String> blocks=new ArrayList<>();for(int i=0;i<prefixes.length();i++)blocks.add(prefixes.getString(i));
            GoogleAddressSpace subset=GoogleAddressSpace.fromBootstrapPrefixes(blocks);
            if(items.length()==0||items.length()>24)throw new IOException("内置候选数量无效");
            LinkedHashSet<String> unique=new LinkedHashSet<>();
            for(int i=0;i<items.length();i++) {
                String ip=items.getString(i);
                if(!subset.contains(ip)||!unique.add(ip))throw new IOException("内置候选越界或重复");
            }
            return new GoogleBootstrap(date,network,source,subset,new ArrayList<>(unique));
        }catch(JSONException|java.time.DateTimeException error){throw new IOException("内置候选格式无效",error);}
    }
    public List<String> candidates(GoogleAddressSpace allowed) {
        List<String> result=new ArrayList<>();for(String ip:addresses)if(allowed.contains(ip))result.add(ip);return result;
    }
    public List<String> candidates(GoogleAddressSpace allowed,List<String> history) {
        LinkedHashSet<String> ordered=new LinkedHashSet<>();
        // Known addresses remain hints: revalidate TLS/service in the current network.
        // Reserve space for all packaged hints, even with large or polluted history.
        for(String ip:history)if(allowed.contains(ip)&&DirectRoutes.automaticAddress(ip)) {
            ordered.add(ip);if(ordered.size()==2)break;
        }
        ordered.addAll(candidates(allowed));return new ArrayList<>(ordered);
    }
    public static final class Scope {
        public final GoogleAddressSpace space;public final boolean bundled;
        public Scope(GoogleAddressSpace space,boolean bundled){this.space=space;this.bundled=bundled;}
    }
    public Scope scope(GoogleRangeCatalog catalog)throws IOException {
        try{return new Scope(catalog.load(),false);}
        catch(IOException unavailable) {
            if(unavailable instanceof java.io.InterruptedIOException||Thread.currentThread().isInterrupted())throw unavailable;
            return new Scope(subset,true);
        }
    }
    public String description() {
        return "内置 "+addresses.size()+" 个待验证候选：\n"+String.join("\n",addresses)
            +"\n\n网段报告："+reportDate+" · "+network+"\n"+source
            +"\n\n报告针对网段；上述具体地址为独立选取的候选，不保证当前可用。应用会在当前网络验证 Google 证书和服务，成功后才发送文件请求。这组范围在报告中归为 Google 自有前端，与已知第三方中转分开；它不是完整官方地址快照。";
    }
}
