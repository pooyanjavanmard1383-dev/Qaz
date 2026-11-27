package com.pooyan.nyxora;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    static MainActivity inst;
    static final String HOST = "nyxora.local";
    private WebView web;
    private ValueCallback<Uri[]> chooser;

    /** Run JS in the page (safe to call from any thread / service). */
    static void js(final String code) {
        final MainActivity a = inst;
        if (a == null || a.web == null) return;
        a.runOnUiThread(new Runnable() {
            public void run() {
                try { a.web.evaluateJavascript(code, null); } catch (Exception e) { }
            }
        });
    }

    /** Stops reading after `left` bytes and closes the file descriptor. */
    static class Limited extends FilterInputStream {
        private long left;
        private final AssetFileDescriptor afd;
        Limited(InputStream in, long left, AssetFileDescriptor afd) { super(in); this.left = left; this.afd = afd; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int r = super.read();
            if (r >= 0) left--;
            return r;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = super.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
        @Override public void close() throws IOException {
            try { super.close(); } finally { try { afd.close(); } catch (Exception e) { } }
        }
    }

    public class Bridge {
        @JavascriptInterface
        public boolean hasPerm() { return perm(); }

        @JavascriptInterface
        public void askStorage() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 11);
                    } else {
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 11);
                    }
                }
            });
        }

        /** Whole music library as JSON: [{id,t,a,al,d,y}] */
        @JavascriptInterface
        public String scan() {
            if (!perm()) return "NOPERM";
            JSONArray out = new JSONArray();
            Cursor c = null;
            try {
                String[] proj = {
                        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED};
                String sel = MediaStore.Audio.Media.DURATION + ">=30000 AND " + MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
                        + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " + MediaStore.Audio.Media.IS_ALARM + "=0";
                c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                        MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
                if (c != null) {
                    while (c.moveToNext()) {
                        JSONObject o = new JSONObject();
                        o.put("id", c.getLong(0));
                        o.put("t", clean(c.getString(1)));
                        o.put("a", clean(c.getString(2)));
                        o.put("al", clean(c.getString(3)));
                        o.put("d", c.getLong(4));
                        o.put("y", c.getLong(5));
                        out.put(o);
                    }
                }
            } catch (Exception e) {
                return "[]";
            } finally {
                if (c != null) c.close();
            }
            return out.toString();
        }

        @JavascriptInterface
        public void vibrate(double ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                v.vibrate(VibrationEffect.createOneShot((long) ms, 90));
            } catch (Exception e) { }
        }

        /** Updates notification + lock-screen controls. */
        @JavascriptInterface
        public void media(double id, String title, String artist, boolean playing, double posMs, double durMs) {
            PlayerService.update(MainActivity.this, (long) id, title, artist, playing, (long) posMs, (long) durMs);
        }

        @JavascriptInterface
        public void stopMedia() { PlayerService.halt(MainActivity.this); }

        @JavascriptInterface
        public void share(final String title, final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, title));
                }
            });
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        if (s.equals("<unknown>")) return "";
        return s;
    }

    boolean perm() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        js("window.__perm&&window.__perm()");
    }

    private WebResourceResponse plain(int code, String reason) {
        Map<String, String> h = new HashMap<String, String>();
        return new WebResourceResponse("text/plain", "utf-8", code, reason, h, new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse asset(String name, String mime) {
        try {
            Map<String, String> h = new HashMap<String, String>();
            h.put("Cache-Control", "no-cache");
            return new WebResourceResponse(mime, "utf-8", 200, "OK", h, getAssets().open(name));
        } catch (Exception e) {
            return plain(404, "Not Found");
        }
    }

    private WebResourceResponse audio(long id, String range) {
        try {
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) return plain(404, "Not Found");
            long len = afd.getLength();
            if (len < 0) len = afd.getParcelFileDescriptor().getStatSize();
            String mime = getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("audio")) mime = "audio/mpeg";
            long start = 0, end = len - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-", -1);
                try {
                    if (p[0].length() > 0) start = Long.parseLong(p[0].trim());
                    if (p.length > 1 && p[1].trim().length() > 0) end = Long.parseLong(p[1].trim());
                    partial = true;
                } catch (Exception e) { start = 0; end = len - 1; partial = false; }
            }
            if (end > len - 1) end = len - 1;
            if (start > end) { afd.close(); return plain(416, "Range Not Satisfiable"); }
            InputStream in = afd.createInputStream();
            long skipped = 0;
            while (skipped < start) {
                long n = in.skip(start - skipped);
                if (n <= 0) {
                    if (in.read() < 0) break;
                    n = 1;
                }
                skipped += n;
            }
            if (skipped != start) { afd.close(); return plain(416, "Range Not Satisfiable"); }
            Map<String, String> h = new HashMap<String, String>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(end - start + 1));
            if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            InputStream lim = new Limited(in, end - start + 1, afd);
            return new WebResourceResponse(mime, null, partial ? 206 : 200, partial ? "Partial Content" : "OK", h, lim);
        } catch (Exception e) {
            return plain(404, "Not Found");
        }
    }

    private WebResourceResponse art(long id) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            m.setDataSource(this, uri);
            byte[] pic = m.getEmbeddedPicture();
            if (pic == null || pic.length == 0) return plain(404, "Not Found");
            Map<String, String> h = new HashMap<String, String>();
            h.put("Cache-Control", "max-age=604800");
            String mime = (pic.length > 3 && pic[1] == 'P' && pic[2] == 'N') ? "image/png" : "image/jpeg";
            return new WebResourceResponse(mime, null, 200, "OK", h, new ByteArrayInputStream(pic));
        } catch (Exception e) {
            return plain(404, "Not Found");
        } finally {
            try { m.release(); } catch (Exception e) { }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inst = this;
        getWindow().setStatusBarColor(Color.parseColor("#07070E"));
        getWindow().setNavigationBarColor(Color.parseColor("#07070E"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#07070E"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u == null || !HOST.equals(u.getHost())) return null;
                String p = u.getPath() == null ? "/" : u.getPath();
                try {
                    if (p.equals("/") || p.equals("/index.html")) return asset("index.html", "text/html");
                    if (p.startsWith("/audio/")) {
                        String range = null;
                        for (Map.Entry<String, String> e : r.getRequestHeaders().entrySet()) {
                            if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
                        }
                        return audio(Long.parseLong(p.substring(7)), range);
                    }
                    if (p.startsWith("/art/")) return art(Long.parseLong(p.substring(5)));
                } catch (Exception e) { }
                return plain(404, "Not Found");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try {
                    startActivityForResult(p.createIntent(), 31);
                } catch (Exception e) {
                    chooser = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 31 && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back?window.__back():false", new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) PlayerService.halt(this);
        if (inst == this) inst = null;
        super.onDestroy();
    }
}
