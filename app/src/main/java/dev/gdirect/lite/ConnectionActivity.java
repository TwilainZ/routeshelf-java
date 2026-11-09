package dev.gdirect.lite;

import dev.gdirect.lite.ui.AppScreens;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import dev.gdirect.lite.auth.GoogleAuthorization;
import dev.gdirect.lite.auth.RememberedAuthorization;
import dev.gdirect.lite.drive.DriveSession;
import dev.gdirect.lite.network.ConnectionCheck;
import dev.gdirect.lite.network.ConnectionHealth;
import dev.gdirect.lite.ui.ConnectionText;
import java.util.concurrent.TimeUnit;

/** A visible, read-only observation of the same application-owned routing/check state. */
public final class ConnectionActivity extends AppCompatActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean visible,technicalExpanded;
    private TextView headline,phase,account,api,download,apiTime,downloadTime,mode,details;
    private MaterialButton check,connect;
    private LinearProgressIndicator progress;
    private final Runnable tick=new Runnable(){public void run(){if(!visible)return;render();handler.postDelayed(this,500);}};
    private void text(TextView view,String value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        setContentView(R.layout.activity_connection);
        View root=findViewById(R.id.connection_root);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()):insets.getSystemWindowInsets();
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets.consumeSystemWindowInsets();
        });root.requestApplyInsets();
        int side=Math.round((24+Math.max(0,(getResources().getConfiguration().screenWidthDp-600)/2))*getResources().getDisplayMetrics().density);
        View content=findViewById(R.id.connection_content);content.setPadding(side,content.getPaddingTop(),side,content.getPaddingBottom());
        ((com.google.android.material.appbar.MaterialToolbar)findViewById(R.id.connection_toolbar)).setNavigationOnClickListener(v->finish());
        headline=findViewById(R.id.connection_headline);phase=findViewById(R.id.connection_phase);account=findViewById(R.id.connection_account_value);
        api=findViewById(R.id.connection_api_value);download=findViewById(R.id.connection_download_value);
        apiTime=findViewById(R.id.connection_api_time);downloadTime=findViewById(R.id.connection_download_time);
        mode=findViewById(R.id.connection_mode_value);details=findViewById(R.id.connection_route_details);
        check=findViewById(R.id.connection_check);connect=findViewById(R.id.connection_account_action);progress=findViewById(R.id.connection_progress);
        check.setOnClickListener(v->{GoogleNetwork net=GoogleNetwork.get();if(net.connectionCheck().active())net.cancelCheck();else net.startCheck();render();});
        connect.setOnClickListener(v->startActivity(new Intent(this,DriveActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SettingsActivity.EXTRA_ACCOUNT_ACTION,"connect")));
        findViewById(R.id.connection_settings).setOnClickListener(v->startActivity(AppScreens.intent(this,SettingsActivity.class)));
        findViewById(R.id.connection_logs).setOnClickListener(v->startActivity(AppScreens.intent(this,LogActivity.class)));
        technicalExpanded=state!=null&&state.getBoolean("technical_expanded",false);
        View technical=findViewById(R.id.connection_technical_panel);technical.setVisibility(technicalExpanded?View.VISIBLE:View.GONE);
        MaterialButton toggle=findViewById(R.id.connection_technical_toggle);
        toggle.setText(technicalExpanded?"收起技术详情":getString(R.string.ui_technical_details));
        toggle.setOnClickListener(v->{technicalExpanded=!technicalExpanded;technical.setVisibility(technicalExpanded?View.VISIBLE:View.GONE);toggle.setText(technicalExpanded?"收起技术详情":getString(R.string.ui_technical_details));});
        render();
    }
    private String age(ConnectionHealth.Result result){
        if(result.ageMillis<0)return "尚无验证记录";
        long seconds=result.ageMillis/1000;return seconds<60?"最近检查：刚刚":"最近检查："+(seconds/60)+" 分钟前";
    }
    private String route(String label,ConnectionHealth.Result result){
        if(result.ip.isEmpty())return "";
        return label+"："+result.ip+(result.tls==null?"":" · "+(result.tls==dev.gdirect.lite.network.DirectRoutes.TlsMode.GOOGLE_FRONT?"g.cn TLS":result.tls==dev.gdirect.lite.network.DirectRoutes.TlsMode.NO_SNI?"无 SNI":"标准 TLS"));
    }
    private void color(TextView view,ConnectionHealth.Result result){
        int attr=result.state==ConnectionHealth.State.FAILED||result.state==ConnectionHealth.State.RESPONSE_ERROR?
            androidx.appcompat.R.attr.colorError:com.google.android.material.R.attr.colorOnSurfaceVariant;
        view.setTextColor(MaterialColors.getColor(this,attr,android.graphics.Color.GRAY));
    }
    private void render(){
        GoogleNetwork net=GoogleNetwork.get();ConnectionHealth.Snapshot health=net.health();ConnectionCheck.Snapshot state=net.connectionCheck();
        RememberedAuthorization.State restored=GoogleAuthorization.memory(this).state();boolean authorized=DriveSession.INSTANCE.connected();
        boolean restoring=!authorized&&restored==RememberedAuthorization.State.RESTORING;
        text(headline,ConnectionText.headline(health,authorized,restoring,state.active()));
        text(account,ConnectionText.account(authorized,DriveSession.INSTANCE.writable(),restored,GoogleAuthorization.revoking.get()));
        text(api,ConnectionText.service(health.api,false));text(download,ConnectionText.service(health.download,true));color(api,health.api);color(download,health.download);
        text(apiTime,age(health.api));text(downloadTime,age(health.download));
        text(mode,(net.connectionMode()==0?"仅 Google 直连":net.connectionMode()==1?"允许已知第三方中转":"系统网络")
            +"\n"+net.ipv6Summary()
            +(net.vpnActive()?"\n设备 VPN 已开启，实际流量可能经过 VPN":""));
        boolean connecting=health.api.state==ConnectionHealth.State.CHECKING||health.download.state==ConnectionHealth.State.CHECKING;
        String step;
        if(!health.online)step="请先连接 Wi-Fi 或移动网络。";
        else if(state.active()){
            String stage=state.state==ConnectionCheck.State.CANCELLING?"正在取消，完成后可重新检测":health.api.state==ConnectionHealth.State.READY?"正在验证文件服务":"正在寻找并验证 Google 云盘线路";
            step=stage+" · 已用 "+Math.max(0,TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-state.startedAt))+" 秒";
        }else if(restoring)step="Google 官方服务正在恢复上次的账户授权。";
        else if(connecting)step="正在为当前请求寻找并验证可用线路，结果会自动更新。";
        else if(health.api.state==ConnectionHealth.State.FAILED||health.download.state==ConnectionHealth.State.FAILED)step="当前数据连接未成功，可重新检测或查看连接日志。";
        else if(health.api.state==ConnectionHealth.State.RESPONSE_ERROR||health.download.state==ConnectionHealth.State.RESPONSE_ERROR)step="Google 服务已响应，请按下面的提示处理授权、文件权限或服务错误。";
        else if(health.api.state==ConnectionHealth.State.STALE||health.download.state==ConnectionHealth.State.STALE)step="上次验证时间较早，请重新检测当前网络。";
        else if(state.state==ConnectionCheck.State.CANCELLED)step="检测已取消。已完成的结果保留，可随时重新检测。";
        else if(state.state==ConnectionCheck.State.FAILED&&health.api.state!=ConnectionHealth.State.READY)step="检测未全部通过，请查看下面的分项状态或连接日志。";
        else if(state.state==ConnectionCheck.State.SUCCEEDED)step="线路检测完成。实际文件下载结果会单独更新。";
        else step="浏览或下载时自动更新，也可主动检测；离开此页不会停止检测。";
        text(phase,step);progress.setVisibility(state.active()||restoring||connecting?View.VISIBLE:View.GONE);
        boolean transfer=DownloadService.snapshot.active;
        text(check,state.state==ConnectionCheck.State.CANCELLING?"正在取消…":state.active()?"取消检测":transfer?"传输完成后可检测":
            state.state==ConnectionCheck.State.IDLE&&health.api.state==ConnectionHealth.State.UNKNOWN?"检测连接":"重新检测连接");
        check.setEnabled(health.online&&!transfer&&state.state!=ConnectionCheck.State.CANCELLING);
        connect.setVisibility(authorized?View.GONE:View.VISIBLE);connect.setEnabled(!GoogleAuthorization.revoking.get());
        String apiRoute=route("云盘线路",health.api),downloadRoute=route("文件线路",health.download);
        text(details,apiRoute+(apiRoute.isEmpty()||downloadRoute.isEmpty()?"":"\n")+downloadRoute);
        details.setVisibility(apiRoute.isEmpty()&&downloadRoute.isEmpty()?View.GONE:View.VISIBLE);
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("technical_expanded",technicalExpanded);super.onSaveInstanceState(state);}
    @Override protected void onStart(){super.onStart();visible=true;handler.post(tick);}
    @Override protected void onStop(){visible=false;handler.removeCallbacks(tick);super.onStop();}
}
