package dev.gdirect.lite;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Call;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** GET-only adapter for the existing transfer code. DNS, TCP, TLS and cancellation use OkHttp. */
final class GoogleHttp {
    interface Clients { OkHttpClient get() throws IOException; }
    interface Guard { void check() throws IOException; }
    static OkHttpClient direct(OkHttpClient trusted, DirectRoutes.Route route) throws IOException {
        InetAddress address = InetAddress.getByName(route.ip); // Route contains a validated literal, never DNS.
        OkHttpClient.Builder builder = trusted.newBuilder()
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .dns(host -> {
                if (!host.equalsIgnoreCase(route.host)) throw new java.net.UnknownHostException("Unmapped direct host");
                return Collections.singletonList(address);
            });
        if (route.tls == DirectRoutes.TlsMode.NO_SNI) {
            builder.sslSocketFactory(new NoSniFactory(trusted.sslSocketFactory()), trusted.x509TrustManager())
                .connectionSpecs(Collections.singletonList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .supportsTlsExtensions(false).build()))
                .protocols(Collections.singletonList(Protocol.HTTP_1_1));
        }
        // The default OkHttp hostname verifier still checks route.host, including with NO_SNI.
        return builder.build();
    }

    static HttpsURLConnection open(URL url, Clients clients, Runnable failed) {
        return open(url,clients,failed, () -> { });
    }
    static HttpsURLConnection open(URL url, Clients clients, Runnable failed, Guard guard) {
        return new GetConnection(url, clients, failed,guard);
    }
    static final class GetConnection extends HttpsURLConnection {
        private final Clients clients;
        private final Runnable failed;
        private final Request.Builder request;
        private final Guard guard;
        private volatile Response response;
        private volatile Call call;
        private volatile boolean closed;
        GetConnection(URL url, Clients clients, Runnable failed, Guard guard) {
            super(url); this.clients = clients; this.failed = failed;
            this.guard = guard;
            request = new Request.Builder().url(url);
            setConnectTimeout(10000); setReadTimeout(20000);
        }
        @Override public void connect() throws IOException {
            if (closed) throw new IOException("连接已关闭");
            if (response != null) return;
            if (!"GET".equals(method) || doOutput) throw new IOException("Only GET is supported");
            // At most two attempts, and only before a response. No redirects or mutations are replayed.
            for (int attempt = 0; attempt < 2; attempt++) {
                OkHttpClient client = clients.get().newBuilder().connectTimeout(getConnectTimeout(), TimeUnit.MILLISECONDS)
                    .readTimeout(getReadTimeout(), TimeUnit.MILLISECONDS).build();
                guard.check();
                call = client.newCall(request.get().build());
                if (closed || Thread.currentThread().isInterrupted()) { call.cancel(); throw new java.io.InterruptedIOException("连接已取消"); }
                try {
                    response = call.execute(); connected = true; return;
                } catch (IOException error) {
                    failed.run();
                    if (attempt == 1 || Thread.currentThread().isInterrupted() || closed) throw error;
                }
            }
        }
        @Override public void disconnect() {
            closed = true;
            if (call != null) call.cancel();
            if (response != null) response.close();
        }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String key, String value) {
            if (connected) throw new IllegalStateException("Already connected");
            request.header(key, value);
        }
        @Override public int getResponseCode() throws IOException { connect(); return response.code(); }
        @Override public String getResponseMessage() throws IOException { connect(); return response.message(); }
        @Override public String getHeaderField(String name) {
            try { connect(); return name == null ? "HTTP/1.1 " + response.code() : response.header(name); }
            catch (IOException error) { return null; }
        }
        @Override public Map<String,List<String>> getHeaderFields() {
            try { connect(); return response.headers().toMultimap(); }
            catch (IOException error) { return Collections.emptyMap(); }
        }
        @Override public InputStream getInputStream() throws IOException {
            connect();
            if (response.code() >= 400) throw new IOException("HTTP " + response.code());
            return new java.io.FilterInputStream(response.body().byteStream()) {
                @Override public int read() throws IOException {
                    try { return in.read(); } catch (IOException error) { failed.run(); throw error; }
                }
                @Override public int read(byte[] data,int offset,int count) throws IOException {
                    try { return in.read(data,offset,count); } catch (IOException error) { failed.run(); throw error; }
                }
            };
        }
        @Override public InputStream getErrorStream() {
            try { connect(); return response.code() >= 400 ? response.body().byteStream() : null; }
            catch (IOException error) { return null; }
        }
        @Override public String getCipherSuite() { return response.handshake().cipherSuite().javaName(); }
        @Override public Certificate[] getLocalCertificates() { return response.handshake().localCertificates().toArray(new Certificate[0]); }
        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
            return response.handshake().peerCertificates().toArray(new Certificate[0]);
        }
    }

    /** Changes the TLS extension only. Chain trust and real-URL hostname validation stay enabled. */
    private static final class NoSniFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        NoSniFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket configure(Socket socket) {
            SSLSocket tls = (SSLSocket) socket;
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setServerNames(Collections.emptyList());
            tls.setSSLParameters(parameters);
            return tls;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return configure(delegate.createSocket()); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean close) throws IOException { return configure(delegate.createSocket(s,h,p,close)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return configure(delegate.createSocket(h,p)); }
        @Override public Socket createSocket(String h, int p, InetAddress local, int lp) throws IOException { return configure(delegate.createSocket(h,p,local,lp)); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return configure(delegate.createSocket(h,p)); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress local, int lp) throws IOException { return configure(delegate.createSocket(h,p,local,lp)); }
    }
    private GoogleHttp() { }
}
