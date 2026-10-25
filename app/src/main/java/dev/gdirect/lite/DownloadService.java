package dev.gdirect.lite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.content.pm.ServiceInfo;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;

/** Foreground download service; anonymous/public Google Drive downloads only. */
public final class DownloadService extends Service {
    static final String ACTION_START = "dev.gdirect.lite.START";
    static final String ACTION_STOP = "dev.gdirect.lite.STOP";
    static final String EXTRA_URL = "url";
    static final String EXTRA_IP = "ip";
    static final String EXTRA_THREADS = "threads";
    private static final String CHANNEL = "gdirect_downloads";
    private static final int NOTIFY_ID = 1407;
    private static final long CHUNK = 4L * 1024L * 1024L;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong bytes = new AtomicLong(0);
    private volatile Thread thread;
    private volatile long lastNotification;
    private volatile long startedAt;

    static final class Snapshot {
        final String text;
        final long done, total, bytesPerSecond;
        final boolean active, finished;
        final String savedUri, mime;
        Snapshot(String text, long done, long total, long speed, boolean active, boolean finished, String savedUri, String mime) {
            this.text = text; this.done = done; this.total = total; this.bytesPerSecond = speed;
            this.active = active; this.finished = finished; this.savedUri = savedUri; this.mime = mime;
        }
    }
    static volatile Snapshot snapshot = new Snapshot("可以开始下载公开的 Google Drive 文件。", 0, -1, 0, false, false, null, null);

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "GDirect Lite 下载", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示文件传输进度");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            cancelled.set(true);
            status("正在取消…", snapshot.total);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        if (thread != null && thread.isAlive()) {
            status("已有下载在进行，请先等待或取消。", snapshot.total);
            return START_NOT_STICKY;
        }
        String link = intent.getStringExtra(EXTRA_URL);
        String manualIp = intent.getStringExtra(EXTRA_IP);
        int workers = Math.max(1, Math.min(8, intent.getIntExtra(EXTRA_THREADS, 4)));
        cancelled.set(false);
        bytes.set(0);
        startedAt = SystemClock.elapsedRealtime();
        snapshot = new Snapshot("准备下载…", 0, -1, 0, true, false, null, null);
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFY_ID, n);
        thread = new Thread(() -> {
            try {
                String mimeAndUri = download(link, manualIp, workers);
                String[] parts = mimeAndUri.split("\\|", 2);
                snapshot = new Snapshot("下载完成，文件已保存到 下载/GDirect Lite", bytes.get(), bytes.get(), 0,
                    false, true, parts[1], parts[0]);
            } catch (Exception ex) {
                snapshot = new Snapshot((cancelled.get() ? "已取消；保留已完成的分块，重试可续传。" : "下载失败：" + explain(ex)),
                    bytes.get(), snapshot.total, 0, false, false, null, null);
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "gdirect-worker");
        thread.start();
        return START_NOT_STICKY;
    }

    private static String explain(Throwable error) {
        String m = error.getMessage();
        if (m == null || m.isBlank()) m = error.getClass().getSimpleName();
        return m.length() > 180 ? m.substring(0, 180) : m;
    }
    private void requireNotCancelled() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("download canceled");
    }
    private void status(String message, long total) {
        long elapsed = Math.max(1000, SystemClock.elapsedRealtime() - startedAt);
        long done = bytes.get();
        Snapshot old = snapshot;
        snapshot = new Snapshot(message, done, total, done * 1000L / elapsed, true, false,
            old.savedUri, old.mime);
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotification > 1200) {
            lastNotification = now;
            getSystemService(NotificationManager.class).notify(NOTIFY_ID, notification());
        }
    }
    private Notification notification() {
        Snapshot s = snapshot;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GDirect Lite")
            .setContentText(s.text)
            .setContentIntent(pending)
            .setOngoing(s.active)
            .setOnlyAlertOnce(true);
        if (s.total > 0) b.setProgress(100, (int)Math.min(100, s.done * 100 / s.total), false);
        else b.setProgress(100, 0, true);
        return b.build();
    }

    private String download(String link, String ipText, int workers) throws Exception {
        String ip = DirectNet.parseIp(ipText);
        URL start = DirectNet.normalizeSharedLink(link);
        status("正在检查文件和 HTTPS 连接…", -1);
        DirectNet.Probe probe = DirectNet.probe(start, ip);
        requireNotCancelled();
        File folder = new File(getFilesDir(), "partial");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("无法创建缓存目录");
        String key = hash(start.toExternalForm());
        File file = new File(folder, key + ".part");
        File meta = new File(folder, key + ".properties");
        if (probe.ranges && probe.size > 0) {
            downloadChunks(file, meta, probe, link, ip, workers);
        } else {
            downloadOne(file, meta, probe, ip);
        }
        requireNotCancelled();
        status("下载完成，正在保存到系统下载目录…", probe.size);
        String mime = guessMime(probe.filename);
        Uri uri = publish(file, probe.filename, mime);
        if (!file.delete()) file.deleteOnExit();
        if (meta.exists()) meta.delete();
        return mime + "|" + uri;
    }

    private void downloadOne(File file, File meta, DirectNet.Probe probe, String ip) throws Exception {
        if (meta.exists()) meta.delete();
        bytes.set(0);
        HttpsURLConnection c = DirectNet.open(probe.finalUrl, ip, null);
        try {
            if (c.getResponseCode() != 200) throw new IOException("单连接下载返回 HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file, false)) {
                byte[] buf = new byte[64*1024]; int read;
                while ((read = in.read(buf)) != -1) {
                    requireNotCancelled();
                    out.write(buf, 0, read);
                    bytes.addAndGet(read);
                    status("正在下载（服务器未提供分块续传）", probe.size);
                }
            }
        } finally { c.disconnect(); }
        if (probe.size > 0 && bytes.get() != probe.size) throw new IOException("文件大小不一致，下载不完整");
    }

    private void downloadChunks(File file, File meta, DirectNet.Probe probe,
                                String link, String ip, int workers) throws Exception {
        int count = (int)((probe.size + CHUNK - 1L) / CHUNK);
        if (count <= 0 || count > 100000) throw new IOException("文件过大，暂不支持此文件");
        BitSet complete = new BitSet(count);
        if (file.exists() && meta.exists()) {
            try (FileInputStream stream = new FileInputStream(meta)) {
                Properties props = new Properties(); props.load(stream);
                if (props.getProperty("link", "").equals(link)
                    && Long.parseLong(props.getProperty("size", "-1")) == probe.size
                    && file.length() == probe.size) {
                    complete = BitSet.valueOf(Base64.getDecoder().decode(props.getProperty("completed", "")));
                    complete.clear(count, Math.max(count, complete.length()));
                }
            } catch (Exception ignored) { complete.clear(); }
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) { raf.setLength(probe.size); }
        final BitSet done = complete;
        long resume = 0;
        for (int i = done.nextSetBit(0); i >= 0 && i < count; i = done.nextSetBit(i+1))
            resume += Math.min(CHUNK, probe.size - i*CHUNK);
        bytes.set(resume);
        final Object metaLock = new Object();
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < count; i++) if (!done.get(i)) pending.add(i);
        if (pending.isEmpty()) return;
        status("分块下载中，已恢复 " + (resume / (1024*1024)) + " MiB", probe.size);
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, pending.size()));
        List<Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < Math.min(workers, pending.size()); worker++) {
            futures.add(pool.submit(() -> {
                while (true) {
                    requireNotCancelled();
                    int n = next.getAndIncrement();
                    if (n >= pending.size()) return null;
                    int chunk = pending.get(n);
                    long from = chunk * CHUNK;
                    long to = Math.min(probe.size - 1, from + CHUNK - 1);
                    downloadChunk(file, probe.finalUrl, ip, from, to, probe.size);
                    synchronized (metaLock) {
                        done.set(chunk);
                        saveMeta(meta, link, probe.size, done);
                    }
                    status("多连接下载中（" + workers + " 线程）", probe.size);
                }
            }));
        }
        try {
            for (Future<?> future : futures) future.get();
        } catch (Exception ex) {
            pool.shutdownNow();
            throw new IOException("分块下载中断：" + explain(ex.getCause() == null ? ex : ex.getCause()), ex);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(4, TimeUnit.SECONDS);
        }
        if (done.cardinality() != count) throw new IOException("分块未全部完成");
    }

    private void downloadChunk(File file, URL url, String ip, long start, long end, long size) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            long received = 0;
            HttpsURLConnection c = null;
            try {
                requireNotCancelled();
                c = DirectNet.open(url, ip, "bytes=" + start + "-" + end);
                if (c.getResponseCode() != 206) throw new IOException("服务器未返回 HTTP 206，无法安全拼接分块");
                String header = c.getHeaderField("Content-Range");
                if (header == null || !header.equals("bytes " + start + "-" + end + "/" + size))
                    throw new IOException("文件 Content-Range 不匹配");
                try (InputStream in = c.getInputStream(); RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                    out.seek(start);
                    byte[] buffer = new byte[64*1024];
                    long remaining = end - start + 1;
                    while (remaining > 0) {
                        requireNotCancelled();
                        int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                        if (n < 0) throw new IOException("文件提前结束");
                        out.write(buffer, 0, n);
                        received += n;
                        remaining -= n;
                        bytes.addAndGet(n);
                        status("多连接下载中…", size);
                    }
                }
                return;
            } catch (IOException e) {
                bytes.addAndGet(-received);
                if (cancelled.get() || attempt == 1) throw e;
            } finally { if (c != null) c.disconnect(); }
        }
    }

    private void saveMeta(File meta, String link, long size, BitSet bits) throws IOException {
        Properties p = new Properties();
        p.setProperty("link", link);
        p.setProperty("size", Long.toString(size));
        p.setProperty("completed", Base64.getEncoder().encodeToString(bits.toByteArray()));
        File temp = new File(meta.getParentFile(), meta.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { p.store(out, "GDirectLite resumable chunks"); }
        Files.move(temp.toPath(), meta.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private Uri publish(File source, String displayName, String mime) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GDirect Lite");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法保存到系统下载目录");
        boolean ok = false;
        try {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("系统拒绝写入文件");
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) != -1) {
                    requireNotCancelled();
                    out.write(buf, 0, n);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            ok = true;
            return uri;
        } finally { if (!ok) getContentResolver().delete(uri, null, null); }
    }

    static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String result = MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix);
        return result == null ? "application/octet-stream" : result;
    }
    private static String hash(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 12; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
        return hex.toString();
    }
    @Override public void onDestroy() {
        cancelled.set(true);
        super.onDestroy();
    }
}
