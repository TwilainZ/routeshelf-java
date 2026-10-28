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
import java.util.Locale;

public final class MainActivity extends AppCompatActivity {
    static final String EXTRA_SETTINGS="show_network_settings", EXTRA_TRANSFER="show_transfer";
    private int INK,MUTED,BLUE,PALE,SURFACE,OUTLINE;
    private LinearLayout networkPanel,manualPanel;
    private Button settingsToggle,advancedToggle;
    private ScrollView page;
    private View transferSection;
    private EditText linkInput, ipInput;
    private Spinner threads;
    private TextView status, progressLabel, networkStatus;
    private Spinner networkMode;
    private Thread scanThread;
    private ProgressBar progress;
    private Button scan, start, stop, open;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadService.Snapshot last;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            String summary=GoogleNetwork.get().summary();
            if(!android.text.TextUtils.equals(networkStatus.getText(),summary))networkStatus.setText(summary);
            networkMode.setEnabled(!DownloadService.snapshot.active && scanThread == null);
            showSnapshot();
            scan.setEnabled(scanThread != null || (!DownloadService.snapshot.active && !GoogleNetwork.get().systemNetwork()));
            start.setEnabled(!DownloadService.snapshot.active && scanThread == null);
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

        root.addView(text(getString(R.string.app_name), 27, INK, true));
        space(root, 7);
        root.addView(text("公开链接下载 · 云盘浏览、上传与管理", 13, MUTED, false));
        space(root, 22);

        Button myDrive = button(getString(R.string.drive_title), true);
        myDrive.setOnClickListener(v -> startActivity(new Intent(this, DriveActivity.class)));
        root.addView(myDrive, new LinearLayout.LayoutParams(-1,-2));
        space(root, 16);

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

