package dev.gdirect.lite;

import dev.gdirect.lite.diagnostics.DiagnosticLog;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** User-controlled local log review and SAF export; no automatic upload or account/file data. */
public final class LogActivity extends AppCompatActivity {
    private static final int SAVE_LOG=812;
    private TextView text;
    private String pendingExport;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);if(state!=null)pendingExport=state.getString("pending_export");
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface,android.graphics.Color.WHITE));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()):insets.getSystemWindowInsets();
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets.consumeSystemWindowInsets();
        });setContentView(root);root.requestApplyInsets();
        MaterialToolbar toolbar=new MaterialToolbar(this);toolbar.setTitle(R.string.connection_logs);root.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));
        setSupportActionBar(toolbar);toolbar.setNavigationIcon(R.drawable.ic_back);toolbar.setNavigationContentDescription("返回");toolbar.setNavigationOnClickListener(v->finish());
        TextView description=new TextView(this);description.setText(R.string.connection_logs_explanation);description.setTextSize(14);description.setPadding(dp(20),dp(8),dp(20),dp(12));root.addView(description);
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(16),0,dp(16),dp(8));root.addView(actions);
        MaterialButton copy=new MaterialButton(this);copy.setText("复制日志");copy.setAllCaps(false);copy.setMinHeight(dp(48));
        actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        copy.setOnClickListener(v->{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.connection_logs),report()));
            Toast.makeText(this,"日志已复制，可粘贴反馈",Toast.LENGTH_SHORT).show();
        });
        MaterialButton save=new MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);save.setText("保存文本");save.setAllCaps(false);save.setMinHeight(dp(48));
        LinearLayout.LayoutParams button=new LinearLayout.LayoutParams(0,-2,1);button.setMarginStart(dp(8));actions.addView(save,button);
        save.setOnClickListener(v->{
            pendingExport=report();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE,"GDirectLite-log-"+java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())+".txt");
            try{startActivityForResult(intent,SAVE_LOG);}catch(android.content.ActivityNotFoundException error){pendingExport=null;Toast.makeText(this,"未找到文件保存工具，请使用复制日志",Toast.LENGTH_LONG).show();}
        });
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        text=new TextView(this);text.setTextSize(14);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setTextIsSelectable(true);text.setSaveEnabled(false);
        text.setPadding(dp(20),dp(12),dp(20),dp(24));scroll.addView(text);refresh();
    }
    private String report() {
        GoogleNetwork net=GoogleNetwork.get();
        return "GDirect Lite connection report\nVersion: "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")\nBuild: "+BuildConfig.REVISION
            +"\nPackage: "+getPackageName()+"\nAndroid API: "+android.os.Build.VERSION.SDK_INT
            +"\nMode: "+(net.systemNetwork()?"SYSTEM":net.relayEnabled()?"RELAY_ALLOWED":"GOOGLE_ONLY")
            +"\nPrivate log storage: "+(net.logStorageAvailable()?"available":"unavailable; memory logs only")
            +"\nRetention: 512 entries / 128 KiB; coalesced writes about 1s. No automatic upload.\n\n"+DiagnosticLog.get().report();
    }
    private void refresh(){text.setText(report());}
    @Override public boolean onCreateOptionsMenu(Menu menu){menu.add(0,1,0,"刷新");menu.add(0,2,1,"清空日志");return true;}
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId()==android.R.id.home){finish();return true;}
        if(item.getItemId()==1){refresh();return true;}
        if(item.getItemId()==2){new MaterialAlertDialogBuilder(this).setTitle("清空日志？")
            .setMessage("清除本机已有记录，之后的连接活动仍会记录。")
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton("清空",(d,w)->GoogleNetwork.get().clearLogs(()->runOnUiThread(()->{
                if(isDestroyed()||isFinishing())return;refresh();Toast.makeText(this,GoogleNetwork.get().logStorageAvailable()?"先前日志已清空":"当前日志已清空；缓存清理失败，请稍后重试",Toast.LENGTH_LONG).show();
            }))).show();return true;}
        return super.onOptionsItemSelected(item);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=SAVE_LOG)return;
        String content=pendingExport;pendingExport=null;
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(!"content".equals(data.getData().getScheme())){Toast.makeText(this,"无法使用该保存位置，请重新选择",Toast.LENGTH_LONG).show();return;}
        final android.net.Uri destination=data.getData();final String output=content==null?report():content;
        new Thread(()->{
            boolean saved=false;
            try(java.io.OutputStream stream=getContentResolver().openOutputStream(destination,"wt")) {
                if(stream==null)throw new java.io.IOException("No output stream");stream.write(output.getBytes(java.nio.charset.StandardCharsets.UTF_8));saved=true;
            }catch(java.io.IOException|RuntimeException ignored){}
            final boolean success=saved;runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())Toast.makeText(this,success?"日志已保存，可将文本文件发来反馈":"保存失败，请重新选择位置或复制日志",Toast.LENGTH_LONG).show();});
        },"gdirect-log-export").start();
    }
    @Override protected void onSaveInstanceState(Bundle state){if(pendingExport!=null)state.putString("pending_export",pendingExport);super.onSaveInstanceState(state);}
}
