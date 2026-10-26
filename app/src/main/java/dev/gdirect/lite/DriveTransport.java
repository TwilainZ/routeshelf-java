package dev.gdirect.lite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Authenticated requests have one exact origin and never follow redirects. */
final class DriveTransport {
    interface Connections { HttpsURLConnection open(URL url) throws IOException; }
    private final DriveSession.Lease lease;
    private final Connections connections;
    private final Runnable unauthorized;

    DriveTransport(DriveSession.Lease lease, Connections connections, Runnable unauthorized) {
        this.lease = lease; this.connections = connections; this.unauthorized = unauthorized;
    }

    static void requireApiUrl(URL url) throws IOException {
        if (!"https".equals(url.getProtocol()) || !"www.googleapis.com".equals(url.getHost())
            || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null
            || url.getRef() != null || !url.getPath().matches("/drive/v3/files(?:/[A-Za-z0-9_-]{1,200})?"))
            throw new IOException("已阻止向 Google Drive API 以外的地址发送授权信息");
    }

    HttpsURLConnection open(URL url) throws IOException {
        requireApiUrl(url);
        String token = lease.token();
        HttpsURLConnection c = connections.open(url);
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "GDirectLite/0.2 Android");
            int code = c.getResponseCode();
            lease.requireCurrent();
            if (code == 401) {
                unauthorized.run();
                throw new DriveSession.AuthorizationRequired();
            }
            if (code == 403) throw new IOException("Google 拒绝访问：请检查文件下载权限、Drive API 配置或配额");
            if (code == 404) throw new IOException("文件不存在，或当前 Google 账号无权访问");
            if (code == 429 || code == 500 || code == 502 || code == 503 || code == 504)
                throw new ReadRetry.Unavailable(code,c.getHeaderField("Retry-After"));
            if (code >= 300 && code < 400) throw new IOException("私人文件请求返回跳转；为保护令牌已停止，请稍后重试");
            if (code != 200) throw new IOException("Google Drive 请求失败（HTTP " + code + "），请稍后重试");
            return c;
        } catch (IOException | RuntimeException error) { c.disconnect(); throw error; }
    }

    String json(URL url) throws IOException {
        return ReadRetry.run(() -> jsonOnce(url),lease::requireCurrent);
    }

    private String jsonOnce(URL url) throws IOException {
        HttpsURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                lease.requireCurrent();
                if (out.size() + n > 1024 * 1024) throw new IOException("Google 返回的文件列表过大，请缩小搜索范围");
                out.write(buffer, 0, n);
            }
            lease.requireCurrent();
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }
}
