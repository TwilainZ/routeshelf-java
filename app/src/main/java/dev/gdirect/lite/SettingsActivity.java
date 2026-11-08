package dev.gdirect.lite;

import dev.gdirect.lite.ui.AppScreens;

import dev.gdirect.lite.auth.GoogleAuthorization;
import dev.gdirect.lite.diagnostics.AppDiagnostics;
import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.network.ConnectionCheck;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** One stable destination for preferences, account actions and troubleshooting. */
public final class SettingsActivity extends AppCompatActivity {
    static final String EXTRA_ACCOUNT_ACTION="settings_account_action";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size) {
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);return view;
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
        androidx.fragment.app.FragmentContainerView body=new androidx.fragment.app.FragmentContainerView(this);
        body.setId(R.id.settings_content);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if(state==null)getSupportFragmentManager().beginTransaction().replace(R.id.settings_content,new Preferences()).commit();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);}
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
    /** AndroidX owns list spacing, two-line rows, focus, ripple and preference dialogs. */
    public static final class Preferences extends androidx.preference.PreferenceFragmentCompat {
        private final Handler handler=new Handler(Looper.getMainLooper());
        private androidx.preference.ListPreference mode,workers;
        private androidx.preference.Preference stateRow,account,connectAccount,manageAccount,disconnectAccount,revokeAccount;
        private boolean visible;
        private int pendingMode=-1;
        private final Runnable tick=new Runnable(){public void run(){if(!visible)return;render();handler.postDelayed(this,500);}};
        private SettingsActivity host(){return (SettingsActivity)requireActivity();}
        private androidx.preference.PreferenceCategory category(String title){
            androidx.preference.PreferenceCategory group=new androidx.preference.PreferenceCategory(requireContext());
            group.setTitle(title);group.setIconSpaceReserved(false);getPreferenceScreen().addPreference(group);return group;
        }
        private androidx.preference.Preference row(androidx.preference.PreferenceCategory group,String title,String summary,Runnable action){
            androidx.preference.Preference row=new androidx.preference.Preference(requireContext());row.setTitle(title);row.setSummary(summary);
            row.setIconSpaceReserved(false);row.setPersistent(false);row.setSelectable(action!=null);
            if(action!=null)row.setOnPreferenceClickListener(p->{action.run();return true;});group.addPreference(row);return row;
        }
        @Override public void onCreatePreferences(Bundle state,String rootKey){
            if(state!=null)pendingMode=state.getInt("pending_connection_mode",-1);
            setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
            androidx.preference.PreferenceCategory connection=category("连接");
            mode=new androidx.preference.ListPreference(requireContext());mode.setKey("connection_mode_ui");mode.setPersistent(false);
            mode.setTitle("连接方式");mode.setDialogTitle("连接方式");mode.setIconSpaceReserved(false);
            mode.setEntries(new String[]{"仅 Google 直连","允许已知中转","系统网络"});mode.setEntryValues(new String[]{"0","1","2"});
            mode.setOnPreferenceChangeListener((p,value)->{
                int selected=Integer.parseInt(value.toString());
                if(selected==GoogleNetwork.get().connectionMode()&&pendingMode<0)return false;
                if(selected==1)new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.relay_title).setMessage(R.string.relay_explanation)
                    .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.relay_allow,(d,w)->requestMode(selected)).show();
                else requestMode(selected);return false;
            });connection.addPreference(mode);
            stateRow=row(connection,"连接状态与检测","",()->startActivity(AppScreens.intent(requireContext(),ConnectionActivity.class)));
            row(connection,"连接日志与反馈","查看阶段耗时、复制或保存诊断日志",()->startActivity(AppScreens.intent(requireContext(),LogActivity.class)));
            row(connection,"内置 Google 候选","查看范围、日期和来源；连接时自动验证",()->new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.builtin_google_candidates).setMessage(GoogleNetwork.get().builtInCandidates()).setPositiveButton(android.R.string.ok,null).show());
            androidx.preference.PreferenceCategory accounts=category("Google 账户");
            account=row(accounts,"账户状态","",null);
            connectAccount=row(accounts,"连接 Google Drive","在 Google 页面完成授权",()->host().accountAction("connect"));
            manageAccount=row(accounts,"文件管理权限","按需允许新建、上传和修改文件",()->host().accountAction("manage"));
            disconnectAccount=row(accounts,"断开连接","忘记本机连接并停止自动恢复，保留 Google 授权",()->new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.drive_disconnect).setMessage("忘记本机连接并停止当前账户传输。再次打开应用不会自动连接；下次可手动重新连接。")
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_disconnect,(d,w)->host().accountAction("disconnect")).show());
            revokeAccount=row(accounts,"撤销 Google 授权","同时撤销此应用的 Google 访问权限",()->host().accountAction("revoke"));
            androidx.preference.PreferenceCategory transfers=category("下载");
            workers=new androidx.preference.ListPreference(requireContext());workers.setTitle("并行连接数");workers.setDialogTitle("下载并行连接数");
            workers.setKey("transfer_workers_ui");workers.setPersistent(false);workers.setIconSpaceReserved(false);
            workers.setEntries(new String[]{"1 条 · 稳妥","4 条 · 推荐","8 条 · 实验"});workers.setEntryValues(new String[]{"1","4","8"});
            workers.setOnPreferenceChangeListener((p,value)->{requireContext().getSharedPreferences("transfer-settings",MODE_PRIVATE).edit()
                .putInt("workers",Integer.parseInt(value.toString())).apply();render();return false;});transfers.addPreference(workers);
            row(transfers,"保存位置","下载/GDirect Lite",null);
            androidx.preference.PreferenceCategory about=category("关于");
            row(about,"隐私说明","账户、文件与本机日志的使用方式",()->host().showPrivacy());
            row(about,"应用与授权诊断","版本、签名和 Google 授权状态",()->host().showDiagnostics());
            row(about,"开源许可","应用与第三方组件声明",()->host().showLicenses());
            row(about,"版本",AppDiagnostics.buildLabel(requireContext()),null);render();
        }
        private void requestMode(int selected){
            if(DownloadService.snapshot.active){android.widget.Toast.makeText(requireContext(),"请先停止当前传输，再切换连接方式",android.widget.Toast.LENGTH_LONG).show();return;}
            pendingMode=selected;GoogleNetwork net=GoogleNetwork.get();
            if(net.connectionCheck().active())net.cancelCheck();render();
        }
        @Override public void onDisplayPreferenceDialog(androidx.preference.Preference preference){
            if(!(preference instanceof androidx.preference.ListPreference)){super.onDisplayPreferenceDialog(preference);return;}
            androidx.preference.ListPreference list=(androidx.preference.ListPreference)preference;
            new MaterialAlertDialogBuilder(requireContext()).setTitle(list.getDialogTitle())
                .setSingleChoiceItems(list.getEntries(),list.findIndexOfValue(list.getValue()),(dialog,index)->{
                    String value=list.getEntryValues()[index].toString();dialog.dismiss();
                    if(list.callChangeListener(value))list.setValue(value);
                }).setNegativeButton(android.R.string.cancel,null).show();
        }
        private void render(){
            if(mode==null||!isAdded())return;
            GoogleNetwork net=GoogleNetwork.get();ConnectionCheck.Snapshot state=net.connectionCheck();boolean transfer=DownloadService.snapshot.active;
            if(pendingMode>=0&&!state.active()){
                if(!transfer)net.connectionMode(pendingMode);
                else android.widget.Toast.makeText(requireContext(),"传输已开始，请停止后再切换连接方式",android.widget.Toast.LENGTH_LONG).show();
                pendingMode=-1;state=net.connectionCheck();
            }
            mode.setValue(Integer.toString(net.connectionMode()));mode.setSummary(mode.getEntry()+(transfer?" · 停止当前传输后可切换":""));mode.setEnabled(!transfer&&pendingMode<0);
            stateRow.setSummary(pendingMode>=0?"正在停止旧检测，随后切换连接方式…":
                dev.gdirect.lite.ui.ConnectionText.headline(net.health(),DriveSession.INSTANCE.connected(),
                    GoogleAuthorization.memory(requireContext()).state()==dev.gdirect.lite.auth.RememberedAuthorization.State.RESTORING,state.active()));
            boolean connected=DriveSession.INSTANCE.connected(),revoking=GoogleAuthorization.revoking.get();
            account.setSummary(dev.gdirect.lite.ui.ConnectionText.account(connected,DriveSession.INSTANCE.writable(),
                GoogleAuthorization.memory(requireContext()).state(),revoking));
            connectAccount.setVisible(!connected);manageAccount.setVisible(connected&&!DriveSession.INSTANCE.writable());
            disconnectAccount.setVisible(connected||GoogleAuthorization.memory(requireContext()).remembered());revokeAccount.setVisible(connected);
            connectAccount.setEnabled(!revoking);manageAccount.setEnabled(!revoking);disconnectAccount.setEnabled(!revoking);revokeAccount.setEnabled(!revoking);
            int count=requireContext().getSharedPreferences("transfer-settings",MODE_PRIVATE).getInt("workers",4);
            workers.setValue(Integer.toString(count));workers.setSummary(workers.getEntry()+"，应用于之后的下载");
        }
        @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("pending_connection_mode",pendingMode);}
        @Override public void onStart(){super.onStart();visible=true;handler.post(tick);}
        @Override public void onStop(){visible=false;handler.removeCallbacks(tick);super.onStop();}
    }
}
