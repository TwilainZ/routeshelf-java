package dev.gdirect.lite;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(26, 36, 61);
    private static final int MUTED = Color.rgb(103, 115, 138);
    private static final int BLUE = Color.rgb(49, 91, 215);
    private static final int PALE = Color.rgb(242, 246, 253);
    private EditText linkInput, ipInput;
    private Spinner threads;
    private TextView status, progressLabel;
    private ProgressBar progress;
    private Button scan, start, stop, open;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadService.Snapshot last;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            showSnapshot();
            handler.postDelayed(this, 500);
        }
    };

    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density * value + 0.5f); }
    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(17));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private TextView text(String content, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(content); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private LinearLayout vertical() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }
    private void space(LinearLayout target, int height) {
        View v = new View(this);
        target.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
    }
    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : BLUE);
        b.setBackground(rounded(primary ? BLUE : Color.WHITE, primary ? 0 : Color.rgb(214,223,244)));
        b.setMinHeight(dp(52));
        return b;
    }
    private void heading(LinearLayout card, String title, String description) {
        card.addView(text(title, 17, INK, true));
        space(card, 5);
        card.addView(text(description, 13, MUTED, false));
        space(card, 12);
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = vertical();
        card.setPadding(dp(17), dp(18), dp(17), dp(18));
        card.setBackground(rounded(Color.WHITE, Color.rgb(229,234,245)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin = dp(14);
        parent.addView(card, p);
        return card;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(PALE);
        getWindow().setNavigationBarColor(PALE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PALE);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(34), dp(20), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        TextView mark = text("G  /  DIRECT", 14, BLUE, true);
        mark.setLetterSpacing(.15f);
        root.addView(mark);
        space(root, 10);
        root.addView(text("Google Drive 直连下载", 27, INK, true));
        space(root, 7);
        root.addView(text("Android Lite  ·  无需 Root  ·  仅公开文件", 13, MUTED, false));
        space(root, 22);

        LinearLayout download = card(root);
        heading(download, "文件链接", "粘贴公开分享的 Google Drive 文件链接，不需要登录账号。");
        linkInput = new EditText(this);
        linkInput.setTextSize(14);
        linkInput.setTextColor(INK);
        linkInput.setHint("https://drive.google.com/file/d/…/view");
        linkInput.setHintTextColor(MUTED);
        linkInput.setMinLines(2);
        linkInput.setMaxLines(4);
        linkInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        linkInput.setBackground(rounded(PALE, Color.rgb(225,230,241)));
        linkInput.setPadding(dp(13), dp(14), dp(13), dp(14));
        download.addView(linkInput, new LinearLayout.LayoutParams(-1,-2));
        space(download, 10);
        Button paste = button("粘贴剪贴板", false);
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence clip = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                linkInput.setText(clip == null ? "" : clip.toString().trim());
            }
        });
        download.addView(paste, new LinearLayout.LayoutParams(-1,dp(49)));

        LinearLayout network = card(root);
        heading(network, "直连网络", "自动扫描支持 HTTPS 校验的 Google IP，也可以留空使用系统网络。");
        ipInput = new EditText(this);
        ipInput.setSingleLine(true);
        ipInput.setTextSize(14);
        ipInput.setTextColor(INK);
        ipInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        ipInput.setHint("Google IPv4（留空 = 系统网络）");
        ipInput.setHintTextColor(MUTED);
        ipInput.setBackground(rounded(PALE, Color.rgb(225,230,241)));
        ipInput.setPadding(dp(13), dp(14), dp(13), dp(14));
        network.addView(ipInput, new LinearLayout.LayoutParams(-1,-2));
        space(network, 10);
        scan = button("扫描可用 IP", false);
        network.addView(scan, new LinearLayout.LayoutParams(-1,dp(49)));
        scan.setOnClickListener(v -> scanIps());
        space(network, 13);
        network.addView(text("并行连接数", 13, INK, true));
        threads = new Spinner(this);
        String[] items = {"1 条连接（稳妥）", "4 条连接（推荐）", "8 条连接（实验）"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        threads.setAdapter(adapter);
        threads.setSelection(1);
        network.addView(threads);

        LinearLayout actions = card(root);
        heading(actions, "下载进度", "大文件采用 HTTP Range 分块下载；取消后重试可继续已完成的分块。");
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        actions.addView(progress, new LinearLayout.LayoutParams(-1,dp(9)));
        space(actions, 10);
        progressLabel = text("等待下载", 13, MUTED, false);
        actions.addView(progressLabel);
        space(actions, 13);
        status = text("等待任务…", 14, INK, false);
        actions.addView(status);
        space(actions, 15);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        start = button("开始下载", true);
        stop = button("取消", false);
        LinearLayout.LayoutParams mainP = new LinearLayout.LayoutParams(0,dp(52),2);
        mainP.rightMargin = dp(10);
        row.addView(start, mainP);
        row.addView(stop, new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(row);
        space(actions, 10);
        open = button("打开下载的文件", false);
        open.setVisibility(View.GONE);
        actions.addView(open, new LinearLayout.LayoutParams(-1,dp(48)));
        start.setOnClickListener(v -> beginDownload());
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_STOP);
            startService(i);
        });
        open.setOnClickListener(v -> openFile());
        space(root, 1);
        root.addView(text("实验性开源原型 · 保持 HTTPS 证书校验 · 不索取 Google 登录权限", 12, MUTED, false));
        linkInput.setText(getPreferences(MODE_PRIVATE).getString("link", ""));
        ipInput.setText(getPreferences(MODE_PRIVATE).getString("ip", ""));
        takeSharedIntent(getIntent());
        handler.post(ticker);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeSharedIntent(intent);
    }
    private void takeSharedIntent(Intent intent) {
        if (intent == null || linkInput == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("https://(?:drive\\.google\\.com|drive\\.usercontent\\.google\\.com)/\\S+").matcher(shared);
                if (m.find()) linkInput.setText(m.group().replaceAll("[),。！]+$", ""));
            }
        }
    }
    private void scanIps() {
        scan.setEnabled(false);
        status.setText("正在测试 Google IP 的 TLS 连接…");
        new Thread(() -> {
            try {
                List<DirectNet.ScanResult> results = DirectNet.scan();
                runOnUiThread(() -> {
                    scan.setEnabled(true);
                    if (results.isEmpty()) {
                        status.setText("没有找到直连 IP，可清空 IP 后使用系统网络。登录/挂载功能尚不支持。");
                    } else {
                        ipInput.setText(results.get(0).ip);
                        StringBuilder s = new StringBuilder("已选延迟最低的可用 IP：\n");
                        for (int i = 0; i < Math.min(5, results.size()); i++) s.append(results.get(i)).append("\n");
                        status.setText(s.toString().trim());
                    }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> { scan.setEnabled(true); status.setText("扫描失败：" + ex.getMessage()); });
            }
        }, "gdirect-scan").start();
    }
    private void beginDownload() {
        if (DownloadService.snapshot.active) { Toast.makeText(this, "已有下载正在进行", Toast.LENGTH_SHORT).show(); return; }
        String link = linkInput.getText().toString().trim();
        String ip = ipInput.getText().toString().trim();
        try { DirectNet.normalizeSharedLink(link); DirectNet.parseIp(ip); }
        catch (Exception ex) { status.setText("输入有误：" + ex.getMessage()); return; }
        getPreferences(MODE_PRIVATE).edit().putString("link",link).putString("ip",ip).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        int workers = threads.getSelectedItemPosition() == 0 ? 1 : threads.getSelectedItemPosition() == 1 ? 4 : 8;
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(DownloadService.ACTION_START);
        intent.putExtra(DownloadService.EXTRA_URL, link);
        intent.putExtra(DownloadService.EXTRA_IP, ip);
        intent.putExtra(DownloadService.EXTRA_THREADS, workers);
        try { startForegroundService(intent); }
        catch (Exception ex) { status.setText("无法启动下载服务：" + ex.getMessage()); }
    }
    private void showSnapshot() {
        DownloadService.Snapshot s = DownloadService.snapshot;
        if (last == s) return;
        last = s;
        status.setText(s.text);
        start.setEnabled(!s.active);
        stop.setEnabled(s.active);
        open.setVisibility(s.savedUri != null ? View.VISIBLE : View.GONE);
        if (s.total > 0) {
            progress.setProgress((int)Math.min(1000, s.done * 1000 / s.total));
            progressLabel.setText(String.format(Locale.ROOT, "%s / %s · %s/s · %d%%",
                units(s.done), units(s.total), units(s.bytesPerSecond),
                Math.min(100, s.done * 100 / s.total)));
        } else {
            progress.setProgress(0);
            progressLabel.setText(s.active ? units(s.done) + " · " + units(s.bytesPerSecond) + "/s" : "等待下载");
        }
    }
    private String units(long n) {
        double d = n; String[] suffix = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (d >= 1024 && unit < suffix.length-1) { d /= 1024; unit++; }
        return String.format(Locale.ROOT, unit == 0 ? "%.0f %s" : "%.1f %s", d, suffix[unit]);
    }
    private void openFile() {
        DownloadService.Snapshot s = DownloadService.snapshot;
        if (s.savedUri == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(s.savedUri), s.mime == null ? "*/*" : s.mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "打开文件")); }
        catch (Exception ex) { Toast.makeText(this, "可在 下载/GDirect Lite 文件夹找到它", Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
}
