package dev.gdirect.lite;

import dev.gdirect.lite.ui.AppScreens;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import dev.gdirect.lite.ui.TransferText;

/** Task observation only: entering this screen never starts or restarts a transfer. */
public final class TransferActivity extends AppCompatActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private DownloadService.Snapshot last;
    private TextView heading,status,amount;
    private LinearProgressIndicator progress;
    private View root,open,stop,emptyAction,logs;
    private final Runnable ticker=new Runnable(){public void run(){render();handler.postDelayed(this,500);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        setContentView(R.layout.activity_transfer_detail);
        root=findViewById(R.id.transfer_detail_root);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime()):insets.getSystemWindowInsets();
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets.consumeSystemWindowInsets();
        });root.requestApplyInsets();
        int side=Math.round((24+Math.max(0,(getResources().getConfiguration().screenWidthDp-600)/2))*getResources().getDisplayMetrics().density);
        View content=findViewById(R.id.transfer_detail_content);content.setPadding(side,content.getPaddingTop(),side,content.getPaddingBottom());
        MaterialToolbar toolbar=findViewById(R.id.transfer_detail_toolbar);
        toolbar.setNavigationOnClickListener(v->finish());
        toolbar.getMenu().add(0,1,0,R.string.ui_public_file);
        toolbar.setOnMenuItemClickListener(item->{openPublicDownload();return true;});
        heading=findViewById(R.id.transfer_heading);status=findViewById(R.id.transfer_status);amount=findViewById(R.id.transfer_amount);
        progress=findViewById(R.id.transfer_progress);open=findViewById(R.id.transfer_open);stop=findViewById(R.id.transfer_stop);
        emptyAction=findViewById(R.id.transfer_empty_action);logs=findViewById(R.id.transfer_logs);
        open.setOnClickListener(v->openFile());
        stop.setOnClickListener(v->{
            try{startService(new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_STOP));}
            catch(RuntimeException error){Snackbar.make(root,R.string.drive_cancel_failed,Snackbar.LENGTH_LONG).show();}
        });
        emptyAction.setOnClickListener(v->openPublicDownload());logs.setOnClickListener(v->startActivity(AppScreens.intent(this,LogActivity.class)));
        render();
    }
    private void openPublicDownload(){startActivity(AppScreens.intent(this,MainActivity.class));}
    private void render(){
        DownloadService.Snapshot s=DownloadService.snapshot;
        if(last==s)return;last=s;
        boolean idle=s==DownloadService.IDLE;
        ((MaterialToolbar)findViewById(R.id.transfer_detail_toolbar)).getMenu().findItem(1).setVisible(!idle);
        heading.setText(idle?R.string.ui_transfer_empty:s.active?R.string.ui_current_transfer:R.string.ui_last_transfer);
        // Don't announce every byte counter update through the live region.
        CharSequence message=idle?getString(R.string.ui_transfer_empty_help):s.text;
        if(!android.text.TextUtils.equals(status.getText(),message))status.setText(message);
        progress.setIndeterminate(s.active&&s.total<=0);progress.setVisibility(s.active?View.VISIBLE:View.GONE);
        if(s.total>0)progress.setProgress(TransferText.progress(s.done,s.total));
        amount.setVisibility(idle?View.GONE:View.VISIBLE);
        amount.setText(TransferText.amount(s.done,s.total,s.bytesPerSecond,s.active,s.finished));
        open.setVisibility(s.finished&&s.savedUri!=null?View.VISIBLE:View.GONE);
        stop.setVisibility(s.active?View.VISIBLE:View.GONE);
        emptyAction.setVisibility(idle?View.VISIBLE:View.GONE);
        logs.setVisibility(!idle&&!s.active&&!s.finished?View.VISIBLE:View.GONE);
    }
    private void openFile(){
        DownloadService.Snapshot s=DownloadService.snapshot;if(!s.finished||s.savedUri==null)return;
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s.savedUri),s.mime==null?"*/*":s.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(Intent.createChooser(intent,getString(R.string.ui_open_file)));}
        catch(RuntimeException error){Snackbar.make(root,R.string.ui_open_file_unavailable,Snackbar.LENGTH_LONG).show();}
    }
    @Override protected void onStart(){super.onStart();handler.post(ticker);}
    @Override protected void onStop(){handler.removeCallbacks(ticker);super.onStop();}
}
