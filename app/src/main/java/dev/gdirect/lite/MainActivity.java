package dev.gdirect.lite;

import dev.gdirect.lite.ui.AppScreens;

import dev.gdirect.lite.network.DirectNet;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

public final class MainActivity extends AppCompatActivity {
    static final String EXTRA_SETTINGS="show_network_settings", EXTRA_TRANSFER="show_transfer";
    private LinearLayout manualPanel;
    private EditText linkInput, ipInput;
    private com.google.android.material.textfield.TextInputLayout linkField,ipField;
    private Button start,viewTransfer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadService.Snapshot last;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            showSnapshot();
            start.setEnabled(!DownloadService.snapshot.active);
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(savedInstanceState==null&&Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            startActivity(new Intent(this,DriveActivity.class));finish();return;
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        setContentView(R.layout.activity_transfers);
        View root=findViewById(R.id.transfers_root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime()):insets.getSystemWindowInsets();
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets.consumeSystemWindowInsets();
        });root.requestApplyInsets();
        com.google.android.material.appbar.MaterialToolbar toolbar=findViewById(R.id.transfers_toolbar);
        toolbar.setNavigationOnClickListener(v->finish());
        toolbar.getMenu().add(0,1,0,R.string.settings_title);
        toolbar.getMenu().add(0,2,1,R.string.connection_status_title);
        toolbar.setOnMenuItemClickListener(item->{startActivity(AppScreens.intent(this,item.getItemId()==1?SettingsActivity.class:ConnectionActivity.class));return true;});
        View content=findViewById(R.id.transfers_content);
        int side=Math.round((24+Math.max(0,(getResources().getConfiguration().screenWidthDp-600)/2))*getResources().getDisplayMetrics().density);
        content.setPadding(side,content.getPaddingTop(),side,content.getPaddingBottom());
        linkInput=findViewById(R.id.public_link);ipInput=findViewById(R.id.public_ip);manualPanel=findViewById(R.id.public_manual_panel);
        linkField=findViewById(R.id.public_link_field);ipField=findViewById(R.id.public_ip_field);
        start=findViewById(R.id.public_start);viewTransfer=findViewById(R.id.public_transfer_link);
        findViewById(R.id.public_paste).setOnClickListener(v->{
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip().getItemCount()>0){
                CharSequence value=clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);linkInput.setText(value==null?"":value.toString().trim());
            }
        });
        com.google.android.material.button.MaterialButton advanced=findViewById(R.id.public_advanced);
        advanced.setOnClickListener(v->{boolean expanded=manualPanel.getVisibility()!=View.VISIBLE;manualPanel.setVisibility(expanded?View.VISIBLE:View.GONE);
            advanced.setText(expanded?"收起高级选项":getString(R.string.ui_advanced));if(!expanded)ipInput.setText("");});
        start.setOnClickListener(v->beginDownload());
        viewTransfer.setOnClickListener(v->startActivity(AppScreens.intent(this,TransferActivity.class)));
        linkInput.setText(savedInstanceState==null?getPreferences(MODE_PRIVATE).getString("link",""):savedInstanceState.getString("link_draft",""));
        if(savedInstanceState!=null&&savedInstanceState.getBoolean("manual_expanded")) {
            manualPanel.setVisibility(View.VISIBLE);advanced.setText("收起高级选项");ipInput.setText(savedInstanceState.getString("manual_ip",""));
        }
        // Do not carry an old scanner-selected IP into the automatic routing workflow.
        if(savedInstanceState==null)takeSharedIntent(getIntent());
        showSnapshot();showRequestedSection(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeSharedIntent(intent);showRequestedSection(intent);
    }
    private void showRequestedSection(Intent intent) {
        if(intent==null)return;
        if(intent.getBooleanExtra(EXTRA_SETTINGS,false)) {
            startActivity(AppScreens.intent(this,SettingsActivity.class));intent.removeExtra(EXTRA_SETTINGS);
        }else if(intent.getBooleanExtra(EXTRA_TRANSFER,false)) {
            intent.removeExtra(EXTRA_TRANSFER);startActivity(AppScreens.intent(this,TransferActivity.class));
        }
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
        linkField.setError(null);ipField.setError(null);
        try { DirectNet.normalizeSharedLink(link); }
        catch(Exception ex){linkField.setError(ex.getMessage());linkInput.requestFocus();return;}
        try { DirectNet.parseIp(ip); }
        catch(Exception ex){manualPanel.setVisibility(View.VISIBLE);ipField.setError(ex.getMessage());ipInput.requestFocus();return;}
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
        try { startForegroundService(intent);startActivity(AppScreens.intent(this,TransferActivity.class)); }
        catch (Exception ex) { com.google.android.material.snackbar.Snackbar.make(findViewById(R.id.transfers_root),"无法启动下载服务，请重试。",com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show(); }
    }
    private void showSnapshot() {
        DownloadService.Snapshot s=DownloadService.snapshot;
        if(last==s)return;last=s;
        start.setEnabled(!s.active);
        viewTransfer.setVisibility(s==DownloadService.IDLE?View.GONE:View.VISIBLE);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }
    @Override protected void onStart() { super.onStart(); if(start!=null)handler.post(ticker); }
    @Override protected void onStop() { handler.removeCallbacks(ticker); super.onStop(); }
}
