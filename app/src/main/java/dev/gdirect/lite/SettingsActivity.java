package dev.gdirect.lite;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** One stable destination for preferences, account actions and troubleshooting. */
public final class SettingsActivity extends AppCompatActivity {
    static final String EXTRA_ACCOUNT_ACTION="settings_account_action";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView connection,account;
    private MaterialButton mode,check,workers,connectAccount,manageAccount,disconnectAccount,revokeAccount;
    private android.widget.ProgressBar progress;
    private boolean visible;
    private final Runnable tick=new Runnable(){public void run(){if(!visible)return;render();handler.postDelayed(this,500);}};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size) {
        TextView label=new TextView(this);label.setText(text);label.setTextSize(size);
        label.setPadding(dp(20),dp(8),dp(20),dp(8));return label;
    }
    private void section(String title) {
        TextView label=label(title,14);label.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        label.setTextColor(com.google.android.material.color.MaterialColors.getColor(this,androidx.appcompat.R.attr.colorPrimary,0));
        label.setPadding(dp(20),dp(28),dp(20),dp(8));label.setAccessibilityHeading(true);content.addView(label);
    }
    private MaterialButton row(String title,View.OnClickListener action) {
        MaterialButton row=new MaterialButton(this,null,R.attr.settingsRowStyle);
        row.setText(title);row.setAllCaps(false);row.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(56));row.setSingleLine(false);row.setMaxLines(3);row.setPadding(dp(20),dp(8),dp(20),dp(8));
        row.setOnClickListener(action);content.addView(row,new LinearLayout.LayoutParams(-1,-2));return row;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface,android.graphics.Color.WHITE));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()):insets.getSystemWindowInsets();
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets.consumeSystemWindowInsets();
        });setContentView(root);root.requestApplyInsets();
        MaterialToolbar toolbar=new MaterialToolbar(this);toolbar.setTitle(R.string.settings_title);
        toolbar.setNavigationIcon(R.drawable.ic_back);toolbar.setNavigationContentDescription("返回");
        toolbar.setNavigationOnClickListener(v->finish());root.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,0,0,dp(24));scroll.addView(content);
        section("账户与权限");account=label("",14);content.addView(account);
        connectAccount=row("连接 Google Drive",v->accountAction("connect"));
        manageAccount=row(getString(R.string.drive_manage_access),v->accountAction("manage"));
        disconnectAccount=row(getString(R.string.drive_disconnect),v->new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_disconnect)
            .setMessage("断开本机连接并停止当前账户的传输。Google 授权仍可在下次连接时使用。")
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_disconnect,(d,w)->accountAction("disconnect")).show());
        revokeAccount=row(getString(R.string.drive_revoke),v->accountAction("revoke"));
        section("连接");
        mode=row("",v->chooseMode());
        content.addView(label("浏览和传输共用此设置。自动模式会验证候选、复用可用线路，失败后尝试备用线路，无需手填 IP。",14));
        connection=label("",14);connection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(connection);
        progress=new android.widget.ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,dp(4));bar.setMargins(dp(20),dp(8),dp(20),dp(8));content.addView(progress,bar);
        check=row("检测连接",v->{if(GoogleNetwork.get().connectionCheck().active())GoogleNetwork.get().cancelCheck();else GoogleNetwork.get().startCheck();render();});
        content.addView(label("可离开此页继续检测，结果会保留。连接检测不登录、不读取私人文件；登录与更新授权仍依赖 Google Play 服务的系统网络。",14));
        row(getString(R.string.builtin_google_candidates),v->new MaterialAlertDialogBuilder(this).setTitle(R.string.builtin_google_candidates)
            .setMessage(GoogleNetwork.get().builtInCandidates()).setPositiveButton(android.R.string.ok,null).show());
        section("传输");workers=row("",v->chooseWorkers());
        content.addView(label("并行数应用于之后的下载，当前任务不变。下载文件保存在“下载/GDirect Lite”。",14));
        section("关于与帮助");
        row(getString(R.string.drive_privacy),v->showPrivacy());
        row(getString(R.string.connection_logs),v->startActivity(new Intent(this,LogActivity.class)));
        row(getString(R.string.diagnostics_title),v->showDiagnostics());
        row(getString(R.string.licenses),v->showLicenses());
        content.addView(label(AppDiagnostics.buildLabel(this),14));render();
    }
    private void showDiagnostics() {
        String details = AppDiagnostics.describe(this,
            getIntent().hasExtra("authorization_phase")?getIntent().getStringExtra("authorization_phase"):"not-requested",
            getIntent().hasExtra("last_activity_result")?getIntent().getIntExtra("last_activity_result",0):null,
            getIntent().hasExtra("last_sdk_status")?getIntent().getIntExtra("last_sdk_status",0):null);
        TextView text = label(details,14);
        text.setTextIsSelectable(true); text.setPadding(dp(20),dp(16),dp(20),dp(16));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(text);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.diagnostics_title).setView(scroll)
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.diagnostics_copy,(dialog,which) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.diagnostics_title),details));
            }).show();
    }
    private void showLicenses() {
        new Thread(() -> {
            try (java.io.InputStream in = getAssets().open("third-party-notices.txt");
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) bytes.write(buffer,0,n);
                String content = bytes.toString("UTF-8");
                // Google SDK notices are large; never lay out megabytes of TextView on the UI thread.
                java.util.List<String> pages = new java.util.ArrayList<>();
                for (int offset = 0; offset < content.length();) {
                    int end = Math.min(content.length(),offset + 8192);
                    if (end < content.length() && Character.isHighSurrogate(content.charAt(end-1))) end--;
                    pages.add(content.substring(offset,end)); offset = end;
                }
                handler.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    TextView text = label("",14); text.setPadding(dp(20),dp(16),dp(20),dp(16)); text.setTextIsSelectable(true);
                    android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.addView(text);
                    androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setView(scroll)
                        .setPositiveButton(R.string.licenses_next,null).setNegativeButton(R.string.licenses_previous,null)
                        .setNeutralButton(android.R.string.ok,null).create();
                    int[] page = {0};
                    Runnable display = () -> {
                        text.setText(pages.get(page[0])); scroll.scrollTo(0,0);
                        dialog.setTitle(getString(R.string.licenses_page,page[0]+1,pages.size()));
                        dialog.getButton(-1).setEnabled(page[0]+1 < pages.size());
                        dialog.getButton(-2).setEnabled(page[0] > 0);
                    };
                    dialog.setOnShowListener(ignored -> {
                        dialog.getButton(-1).setOnClickListener(v -> { page[0]++; display.run(); });
                        dialog.getButton(-2).setOnClickListener(v -> { page[0]--; display.run(); });
                        display.run();
                    });
                    dialog.show();
                });
            } catch (java.io.IOException error) { handler.post(() -> { if (!isDestroyed()) android.widget.Toast.makeText(this,R.string.licenses_unavailable,android.widget.Toast.LENGTH_LONG).show(); }); }
        },"gdirect-notices").start();
    }
    private void showPrivacy() {
        new Thread(()->{
            try(java.io.InputStream in=getAssets().open("privacy-policy.txt")) {
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(bytes.size()+n>65536)throw new java.io.IOException("Privacy text too large");bytes.write(buffer,0,n);}
                String content=bytes.toString("UTF-8");
                handler.post(()->{if(!isDestroyed()){
                    TextView view=label(content,16);view.setTextIsSelectable(true);view.setPadding(dp(20),dp(12),dp(20),dp(12));
                    android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(view);
                    new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_privacy).setView(scroll).setPositiveButton(android.R.string.ok,null).show();
                }});
            }catch(java.io.IOException e){handler.post(()->{if(!isDestroyed())android.widget.Toast.makeText(this,R.string.drive_privacy_unavailable,android.widget.Toast.LENGTH_LONG).show();});}
        },"privacy-text").start();
    }
    private void accountAction(String action) {
        startActivity(new Intent(this,DriveActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ACCOUNT_ACTION,action));finish();
    }
    private void chooseMode() {
        GoogleNetwork net=GoogleNetwork.get();int selected=net.systemNetwork()?2:net.relayEnabled()?1:0;
        new MaterialAlertDialogBuilder(this).setTitle("连接方式")
            .setSingleChoiceItems(new String[]{"自动直连 · 仅 Google（默认）","自动连接 · 优先已知中转","系统网络"},selected,(d,n)->{
                d.dismiss();if(n==selected)return;
                if(n==1)new MaterialAlertDialogBuilder(this).setTitle(R.string.relay_title).setMessage(R.string.relay_explanation)
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.relay_allow,(a,b)->applyMode(1)).show();
                else applyMode(n);
            }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private void applyMode(int n) {
        if(GoogleNetwork.get().connectionCheck().active()||DownloadService.snapshot.active)return;
        GoogleNetwork.get().relayEnabled(n==1);GoogleNetwork.get().systemNetwork(n==2);render();
    }
    private void chooseWorkers() {
        int count=getSharedPreferences("transfer-settings",MODE_PRIVATE).getInt("workers",4);
        new MaterialAlertDialogBuilder(this).setTitle("下载并行连接数")
            .setSingleChoiceItems(new String[]{"1 条 · 稳妥","4 条 · 推荐","8 条 · 实验"},count==1?0:count==8?2:1,(d,n)->{
                getSharedPreferences("transfer-settings",MODE_PRIVATE).edit().putInt("workers",n==0?1:n==1?4:8).apply();d.dismiss();render();
            }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private void render() {
        GoogleNetwork net=GoogleNetwork.get();ConnectionCheck.Snapshot state=net.connectionCheck();
        boolean busy=state.active(),transfer=DownloadService.snapshot.active;
        mode.setText("连接方式\n"+(net.systemNetwork()?"系统网络":net.relayEnabled()?"自动连接 · 优先已知中转":"自动直连 · 仅 Google"));mode.setEnabled(!busy&&!transfer);
        String detail=state.detail.isEmpty()?net.summary():state.detail;
        if(state.state==ConnectionCheck.State.SUCCEEDED)detail="上次检测完成\n"+state.detail;

        if(!android.text.TextUtils.equals(connection.getText(),detail))connection.setText(detail);
        check.setText(state.state==ConnectionCheck.State.CANCELLING?"正在取消…":busy?"取消检测":transfer?"传输期间使用当前连接":net.systemNetwork()?"系统网络由设备管理":"重新检测连接");
        check.setEnabled(state.state!=ConnectionCheck.State.CANCELLING&&!transfer&&!net.systemNetwork());progress.setVisibility(busy?View.VISIBLE:View.GONE);
        boolean connected=DriveSession.INSTANCE.connected(),revoking=GoogleAuthorization.revoking.get();
        connectAccount.setVisibility(connected?View.GONE:View.VISIBLE);connectAccount.setEnabled(!revoking);
        manageAccount.setVisibility(connected?View.VISIBLE:View.GONE);disconnectAccount.setVisibility(connected?View.VISIBLE:View.GONE);revokeAccount.setVisibility(connected?View.VISIBLE:View.GONE);
        manageAccount.setEnabled(connected&&!DriveSession.INSTANCE.writable()&&!revoking);
        disconnectAccount.setEnabled(connected&&!revoking);revokeAccount.setEnabled(connected&&!revoking);
        account.setText(connected?(DriveSession.INSTANCE.writable()?"已连接 · 可管理文件":"已连接 · 只读访问"):"尚未连接；公开链接下载无需登录。");
        workers.setText("下载并行连接数\n"+getSharedPreferences("transfer-settings",MODE_PRIVATE).getInt("workers",4)+" 条连接");
    }
    @Override protected void onStart(){super.onStart();visible=true;handler.post(tick);}
    @Override protected void onStop(){visible=false;handler.removeCallbacks(tick);super.onStop();}
}
