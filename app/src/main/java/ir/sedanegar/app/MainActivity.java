package ir.sedanegar.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_MIC = 1;
    private static final int REQ_NOTIF = 2;

    private WebView web;
    private PermissionRequest pendingPerm;

    private File outFile;
    private String outName = "recording.webm";
    private String outMime = "audio/webm";
    private OutputStream out;
    private MediaTool tool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(getResources().getColor(R.color.bg, getTheme()));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // فقط خود برنامه؛ هیچ لینک بیرونی باز نمی‌شود
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handlePermission(request));
            }
        });

        tool = new MediaTool(this, web);
        web.addJavascriptInterface(new Bridge(), "NativeBridge");
        web.loadUrl("https://" + HOST + "/assets/index.html");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void handlePermission(PermissionRequest req) {
        boolean wantsAudio = false;
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
        }
        if (!wantsAudio || !HOST.equals(req.getOrigin().getHost())) {
            req.deny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingPerm = req;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC && pendingPerm != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPerm.deny();
            }
            pendingPerm = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == MediaTool.REQ_PICK && res == RESULT_OK && data != null && tool != null) {
            tool.onPicked(data);
        }
    }

    @Override
    public void onBackPressed() {
        // با دکمه‌ی برگشت برنامه بسته نمی‌شود تا ضبط جاری قطع نشود
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        try { stopService(new Intent(this, RecordService.class)); } catch (Exception ignored) { }
        closeOut();
        if (tool != null) tool.destroy();
        if (web != null) {
            web.removeJavascriptInterface("NativeBridge");
            web.destroy();
        }
        super.onDestroy();
    }

    private void closeOut() {
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        out = null;
    }

    /** پل بین صفحه‌ی وب و اندروید */
    private class Bridge {
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void recording(final boolean on) {
            try {
                Intent i = new Intent(MainActivity.this, RecordService.class);
                if (on) startForegroundService(i);
                else stopService(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void pickMedia(String kind) {
            tool.pick(kind);
        }

        @JavascriptInterface
        public void compress(String kind, int videoBps, int audioBps, int shortSide) {
            tool.compress(kind, videoBps, audioBps, shortSide);
        }

        @JavascriptInterface
        public void cancelJob() {
            tool.cancel();
        }

        @JavascriptInterface
        public void shareResult() {
            tool.shareResult();
        }

        @JavascriptInterface
        public void begin(String name, String mime) {
            try {
                closeOut();
                File dir = new File(getCacheDir(), "share");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]+", "-").trim();
                outName = n.isEmpty() ? "recording.webm" : n;
                String m = (mime == null || mime.isEmpty()) ? "audio/webm" : mime;
                outMime = m.split(";")[0];
                outFile = new File(dir, outName);
                out = new BufferedOutputStream(new FileOutputStream(outFile));
            } catch (IOException e) {
                out = null;
            }
        }

        @JavascriptInterface
        public void chunk(String b64) {
            try {
                if (out != null) out.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                closeOut();
            }
        }

        @JavascriptInterface
        public boolean finish(String mode) {
            try {
                if (out == null) return false;
                out.close();
                out = null;
            } catch (IOException e) {
                return false;
            }
            return "share".equals(mode) ? shareFile() : saveToFolder();
        }
    }

    private boolean shareFile() {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", outFile);
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(outMime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri("", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, "ارسال ضبط")));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean saveToFolder() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, outMime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Sedanegar");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = getContentResolver();
            Uri item = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (item == null) return false;
            try (OutputStream os = cr.openOutputStream(item); InputStream in = new FileInputStream(outFile)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, v, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
