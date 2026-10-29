package dev.gdirect.lite;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    static final String EXTRA_SETTINGS="show_network_settings", EXTRA_TRANSFER="show_transfer";
    private int INK,MUTED,BLUE,PALE,SURFACE,OUTLINE;
    private LinearLayout manualPanel;
    private ScrollView page;
    private View transferSection;
    private EditText linkInput, ipInput;
    private TextView status, progressLabel;
    private ProgressBar progress;
    private Button start, stop, open;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadService.Snapshot last;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            showSnapshot();
            start.setEnabled(!DownloadService.snapshot.active);
            handler.postDelayed(this, 500);
        }
    };

    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density * value + 0.5f); }
    private GradientDrawable rounded(int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(16));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private TextView text(String content, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(content); t.setTextSize(Math.max(14,sp)); t.setTextColor(color);
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
        MaterialButton b = primary ? new MaterialButton(this)
            : new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label); b.setAllCaps(false); b.setMinHeight(dp(48));
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
        card.setBackground(rounded(SURFACE, 0));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin = dp(14);
        parent.addView(card, p);
        return card;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(savedInstanceState==null&&Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            startActivity(new Intent(this,DriveActivity.class));finish();return;
        }
        INK=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurface,Color.BLACK);
        MUTED=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurfaceVariant,INK);
        BLUE=MaterialColors.getColor(this,androidx.appcompat.R.attr.colorPrimary,INK);
        PALE=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface,Color.WHITE);
        SURFACE=MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurfaceContainer,PALE);
        OUTLINE=MaterialColors.getColor(this,com.google.android.material.R.attr.colorOutlineVariant,MUTED);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        page=new ScrollView(this);ScrollView scroll=page;
        scroll.setFillViewport(true);scroll.setBackgroundColor(PALE);
        LinearLayout root=vertical();scroll.addView(root);setContentView(scroll);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=Build.VERSION.SDK_INT>=30
                ?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime())
                :insets.getSystemWindowInsets();
            int side=dp(20+Math.max(0,(getResources().getConfiguration().screenWidthDp-720)/2));
            view.setPadding(side+bars.left,dp(16)+bars.top,side+bars.right,dp(16)+bars.bottom);return insets;
        });root.requestApplyInsets();

        Button back=button("返回云盘",false);back.setOnClickListener(v->finish());root.addView(back);
        root.addView(text("公开链接与传输", 24, INK, true));
        space(root, 7);

        space(root, 22);

        LinearLayout download = card(root);
        heading(download, "文件链接", "粘贴公开分享的 Google Drive 文件链接，不需要登录账号。");
        linkInput = new EditText(this);
        linkInput.setTextSize(16);
        linkInput.setTextColor(INK);
        linkInput.setHint("https://drive.google.com/file/d/…/view");
        linkInput.setHintTextColor(MUTED);
        linkInput.setMinLines(2);
        linkInput.setMaxLines(4);
        linkInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        linkInput.setBackground(rounded(PALE, OUTLINE));
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
        download.addView(paste, new LinearLayout.LayoutParams(-1,-2));

        Button settings = button(getString(R.string.settings_title),false);
        settings.setOnClickListener(v -> startActivity(new Intent(this,SettingsActivity.class)));
        root.addView(settings,new LinearLayout.LayoutParams(-1,-2));
        // A one-transfer override belongs with the public-link task, not global routing.
        Button advanced = button("指定本次公开下载 IP（高级）",false);download.addView(advanced);
        manualPanel=vertical();manualPanel.setVisibility(View.GONE);download.addView(manualPanel);
        ipInput=new EditText(this);ipInput.setSingleLine(true);ipInput.setHint("Google 公网 IPv4；留空自动连接");
        ipInput.setContentDescription("本次公开下载 IP");ipInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        manualPanel.addView(ipInput,new LinearLayout.LayoutParams(-1,-2));
        manualPanel.addView(text("只影响本次公开下载，仍会核对 Google 地址范围与证书。",14,MUTED,false));
        advanced.setOnClickListener(v->{boolean show=manualPanel.getVisibility()!=View.VISIBLE;manualPanel.setVisibility(show?View.VISIBLE:View.GONE);if(!show)ipInput.setText("");});

        LinearLayout actions = card(root);transferSection=actions;
        heading(actions, "文件传输", "查看当前上传或下载任务，完成后可打开下载文件。");
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
        start=button("开始下载",true);download.addView(start,new LinearLayout.LayoutParams(-1,-2));
        stop=button("取消当前传输",false);actions.addView(stop,new LinearLayout.LayoutParams(-1,-2));
        space(actions, 10);
        open = button("打开下载的文件", false);
        open.setVisibility(View.GONE);
        actions.addView(open, new LinearLayout.LayoutParams(-1,-2));
        start.setOnClickListener(v -> beginDownload());
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_STOP);
            startService(i);
        });
        open.setOnClickListener(v -> openFile());
        space(root, 1);
        root.addView(text("已下载文件保存在 下载/GDirect Lite", 12, MUTED, false));
        // Put the task before optional diagnostics/settings in the reading and focus order.
        root.removeView(actions);root.addView(actions,root.indexOfChild(settings));
        linkInput.setText(savedInstanceState==null?getPreferences(MODE_PRIVATE).getString("link",""):savedInstanceState.getString("link_draft",""));
        if(savedInstanceState!=null&&savedInstanceState.getBoolean("manual_expanded")) {
            manualPanel.setVisibility(View.VISIBLE);ipInput.setText(savedInstanceState.getString("manual_ip",""));
        }
        // Do not carry an old scanner-selected IP into the automatic routing workflow.
        if(savedInstanceState==null)takeSharedIntent(getIntent());
        showRequestedSection(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeSharedIntent(intent);showRequestedSection(intent);
    }
    private void showRequestedSection(Intent intent) {
        if(intent==null)return;
        if(intent.getBooleanExtra(EXTRA_SETTINGS,false)) {
            startActivity(new Intent(this,SettingsActivity.class));intent.removeExtra(EXTRA_SETTINGS);
        }else if(intent.getBooleanExtra(EXTRA_TRANSFER,false))page.post(()->page.smoothScrollTo(0,transferSection.getTop()));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if(linkInput==null){super.onSaveInstanceState(state);return;}
        state.putString("link_draft",linkInput.getText().toString());
        state.putBoolean("manual_expanded",manualPanel.getVisibility()==View.VISIBLE);
        state.putString("manual_ip",ipInput.getText().toString());
        super.onSaveInstanceState(state);
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
    private void beginDownload() {
        if (DownloadService.snapshot.active) { Toast.makeText(this, "已有下载正在进行", Toast.LENGTH_SHORT).show(); return; }
        String link = linkInput.getText().toString().trim();
        String ip = ipInput.getText().toString().trim();
        linkInput.setError(null);ipInput.setError(null);
        try { DirectNet.normalizeSharedLink(link); }
        catch(Exception ex){linkInput.setError(ex.getMessage());linkInput.requestFocus();return;}
        try { DirectNet.parseIp(ip); }
        catch(Exception ex){manualPanel.setVisibility(View.VISIBLE);ipInput.setError(ex.getMessage());ipInput.requestFocus();return;}
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(linkInput.getWindowToken(),0);
        getPreferences(MODE_PRIVATE).edit().putString("link",link).putString("ip",ip).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        int workers = getSharedPreferences("transfer-settings",MODE_PRIVATE).getInt("workers",4);
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(DownloadService.ACTION_START);
        intent.putExtra(DownloadService.EXTRA_URL, link);
        intent.putExtra(DownloadService.EXTRA_IP, ip);
        intent.putExtra(DownloadService.EXTRA_THREADS, workers);
        try { startForegroundService(intent);page.post(()->page.smoothScrollTo(0,transferSection.getTop())); }
        catch (Exception ex) { status.setText("无法启动下载服务：" + ex.getMessage()); }
    }
    private void showSnapshot() {
        DownloadService.Snapshot s = DownloadService.snapshot;
        if (last == s) return;
        last = s;
        status.setText(s.text);
        start.setEnabled(!s.active);
        stop.setEnabled(s.active);stop.setVisibility(s.active?View.VISIBLE:View.GONE);
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
    @Override protected void onStart() { super.onStart(); if(status!=null)handler.post(ticker); }
    @Override protected void onStop() { handler.removeCallbacks(ticker); super.onStop(); }
}
