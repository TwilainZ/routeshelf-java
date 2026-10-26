package dev.gdirect.lite;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Public Google metadata only; no file URL, account, Authorization header, or third-party feed. */
final class GoogleRangeCatalog {
    interface Store {String read()throws IOException;void write(String value)throws IOException;}
    interface Fetch {String get(String name)throws IOException;}
    private final Store store;private final Fetch fetch;
    private GoogleAddressSpace current;private long checked,lastRefreshFailure,deferredEpoch=-1;
    private final java.util.concurrent.atomic.AtomicLong resetEpoch=new java.util.concurrent.atomic.AtomicLong();
    GoogleRangeCatalog(Store store,Fetch fetch){this.store=store;this.fetch=fetch;}
    synchronized GoogleAddressSpace load()throws IOException {
        long now=System.currentTimeMillis();
        if(current==null) {
            try{JSONObject saved=new JSONObject(store.read());current=GoogleAddressSpace.parse(saved.getString("google"),saved.getString("cloud"));checked=saved.getLong("checked");}
            catch(Exception ignored){current=null;}
        }
        if(current!=null&&checked<=now&&now-checked<TimeUnit.DAYS.toMillis(7))return current;
        if(current!=null&&checked<=now&&now-checked<TimeUnit.DAYS.toMillis(30)&&deferredEpoch==resetEpoch.get()&&System.nanoTime()-lastRefreshFailure<TimeUnit.HOURS.toNanos(1))return current;
        long updateEpoch=resetEpoch.get();
        try {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("地址更新已取消");
            String google=fetch.get("goog.json");
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("地址更新已取消");
            String cloud=fetch.get("cloud.json");
            // A partial/tiny response must never replace an existing full catalog.
            if(GoogleAddressSpace.prefixCount(google)<20||GoogleAddressSpace.prefixCount(cloud)<20)
                throw new IOException("Google 地址列表明显不完整");
            GoogleAddressSpace updated=GoogleAddressSpace.parse(google,cloud);
            String encoded=new JSONObject().put("google",google).put("cloud",cloud).put("checked",now).toString();
            store.write(encoded);current=updated;checked=now;deferredEpoch=-1;return current;
        }catch(Exception error) {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("地址更新已取消");
            if(current!=null&&checked<=now&&now-checked<TimeUnit.DAYS.toMillis(30)){lastRefreshFailure=System.nanoTime();deferredEpoch=updateEpoch;return current;}
            throw new IOException("无法取得 Google 官方地址范围，请稍后重新检测或选择系统网络",error);
        }
    }
    void retryRefresh(){resetEpoch.incrementAndGet();}
    static String download(OkHttpClient trusted,String name)throws IOException {
        if(!name.equals("goog.json")&&!name.equals("cloud.json"))throw new IOException("Invalid Google range filename");
        OkHttpClient client=trusted.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(2500,TimeUnit.MILLISECONDS).build();
        try(Response response=client.newCall(new Request.Builder().url("https://www.gstatic.com/ipranges/"+name).get().build()).execute()) {
            if(response.code()!=200||response.body()==null)throw new IOException("Google 地址范围下载失败");
            try(java.io.InputStream in=response.body().byteStream();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(bytes.size()+n>1024*1024)throw new IOException("Google 地址列表过大");bytes.write(buffer,0,n);}
                return bytes.toString("UTF-8");
            }
        }
    }
}