        settingsToggle=button(getString(R.string.connection_settings),false);
        root.addView(settingsToggle,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout network = card(root);networkPanel=network;
        network.setVisibility(savedInstanceState!=null&&savedInstanceState.getBoolean("network_expanded")?View.VISIBLE:View.GONE);
        settingsToggle.setOnClickListener(v->{network.setVisibility(network.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});
        heading(network, "数据连接", "浏览和传输共用连接设置。默认仅使用 Google 前端；也可明确允许已知第三方中转。证书始终由 Google 专用根验证。");
        networkMode = new Spinner(this);
        ArrayAdapter<String> modes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{"仅 Google 前端（自动检测）", "自动直连（允许已知中转）", "系统网络（使用设备网络设置）"});
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        networkMode.setAdapter(modes);
        networkMode.setMinimumHeight(dp(48));
        networkMode.setContentDescription("数据连接方式");
        networkMode.setSelection(GoogleNetwork.get().systemNetwork()?2:GoogleNetwork.get().relayEnabled()?1:0);
        networkMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if(position==1&&!GoogleNetwork.get().relayEnabled()) {
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle(R.string.relay_title).setMessage(R.string.relay_explanation)
                        .setNegativeButton(android.R.string.cancel,(dialog,n)->networkMode.setSelection(GoogleNetwork.get().systemNetwork()?2:0))
                        .setOnCancelListener(dialog->networkMode.setSelection(GoogleNetwork.get().systemNetwork()?2:0))
                        .setPositiveButton(R.string.relay_allow,(dialog,n)->{GoogleNetwork.get().relayEnabled(true);GoogleNetwork.get().systemNetwork(false);}).show();
                }else {GoogleNetwork.get().relayEnabled(position==1);GoogleNetwork.get().systemNetwork(position==2);}
                if(scan!=null)scan.setEnabled(position!=2);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        network.addView(networkMode);
        networkStatus = text(GoogleNetwork.get().summary(), 14, INK, false);
        networkStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        network.addView(networkStatus);
        Button builtIn=button(getString(R.string.builtin_google_candidates),false);
        builtIn.setOnClickListener(v->new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.builtin_google_candidates).setMessage(GoogleNetwork.get().builtInCandidates())
            .setPositiveButton(android.R.string.ok,null).show());
        network.addView(builtIn,new LinearLayout.LayoutParams(-1,-2));
        space(network, 10);
        scan = button("重新检测直连", false);
        network.addView(scan, new LinearLayout.LayoutParams(-1,-2));
        scan.setEnabled(!GoogleNetwork.get().systemNetwork());
        scan.setOnClickListener(v -> scanIps());
        space(network, 8);
        network.addView(text("登录及重新获取授权仍由 Google 服务连接系统网络。直连可用性取决于当前网络，VPN/TUN 可能接管连接。", 13, MUTED, false));
        Button advanced = button("高级：指定公开下载 IP", false);advancedToggle=advanced;
        network.addView(advanced, new LinearLayout.LayoutParams(-1,-2));
        LinearLayout manual = vertical();manualPanel=manual;
        manual.setVisibility(View.GONE);
        TextView manualLabel = text("Google 前端 IPv4（可选）", 14, INK, false);
        manual.addView(manualLabel);
        ipInput = new EditText(this);
        ipInput.setId(View.generateViewId());
        ipInput.setSingleLine(true);
        ipInput.setTextSize(14);
        ipInput.setTextColor(INK);
        ipInput.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        ipInput.setHint("留空使用上方连接方式");
        ipInput.setHintTextColor(MUTED);
        ipInput.setBackground(rounded(PALE, OUTLINE));
        ipInput.setPadding(dp(13), dp(14), dp(13), dp(14));
        manualLabel.setLabelFor(ipInput.getId());
        manual.addView(ipInput, new LinearLayout.LayoutParams(-1,-2));
        manual.addView(text("仅覆盖本次公开下载；地址须属于 Google 官方前端范围，并校验真实域名证书。私人文件由自动直连或系统网络处理。",13,MUTED,false));
        network.addView(manual);
        advanced.setOnClickListener(v -> {
            boolean visible = manual.getVisibility() != View.VISIBLE;
            manual.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (!visible) ipInput.setText("");
            advanced.setText(visible ? "收起高级设置" : "高级：指定公开下载 IP");
        });
        space(network, 13);
        network.addView(text("并行连接数", 13, INK, true));
        threads = new Spinner(this);
        String[] items = {"1 条连接（稳妥）", "4 条连接（推荐）", "8 条连接（实验）"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        threads.setAdapter(adapter);
        int savedWorkers=getSharedPreferences("transfer-settings",MODE_PRIVATE).getInt("workers",4);
        threads.setSelection(savedWorkers==1?0:savedWorkers==8?2:1);
        threads.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){getSharedPreferences("transfer-settings",MODE_PRIVATE).edit().putInt("workers",position==0?1:position==1?4:8).apply();}
        });
        threads.setMinimumHeight(dp(48));threads.setContentDescription("并行连接数");
        network.addView(threads);

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
        root.removeView(actions);root.addView(actions,root.indexOfChild(settingsToggle));
        linkInput.setText(savedInstanceState==null?getPreferences(MODE_PRIVATE).getString("link",""):savedInstanceState.getString("link_draft",""));
        if(savedInstanceState!=null&&savedInstanceState.getBoolean("manual_expanded")) {
            manual.setVisibility(View.VISIBLE);ipInput.setText(savedInstanceState.getString("manual_ip",""));advanced.setText("收起高级设置");
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
            networkPanel.setVisibility(View.VISIBLE);page.post(()->page.smoothScrollTo(0,settingsToggle.getTop()));
        }else if(intent.getBooleanExtra(EXTRA_TRANSFER,false))page.post(()->page.smoothScrollTo(0,transferSection.getTop()));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if(linkInput==null){super.onSaveInstanceState(state);return;}
        state.putString("link_draft",linkInput.getText().toString());
        state.putBoolean("network_expanded",networkPanel.getVisibility()==View.VISIBLE);
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
    private void scanIps() {
        if (scanThread != null) { scanThread.interrupt(); scan.setEnabled(false); return; }
        scan.setText("取消检测");
        GoogleNetwork.get().reset();
        scanThread = new Thread(() -> {
            try {
                GoogleNetwork.get().checkRoutes();
            } catch (java.io.IOException ignored) { /* Specific progress/failure is shown by the route manager. */ }
            finally { runOnUiThread(() -> {
                scanThread = null;
                if (!isDestroyed()) { scan.setText("重新检测直连"); scan.setEnabled(!GoogleNetwork.get().systemNetwork()); }
            }); }
        }, "gdirect-scan");
        scanThread.start();
    }
    private void beginDownload() {
        if (DownloadService.snapshot.active) { Toast.makeText(this, "已有下载正在进行", Toast.LENGTH_SHORT).show(); return; }
        String link = linkInput.getText().toString().trim();
        String ip = ipInput.getText().toString().trim();
        linkInput.setError(null);ipInput.setError(null);
        try { DirectNet.normalizeSharedLink(link); }
        catch(Exception ex){linkInput.setError(ex.getMessage());linkInput.requestFocus();return;}
        try { DirectNet.parseIp(ip); }
        catch(Exception ex){networkPanel.setVisibility(View.VISIBLE);manualPanel.setVisibility(View.VISIBLE);ipInput.setError(ex.getMessage());ipInput.requestFocus();return;}
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(linkInput.getWindowToken(),0);
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
        if (scanThread != null) scanThread.interrupt();
        super.onDestroy();
    }
    @Override protected void onStart() { super.onStart(); if(networkStatus!=null)handler.post(ticker); }
    @Override protected void onStop() { handler.removeCallbacks(ticker); super.onStop(); }
}
