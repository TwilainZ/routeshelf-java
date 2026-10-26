package dev.gdirect.lite;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

/** Private cached previews. No bearer URLs, web scripts, external providers or world-readable files. */
public final class FilePreviewActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private boolean resumed;
    private volatile HttpsURLConnection connection;
    private volatile File cache;
    private TextView state;
    private ProgressBar progress;
    private ImageView image;
    private TextView text;
    private android.widget.VideoView video;
    private MaterialButton previous,next,retry;
    private Bitmap shown;
    private int page,pageCount;
    private DriveSession.Lease lease;
    private LinearLayout root;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);setTitle(R.string.drive_preview);
        if(getSupportActionBar()!=null)getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int gap=Math.round(16*getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()):insets.getSystemWindowInsets();
            v.setPadding(gap+bars.left,gap+bars.top,gap+bars.right,gap+bars.bottom);return insets;
        });setContentView(root);root.requestApplyInsets();
        state=new TextView(this);state.setText(R.string.drive_preview_loading);state.setTextSize(16);state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(state);
        progress=new ProgressBar(this);root.addView(progress);
        image=new ImageView(this);image.setAdjustViewBounds(true);image.setContentDescription(getString(R.string.drive_preview));
        text=new TextView(this);text.setTextIsSelectable(true);text.setTextSize(16);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.addView(image,new LinearLayout.LayoutParams(-1,-2));content.addView(text,new LinearLayout.LayoutParams(-1,-2));
        video=new android.widget.VideoView(this);video.setVisibility(View.GONE);content.addView(video,new LinearLayout.LayoutParams(-1,Math.round(240*getResources().getDisplayMetrics().density)));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);root.addView(actions);
        previous=new MaterialButton(this);previous.setText(R.string.drive_previous_page);actions.addView(previous,new LinearLayout.LayoutParams(0,-2,1));
        next=new MaterialButton(this);next.setText(R.string.drive_next_page);actions.addView(next,new LinearLayout.LayoutParams(0,-2,1));
        previous.setVisibility(View.GONE);next.setVisibility(View.GONE);
        previous.setOnClickListener(v->renderPage(page-1));next.setOnClickListener(v->renderPage(page+1));
        retry=new MaterialButton(this);retry.setText(R.string.drive_retry);retry.setVisibility(View.GONE);root.addView(retry);retry.setOnClickListener(v->recreate());
        try {
            String id=DriveApi.requireId(getIntent().getStringExtra("file"));
            lease=DriveSession.INSTANCE.lease();
            if(lease.id()!=getIntent().getLongExtra("session",-1))throw new DriveSession.AuthorizationRequired();
            worker.submit(()->{
                try {
                    check();cache=File.createTempFile("drive-preview-",".tmp",getCacheDir());
                    String mime=ReadRetry.run(()->fetch(id),this::check);check();
                    if(mime.equals("application/pdf"))handler.post(()->{if(!stopped)renderPage(0);});
                    else if(PreviewPolicy.media(mime))handler.post(()->{
                        if(stopped)return;
                        try{lease.requireCurrent();}catch(IOException e){failure(e);return;}
                        video.setVisibility(View.VISIBLE);android.widget.MediaController controls=new android.widget.MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);
                        video.setOnPreparedListener(player->{if(stopped){video.stopPlayback();return;}ready();video.seekTo(1);if(resumed&&hasWindowFocus())controls.show(0);});
                        video.setOnErrorListener((player,what,extra)->{failure(new IOException(getString(R.string.drive_media_error)));return true;});video.setVideoPath(cache.toString());
                    });
                    else if(mime.startsWith("image/")) {
                        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(cache.toString(),options);
                        options.inSampleSize=PreviewPolicy.sample(options.outWidth,options.outHeight);options.inJustDecodeBounds=false;
                        Bitmap bitmap=BitmapFactory.decodeFile(cache.toString(),options);if(bitmap==null)throw new IOException(getString(R.string.drive_preview_invalid));
                        handler.post(()->display(bitmap));
                    } else {
                        String value=new String(java.nio.file.Files.readAllBytes(cache.toPath()),java.nio.charset.StandardCharsets.UTF_8);
                        handler.post(()->{if(!stopped){try{lease.requireCurrent();text.setText(value);ready();}catch(IOException e){failure(e);}}});
                    }
                }catch(Exception error){failure(error);}
                finally{if(stopped&&cache!=null)cache.delete();}
            });
        }catch(IOException error){failure(error);}
    }
    private void check()throws IOException {if(stopped||Thread.currentThread().isInterrupted())throw new InterruptedIOException("预览已取消");lease.requireCurrent();}
    private String fetch(String id)throws IOException {
        check();DriveApi api=new DriveApi(GoogleAuthorization.transport(this,lease));DriveApi.FileEntry info=api.metadata(id);
        String mime=info.previewMime;
        if(!info.downloadable||!PreviewPolicy.supports(mime))throw new IOException(getString(R.string.drive_preview_unsupported));
        long limit=info.exportMime.isEmpty()?PreviewPolicy.limit(mime):10L*1024*1024;
        if(info.exportMime.isEmpty()&&info.size>limit)throw new IOException(getString(R.string.drive_preview_too_large));
        HttpsURLConnection c=GoogleAuthorization.transport(this,lease).open(info.exportMime.isEmpty()?api.mediaUrl(id):api.exportUrl(info,mime));connection=c;
        try {
            check();long advertised=c.getContentLengthLong();if(advertised>limit)throw new IOException(getString(R.string.drive_preview_too_large));
            MessageDigest digest;
            try{digest=MessageDigest.getInstance("MD5");}catch(java.security.NoSuchAlgorithmException e){throw new IOException("MD5 unavailable",e);}
            long total=0;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(cache)) {
                byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1){check();if(n>limit-total)throw new IOException(getString(R.string.drive_preview_too_large));out.write(buffer,0,n);digest.update(buffer,0,n);total+=n;}
                out.getFD().sync();
            }
            check();
            if(info.exportMime.isEmpty())FileIntegrity.verify(total,info.size,digest.digest(),info.md5);
            else if(advertised>=0&&total!=advertised)throw new IOException(getString(R.string.drive_preview_invalid));
            return mime;
        }finally{c.disconnect();connection=null;}
    }
    private void renderPage(int requested) {
        if(stopped||requested<0||(pageCount>0&&requested>=pageCount))return;
        progress.setVisibility(View.VISIBLE);previous.setEnabled(false);next.setEnabled(false);
        worker.submit(()->{
            try {
                check();Bitmap bitmap;int count;
                try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(cache,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)) {
                    count=renderer.getPageCount();if(requested>=count)throw new IOException(getString(R.string.drive_preview_invalid));
                    try(PdfRenderer.Page pdf=renderer.openPage(requested)) {
                        double scale=Math.min(1024d/pdf.getWidth(),1536d/pdf.getHeight());
                        bitmap=Bitmap.createBitmap(Math.max(1,(int)(pdf.getWidth()*scale)),Math.max(1,(int)(pdf.getHeight()*scale)),Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(android.graphics.Color.WHITE);pdf.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                }
                handler.post(()->{if(stopped){bitmap.recycle();return;}page=requested;pageCount=count;display(bitmap);state.setText(getString(R.string.drive_preview_page,page+1,pageCount));previous.setVisibility(View.VISIBLE);next.setVisibility(View.VISIBLE);previous.setEnabled(page>0);next.setEnabled(page+1<pageCount);});
            }catch(Exception error){failure(error);}
        });
    }
    private void ready(){progress.setVisibility(View.GONE);state.setText(R.string.drive_preview_ready);retry.setVisibility(View.GONE);}
    private void display(Bitmap bitmap) {
        if(stopped){bitmap.recycle();return;}
        try{lease.requireCurrent();}catch(IOException e){bitmap.recycle();failure(e);return;}
        Bitmap old=shown;shown=bitmap;image.setImageBitmap(bitmap);if(old!=null)old.recycle();ready();
    }
    private void failure(Exception error) {
        handler.post(()->{if(!stopped){progress.setVisibility(View.GONE);state.setText(getString(R.string.drive_preview_error,error.getMessage()==null?getString(R.string.drive_preview_invalid):error.getMessage()));retry.setVisibility(View.VISIBLE);}});
    }
    @Override public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){finish();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onResume(){super.onResume();resumed=true;if(lease!=null)try{lease.requireCurrent();}catch(IOException e){image.setImageDrawable(null);text.setText("");video.stopPlayback();video.setVisibility(View.GONE);if(shown!=null){shown.recycle();shown=null;}failure(e);}}
    @Override public void onPause(){resumed=false;if(video!=null)video.pause();super.onPause();}
    @Override public void onDestroy(){stopped=true;if(connection!=null)connection.disconnect();worker.shutdownNow();video.stopPlayback();image.setImageDrawable(null);if(shown!=null)shown.recycle();if(cache!=null)cache.delete();super.onDestroy();}
}
