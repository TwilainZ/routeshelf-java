package dev.gdirect.lite;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Explicit user consent, in-memory authorization, and a paginated read-only Drive browser. */
public final class DriveActivity extends AppCompatActivity {
    private static final int AUTHORIZE = 701;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<DriveApi.FileEntry> files = new ArrayList<>();
    private final Deque<String[]> parents = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FileAdapter adapter = new FileAdapter();
    private TextView message, location, transfer, explanation;
    private LinearLayout queryRow;
    private ListView fileList;
    private EditText search;
    private Button connect, disconnect, find, more, up;
    private ProgressBar loadingIndicator;
    private Future<?> listing;
    private String folder = "root", folderName, nextPage = "";
    private String activeSearch = "";
    private int requestVersion;
    private Integer lastActivityResult, lastSdkStatus;
    private String authorizationPhase = "not-requested";
    private boolean loading, authorizing, waitingResolution, wasConnected, transferActive, wasRevoking;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (wasConnected && !DriveSession.INSTANCE.connected()) clearBrowser();
            if (wasRevoking && !GoogleAuthorization.revoking.get()) {
                message.setText(GoogleAuthorization.lastRevokeSucceeded ? R.string.drive_revoked : R.string.drive_revoke_failed);
                updateControls();
            }
            DownloadService.Snapshot s = DownloadService.snapshot;
            transfer.setText(s.active || s.finished ? s.text : "");
            if (transferActive != s.active) { transferActive = s.active; adapter.notifyDataSetChanged(); }
            handler.postDelayed(this, 500);
        }
    };

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size);
        return view;
    }
    private Button button(int text) {
        Button view = text == R.string.drive_connect ? new MaterialButton(this)
            : new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        view.setText(text); view.setAllCaps(false); view.setMinHeight(dp(48));
        return view;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        folderName = getString(R.string.drive_root);
        waitingResolution = saved != null && saved.getBoolean("waiting_resolution", false);
        authorizing = waitingResolution;
        LinearLayout container = column();
        container.setPadding(dp(16), dp(8), dp(16), dp(8));
        // Android 15 enforces edge-to-edge. Consume actual system/IME insets without fixed bar heights.
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = android.os.Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()
                    | android.view.WindowInsets.Type.ime())
                : insets.getSystemWindowInsets();
            view.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom);
            return insets;
        });
        setContentView(container);
        container.requestApplyInsets();
        LinearLayout root = column();
        root.setFocusableInTouchMode(true);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.drive_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!parents.isEmpty()) goUp();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        TextView identity = label(AppDiagnostics.buildLabel(this),14);
        identity.setPadding(0,dp(8),0,dp(8));
        root.addView(identity);
        explanation = label(getString(R.string.drive_consent), 16);
        explanation.setPadding(0,dp(16),0,dp(12));
        root.addView(explanation);
        connect = button(R.string.drive_connect);
        connect.setOnClickListener(v -> authorize());
        root.addView(connect, new LinearLayout.LayoutParams(-1,-2));
        disconnect = button(R.string.drive_disconnect);
        disconnect.setOnClickListener(v -> disconnect());
        root.addView(disconnect, new LinearLayout.LayoutParams(-1,-2));

        queryRow = new LinearLayout(this);
        queryRow.setGravity(Gravity.CENTER_VERTICAL);
        TextInputLayout searchField = new TextInputLayout(this);
        searchField.setHint(getString(R.string.drive_search_hint));
        search = new TextInputEditText(searchField.getContext());
        search.setSingleLine(true); search.setTextSize(16); search.setMinHeight(dp(48));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            startSearch(); return true;
        });
        searchField.addView(search, new LinearLayout.LayoutParams(-1,-2));
        queryRow.addView(searchField, new LinearLayout.LayoutParams(0,-2,1));
        find = button(R.string.drive_search);
        LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(-2,-2);
        findParams.setMarginStart(dp(8));
        queryRow.addView(find, findParams);
        find.setOnClickListener(v -> startSearch());
        root.addView(queryRow);
        location = label(folderName,16);
        location.setPadding(0,dp(8),0,dp(8));
        location.setAccessibilityHeading(true);
        root.addView(location);
        up = button(R.string.drive_up);
        up.setOnClickListener(v -> goUp());
        root.addView(up, new LinearLayout.LayoutParams(-1,-2));
        loadingIndicator = new ProgressBar(this);
        root.addView(loadingIndicator, new LinearLayout.LayoutParams(dp(32),dp(32)));
        message = label("",14);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setPadding(0,dp(8),0,dp(8));
        root.addView(message);
        fileList = new ListView(this);
        fileList.addHeaderView(root, null, false);
        LinearLayout footer = column();
        more = button(R.string.drive_more);
        more.setOnClickListener(v -> load(true));
        footer.addView(more,new LinearLayout.LayoutParams(-1,-2));
        transfer = label("",14);
        footer.addView(transfer);
        fileList.addFooterView(footer, null, false);
        fileList.setAdapter(adapter);
        container.addView(fileList,new LinearLayout.LayoutParams(-1,-1));
        updateControls();
        if (DriveSession.INSTANCE.connected()) load(false);
        else message.setText(GoogleAuthorization.revoking.get() ? R.string.drive_revoking : R.string.drive_not_connected);
    }

    private void authorize() {
        if (authorizing || GoogleAuthorization.revoking.get()) return;
        authorizationPhase = "request";
        lastActivityResult = null; lastSdkStatus = null;
        authorizing = true;
        updateControls();
        message.setText(R.string.drive_authorizing);
        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(DriveApi.READONLY_SCOPE))).build();
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener(result -> {
                if (isDestroyed() || isFinishing()) return;
                if (result.hasResolution()) {
                    try {
                        if (result.getPendingIntent() == null) throw new IOException();
                        waitingResolution = true;
                        authorizationPhase = "google-consent";
                        startIntentSenderForResult(result.getPendingIntent().getIntentSender(), AUTHORIZE, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException | IOException error) {
                        authFailed(getString(R.string.drive_auth_failed));
                    }
                } else acceptAuthorization(result);
            })
            .addOnFailureListener(error -> {
                if (isDestroyed() || isFinishing()) return;
                int code = error instanceof ApiException ? ((ApiException)error).getStatusCode() : -1;
                authStatusFailed(code);
            });
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request,resultCode,data);
        if (request != AUTHORIZE) return;
        authorizationPhase = "callback";
        lastActivityResult = resultCode;
        waitingResolution = false;
        AuthorizationCallback.Result<AuthorizationResult> decoded = AuthorizationCallback.decode(resultCode,data,payload -> {
            try { return Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(payload); }
            catch (ApiException error) { throw new AuthorizationCallback.StatusFailure(error.getStatusCode()); }
        });
        if (decoded.status != null) authStatusFailed(decoded.status);
        else if (decoded.value != null) acceptAuthorization(decoded.value);
        else authFailed(getString(R.string.drive_auth_no_result,decoded.activityResult));
    }

    private void acceptAuthorization(AuthorizationResult result) {
        try {
            if (!result.getGrantedScopes().contains(DriveApi.READONLY_SCOPE)) {
                authFailed(getString(R.string.drive_scope_missing)); return;
            }
            DriveSession.INSTANCE.connect(result.getAccessToken());
            authorizationPhase = "authorized";
            authorizing = false; waitingResolution = false;
            clearBrowser();
            load(false);
        } catch (IllegalArgumentException error) { authFailed(getString(R.string.drive_auth_failed)); }
    }

    private void authFailed(String reason) {
        authorizing = false; waitingResolution = false;
        message.setText(getString(R.string.auth_failure_with_build,reason,AppDiagnostics.buildLabel(this))); updateControls();
    }

    private void authStatusFailed(int code) {
        lastSdkStatus = code;
        int reason;
        switch (code) {
            case 10: reason = R.string.drive_auth_config; break;
            case 7: case 15: reason = R.string.drive_auth_network; break;
            case 16: case 12501: reason = R.string.drive_auth_interrupted; break;
            default: reason = R.string.drive_auth_failed;
        }
        // Only a numeric SDK status is exposed. Never render provider messages or tokens.
        authFailed(getString(reason) + "\n" + getString(R.string.drive_auth_status,code));
    }

    private void disconnect() {
        try {
            String token = DriveSession.INSTANCE.lease().token();
            DriveSession.INSTANCE.disconnect();
            Identity.getAuthorizationClient(this).clearToken(ClearTokenRequest.builder().setToken(token).build());
        } catch (DriveSession.AuthorizationRequired ignored) { DriveSession.INSTANCE.disconnect(); }
        clearBrowser(); message.setText(R.string.drive_disconnected);
    }

    private void clearBrowser() {
        requestVersion++;
        if (listing != null) listing.cancel(true);
        loading = false;
        files.clear(); nextPage = ""; parents.clear(); folder = "root"; activeSearch = "";
        folderName = getString(R.string.drive_root);
        search.setText("");
        location.setText(folderName); adapter.notifyDataSetChanged();
        message.setText(R.string.drive_not_connected);
        updateControls();
    }

    private void updateControls() {
        wasConnected = DriveSession.INSTANCE.connected();
        wasRevoking = GoogleAuthorization.revoking.get();
        connect.setVisibility(wasConnected ? View.GONE : View.VISIBLE);
        connect.setEnabled(!authorizing && !wasRevoking);
        disconnect.setVisibility(wasConnected ? View.VISIBLE : View.GONE);
        disconnect.setEnabled(!authorizing);
        explanation.setVisibility(wasConnected ? View.GONE : View.VISIBLE);
        queryRow.setVisibility(wasConnected ? View.VISIBLE : View.GONE);
        location.setVisibility(wasConnected ? View.VISIBLE : View.GONE);
        search.setEnabled(wasConnected && !loading);
        find.setEnabled(wasConnected && !loading);
        up.setVisibility(parents.isEmpty() ? View.GONE : View.VISIBLE);
        up.setEnabled(!loading);
        more.setVisibility(nextPage.isEmpty() ? View.GONE : View.VISIBLE);
        more.setEnabled(!loading);
        loadingIndicator.setVisibility(loading || authorizing || wasRevoking ? View.VISIBLE : View.GONE);
        invalidateOptionsMenu();
    }

    private void startSearch() {
        if (loading || !DriveSession.INSTANCE.connected()) return;
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(search.getWindowToken(),0);
        search.clearFocus();
        activeSearch = search.getText().toString(); load(false);
    }

    private void load(boolean append) {
        DriveSession.Lease lease;
        try { lease = DriveSession.INSTANCE.lease(); }
        catch (DriveSession.AuthorizationRequired error) { clearBrowser(); return; }
        int version = ++requestVersion;
        if (listing != null) listing.cancel(true);
        String queryFolder = folder, query = activeSearch, page = append ? nextPage : "";
        if (!append) { files.clear(); nextPage = ""; adapter.notifyDataSetChanged(); }
        loading = true; updateControls(); message.setText(R.string.drive_loading);
        listing = network.submit(() -> {
            try {
                DriveApi.Page result = new DriveApi(GoogleAuthorization.transport(this,lease)).list(queryFolder,query,page);
                handler.post(() -> {
                    if (isDestroyed() || version != requestVersion) return;
                    try { lease.requireCurrent(); } catch (DriveSession.AuthorizationRequired error) { clearBrowser(); return; }
                    files.addAll(result.files); nextPage = result.nextToken;
                    loading = false; adapter.notifyDataSetChanged(); updateControls();
                    message.setText(files.isEmpty() ? getString(R.string.drive_empty) : getString(R.string.drive_file_count,files.size()));
                });
            } catch (IOException error) {
                handler.post(() -> {
                    if (isDestroyed() || version != requestVersion) return;
                    if (!DriveSession.INSTANCE.connected()) clearBrowser();
                    loading = false; updateControls();
                    message.setText(error.getMessage());
                });
            }
        });
    }

    private void enterFolder(DriveApi.FileEntry entry) {
        parents.push(new String[]{folder,folderName});
        folder = entry.id; folderName = entry.name; activeSearch = "";
        search.setText(""); location.setText(folderName); load(false);
    }
    private void goUp() {
        if (parents.isEmpty()) { finish(); return; }
        String[] previous = parents.pop(); folder = previous[0]; folderName = previous[1]; activeSearch = "";
        search.setText(""); location.setText(folderName); load(false);
    }

    private void download(DriveApi.FileEntry file) {
        if (!DriveSession.INSTANCE.connected()) { clearBrowser(); return; }
        if (DownloadService.snapshot.active) { message.setText(R.string.drive_download_busy); return; }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},702);
        Intent request = new Intent(this,DownloadService.class).setAction(DownloadService.ACTION_START);
        request.putExtra(DownloadService.EXTRA_DRIVE_ID,file.id);
        try { request.putExtra(DownloadService.EXTRA_DRIVE_SESSION,DriveSession.INSTANCE.lease().id()); }
        catch (DriveSession.AuthorizationRequired error) { clearBrowser(); return; }
        try { startForegroundService(request); message.setText(R.string.drive_download_started); }
        catch (RuntimeException error) { message.setText(R.string.drive_download_failed); }
    }

    private final class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return files.size(); }
        @Override public Object getItem(int n) { return files.get(n); }
        @Override public long getItemId(int n) { return n; }
        @Override public View getView(int n, View old, ViewGroup parent) {
            DriveApi.FileEntry entry = files.get(n);
            LinearLayout row = column(); row.setPadding(0,dp(12),0,dp(12));
            TextView name = label(entry.name,16); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name);
            String detail = entry.folder ? getString(R.string.drive_folder) : entry.downloadable
                ? android.text.format.Formatter.formatFileSize(DriveActivity.this,entry.size) : getString(R.string.drive_unsupported);
            TextView subtitle = label(detail,14); row.addView(subtitle);
            Button action = button(entry.folder ? R.string.drive_open_folder : R.string.drive_download);
            action.setContentDescription(getString(entry.folder ? R.string.drive_open_folder_named : R.string.drive_download_named,entry.name));
            action.setEnabled(!loading && (entry.folder || entry.downloadable && !DownloadService.snapshot.active));
            action.setOnClickListener(v -> { if (entry.folder) enterFolder(entry); else download(entry); });
            row.addView(action,new LinearLayout.LayoutParams(-1,-2));
            return row;
        }
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0,1,0,R.string.drive_revoke);
        menu.add(0,2,1,R.string.licenses);
        menu.add(0,3,2,R.string.diagnostics_title);
        return true;
    }
    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(1).setEnabled(!authorizing && !GoogleAuthorization.revoking.get());
        return super.onPrepareOptionsMenu(menu);
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { goUp(); return true; }
        if (item.getItemId() == 1) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.drive_revoke).setMessage(R.string.drive_revoke_explanation)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.drive_revoke,(d,w) -> revoke()).show();
            return true;
        }
        if (item.getItemId() == 2) { showLicenses(); return true; }
        if (item.getItemId() == 3) { showDiagnostics(); return true; }
        return super.onOptionsItemSelected(item);
    }
    private void revoke() {
        if (!GoogleAuthorization.revoking.compareAndSet(false,true)) return;
        disconnect();
        message.setText(R.string.drive_revoking); updateControls();
        Identity.getAuthorizationClient(this).revokeAccess(RevokeAccessRequest.builder()
            .setScopes(Collections.singletonList(new Scope(DriveApi.READONLY_SCOPE))).build())
            .addOnCompleteListener(result -> {
                GoogleAuthorization.lastRevokeSucceeded = result.isSuccessful();
                GoogleAuthorization.revoking.set(false);
                if (!isDestroyed()) {
                    message.setText(result.isSuccessful() ? R.string.drive_revoked : R.string.drive_revoke_failed);
                    updateControls();
                }
            });
    }
    private void showDiagnostics() {
        String details = AppDiagnostics.describe(this,authorizationPhase,lastActivityResult,lastSdkStatus);
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
                List<String> pages = new ArrayList<>();
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
            } catch (IOException error) { handler.post(() -> { if (!isDestroyed()) message.setText(R.string.licenses_unavailable); }); }
        },"gdirect-notices").start();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("waiting_resolution",waitingResolution); super.onSaveInstanceState(state); }
    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onDestroy() {
        requestVersion++; if (listing != null) listing.cancel(true); network.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
