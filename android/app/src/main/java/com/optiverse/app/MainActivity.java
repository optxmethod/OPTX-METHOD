package com.optiverse.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private static final int REQ_FILE = 7;
    private static final String HOST = "optiverse.local";

    private final Map<String, Uri> picked = new ConcurrentHashMap<>();        // "name|size" -> content Uri
    private final Map<String, File> outputs = new ConcurrentHashMap<>();      // job id -> finished file
    private final Map<String, NativeEngine> jobs = new HashMap<>(); // running jobs
    private PowerManager.WakeLock wake;

    private static final String HOOK =
        "(function(){if(window.__obHook)return;window.__obHook=1;"
      + "async function send(u,n){try{var r=await fetch(u);var b=await r.blob();"
      + "if(window.__obLast&&b.size===window.__obLast.size){AndroidBridge.saveCached(window.__obLast.id,n||'');return}"
      + "var t=b.type||'video/mp4';var ext=(t.split('/')[1]||'mp4').split(';')[0];"
      + "AndroidBridge.begin(n||('OPTIVERSE_'+Date.now()+'.'+ext),t);"
      + "var S=1<<20;for(var o=0;o<b.size;o+=S){var a=new Uint8Array(await b.slice(o,o+S).arrayBuffer()),s='';"
      + "for(var i=0;i<a.length;i+=32768)s+=String.fromCharCode.apply(null,a.subarray(i,i+32768));AndroidBridge.chunk(btoa(s))}"
      + "AndroidBridge.end()}catch(e){AndroidBridge.fail(String(e))}}"
      + "var oc=HTMLAnchorElement.prototype.click;"
      + "HTMLAnchorElement.prototype.click=function(){if(this.href&&this.href.indexOf('blob:')===0&&this.hasAttribute('download')){send(this.href,this.download);return}return oc.apply(this,arguments)};"
      + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[download]');"
      + "if(a&&a.href.indexOf('blob:')===0){e.preventDefault();send(a.href,a.download)}},true)})();";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#07080a"));
        getWindow().setNavigationBarColor(Color.parseColor("#07080a"));
        if (Build.VERSION.SDK_INT < 29) requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        try { File[] old = getCacheDir().listFiles(); if (old != null) for (File f : old) if (f.getName().endsWith(".mp4")) f.delete(); } catch (Exception e) {}

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#07080a"));
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(web);
        final String home = getString(R.string.site_url);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("blob".equals(u.getScheme()) || "data".equals(u.getScheme())) return false;
                if (u.getHost() != null && u.getHost().equals(Uri.parse(home).getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) {}
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/out/")) {
                    String id = u.getLastPathSegment();
                    if (id != null && id.endsWith(".mp4")) id = id.substring(0, id.length() - 4);
                    File f = id == null ? null : outputs.get(id);
                    if (f != null && f.exists()) {
                        try {
                            Map<String, String> h = new HashMap<>();
                            h.put("Access-Control-Allow-Origin", "*");
                            h.put("Cache-Control", "no-store");
                            h.put("Content-Length", String.valueOf(f.length()));
                            return new WebResourceResponse("video/mp4", null, 200, "OK", h, new FileInputStream(f));
                        } catch (Exception e) {}
                    }
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(v, r);
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { v.evaluateJavascript(HOOK, null); }
            @Override public void onPageFinished(WebView v, String url) { v.evaluateJavascript(HOOK, null); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
                catch (Exception e) { pending = null; return false; }
                return true;
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url.startsWith("blob:")) web.evaluateJavascript(HOOK, null);
            else try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) {}
        });
        web.loadUrl(home);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && pending != null) {
            Uri[] r = WebChromeClient.FileChooserParams.parseResult(res, data);
            if (r != null) for (Uri u : r) remember(u);
            pending.onReceiveValue(r);
            pending = null;
        } else super.onActivityResult(req, res, data);
    }

    private void remember(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                String n = ni >= 0 ? c.getString(ni) : null; long sz = si >= 0 ? c.getLong(si) : -1;
                if (n != null) { picked.put(n + "|" + sz, u); picked.put(n + "|*", u); }
            }
        } catch (Exception e) {}
    }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }

    private void emit(final String id, final String type, final String a, final String b) {
        runOnUiThread(() -> web.evaluateJavascript("window.__obEvent&&window.__obEvent(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + JSONObject.quote(a) + "," + JSONObject.quote(b) + ")", null));
    }

    private void jobStarted() {
        try {
            Intent i = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            if (wake == null) { PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE); wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "optiverse:job"); }
            if (!wake.isHeld()) wake.acquire(4 * 60 * 60 * 1000L);
        } catch (Exception e) {}
    }
    private void jobEnded(String id) {
        jobs.remove(id);
        if (jobs.isEmpty()) {
            try { stopService(new Intent(this, KeepAliveService.class)); } catch (Exception e) {}
            try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception e) {}
        }
    }

    /** JavaScript bridge: native engine + saving of finished videos into Movies/OPTIVERSE. */
    private class Bridge {
        private OutputStream out; private Uri uri; private File file; private String mimeType;

        private void toast(final String t) { runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show()); }

        @JavascriptInterface public boolean isNative() { return true; }

        /** Maps the file the page picked to the content Uri the picker returned. Returns a token or "". */
        @JavascriptInterface public String resolve(String name, double size) {
            String k = name + "|" + (long) size;
            if (picked.containsKey(k)) return k;
            if (picked.containsKey(name + "|*")) return name + "|*";
            return "";
        }

        @JavascriptInterface public void start(final String id, final String token, final String json) {
            runOnUiThread(() -> {
                try {
                    Uri src = picked.get(token);
                    if (src == null) { emit(id, "err", "source file not found", ""); return; }
                    JSONObject o = new JSONObject(json);
                    jobStarted();
                    final NativeEngine eng = new NativeEngine(MainActivity.this);
                    jobs.put(id, eng);
                    File out = new File(getCacheDir(), id + ".mp4");
                    eng.start(src, o, out, new NativeEngine.Callback() {
                        @Override public void progress(float p) { emit(id, "p", String.valueOf(p), ""); }
                        @Override public void done(File f, double br) { outputs.put(id, f); jobEnded(id); emit(id, "done", String.valueOf(f.length()), String.valueOf(br)); }
                        @Override public void error(String m) { jobEnded(id); emit(id, "err", m, ""); }
                    });
                } catch (Exception e) { jobEnded(id); emit(id, "err", String.valueOf(e), ""); }
            });
        }

        @JavascriptInterface public void cancel(final String id) {
            runOnUiThread(() -> { NativeEngine e = jobs.get(id); if (e != null) e.cancel(); });
        }

        /** Copies a finished native result straight from cache to Movies/OPTIVERSE (no base64). */
        @JavascriptInterface public void saveCached(final String id, final String name) {
            final File f = outputs.get(id);
            if (f == null || !f.exists()) { toast("Save failed: result no longer available"); return; }
            new Thread(() -> {
                try {
                    String n = (name == null || name.isEmpty()) ? ("OPTIVERSE_" + System.currentTimeMillis() + ".mp4") : name.replaceAll("[^A-Za-z0-9._-]", "_");
                    if (!n.toLowerCase().endsWith(".mp4")) n += ".mp4";
                    OutputStream os; Uri u = null; File df = null;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Video.Media.DISPLAY_NAME, n);
                        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OPTIVERSE");
                        v.put(MediaStore.Video.Media.IS_PENDING, 1);
                        u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                        os = getContentResolver().openOutputStream(u);
                    } else {
                        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OPTIVERSE");
                        d.mkdirs(); df = new File(d, n); os = new FileOutputStream(df);
                    }
                    try (InputStream is = new FileInputStream(f)) { byte[] buf = new byte[1 << 20]; int r; while ((r = is.read(buf)) > 0) os.write(buf, 0, r); }
                    os.close();
                    if (u != null) { ContentValues v = new ContentValues(); v.put(MediaStore.Video.Media.IS_PENDING, 0); getContentResolver().update(u, v, null, null); }
                    else MediaScannerConnection.scanFile(MainActivity.this, new String[]{df.getAbsolutePath()}, new String[]{"video/mp4"}, null);
                    toast("Saved to Movies/OPTIVERSE");
                } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
            }).start();
        }

        // ---- fallback save path for videos produced by the in-page engine (chunked) ----
        @JavascriptInterface public synchronized void begin(String name, String mime) {
            try {
                name = name.replaceAll("[^A-Za-z0-9._-]", "_"); mimeType = mime;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Video.Media.MIME_TYPE, mime);
                    v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OPTIVERSE");
                    v.put(MediaStore.Video.Media.IS_PENDING, 1);
                    uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                    out = getContentResolver().openOutputStream(uri);
                } else {
                    File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OPTIVERSE");
                    d.mkdirs(); file = new File(d, name); out = new FileOutputStream(file);
                }
                toast("Saving video...");
            } catch (Exception e) { out = null; toast("Could not save: " + e.getMessage()); }
        }
        @JavascriptInterface public synchronized void chunk(String b64) {
            try { if (out != null) out.write(Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { fail(String.valueOf(e)); }
        }
        @JavascriptInterface public synchronized void end() {
            try {
                if (out == null) return;
                out.close(); out = null;
                if (uri != null) { ContentValues v = new ContentValues(); v.put(MediaStore.Video.Media.IS_PENDING, 0); getContentResolver().update(uri, v, null, null); }
                else if (file != null) MediaScannerConnection.scanFile(MainActivity.this, new String[]{file.getAbsolutePath()}, new String[]{mimeType}, null);
                uri = null; file = null;
                toast("Saved to Movies/OPTIVERSE");
            } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
        }
        @JavascriptInterface public synchronized void fail(String msg) {
            try { if (out != null) out.close(); } catch (Exception e) {}
            out = null; toast("Save failed: " + msg);
        }
    }
}
