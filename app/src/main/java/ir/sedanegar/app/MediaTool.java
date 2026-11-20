package ir.sedanegar.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.webkit.WebView;

import androidx.core.content.FileProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** انتخاب صدا/ویدیو از گوشی و کم‌حجم کردن آن با کدک‌های خود اندروید. */
class MediaTool {
    static final int REQ_PICK = 11;

    private final Activity act;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String kind = "audio";
    private boolean extract;
    private Uri inUri;
    private String inName = "";
    private long inSize;
    private int vw, vh;

    private Transformer tf;
    private File outFile;
    private String outName = "";
    private boolean running;

    MediaTool(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    // ---------- JS events ----------
    private void emit(final JSONObject o) {
        ui.post(() -> web.evaluateJavascript(
                "window.__native&&window.__native.onEvent(" + o.toString() + ")", null));
    }

    private void emit(String type) {
        try { emit(new JSONObject().put("type", type)); } catch (Exception ignored) { }
    }

    // ---------- pick ----------
    void pick(final String k) {
        extract = "extract".equals(k);
        kind = (extract || "video".equals(k)) ? "video" : "audio";
        ui.post(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(kind + "/*");
                act.startActivityForResult(i, REQ_PICK);
            } catch (Exception e) {
                emit("pickfail");
            }
        });
    }

    void onPicked(Intent data) {
        JSONObject o = new JSONObject();
        try {
            Uri u = data == null ? null : data.getData();
            if (u == null) { emit(o.put("type", "picked").put("ok", false)); return; }
            inUri = u;
            inName = "";
            inSize = 0;
            ContentResolver cr = act.getContentResolver();
            try (Cursor c = cr.query(u, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) inName = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) inSize = c.getLong(si);
                }
            } catch (Exception ignored) { }
            if (inName == null || inName.isEmpty()) inName = "file";

            long durMs = 0, bitrate = 0;
            vw = 0;
            vh = 0;
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            try {
                m.setDataSource(act, u);
                durMs = parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                bitrate = parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
                if ("video".equals(kind)) {
                    int w = (int) parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    int h = (int) parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    int rot = (int) parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                    if (rot == 90 || rot == 270) { vw = h; vh = w; } else { vw = w; vh = h; }
                }
            } catch (Exception ignored) {
            } finally {
                try { m.release(); } catch (Exception ignored) { }
            }
            if (inSize <= 0) {
                try (android.content.res.AssetFileDescriptor fd = cr.openAssetFileDescriptor(u, "r")) {
                    if (fd != null) inSize = fd.getLength();
                } catch (Exception ignored) { }
            }
            boolean ok = inSize > 0 && durMs > 0;
            o.put("type", "picked").put("ok", ok).put("kind", extract ? "extract" : kind).put("name", inName)
                    .put("size", inSize).put("duration", durMs / 1000.0)
                    .put("bitrate", bitrate).put("width", vw).put("height", vh);
            emit(o);
        } catch (Exception e) {
            emit("pickfail");
        }
    }

    private boolean videoOut() {
        return "video".equals(kind) && !extract;
    }

    /** تبدیل یک فایل صوتی (مثلاً webm) به m4a/AAC؛ روی نخ اصلی اجرا می‌شود. */
    void transcodeAudio(final File in, final File out, final int bps, final Runnable ok, final Runnable fail) {
        ui.post(() -> {
            try {
                DefaultEncoderFactory ef = new DefaultEncoderFactory.Builder(act)
                        .setRequestedAudioEncoderSettings(new AudioEncoderSettings.Builder().setBitrate(bps).build())
                        .build();
                Transformer t = new Transformer.Builder(act)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(ef)
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult result) {
                                ok.run();
                            }

                            @Override
                            public void onError(Composition composition, ExportResult result, ExportException e) {
                                fail.run();
                            }
                        })
                        .build();
                EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(in)))
                        .setRemoveVideo(true).build();
                t.start(item, out.getAbsolutePath());
            } catch (Exception e) {
                fail.run();
            }
        });
    }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    // ---------- compress ----------
    void compress(String k, final int videoBps, final int audioBps, final int shortSide) {
        ui.post(() -> {
            if (running || inUri == null) return;
            try {
                final boolean video = videoOut();
                File dir = new File(act.getCacheDir(), "share");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();

                String base = inName;
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                base = base.replaceAll("[\\\\/:*?\"<>|]+", "-").trim();
                if (base.isEmpty()) base = "file";
                outName = base + (extract ? " (صدا)" : " (کم‌حجم)") + (video ? ".mp4" : ".m4a");
                outFile = new File(dir, outName);

                DefaultEncoderFactory.Builder eb = new DefaultEncoderFactory.Builder(act)
                        .setRequestedAudioEncoderSettings(
                                new AudioEncoderSettings.Builder().setBitrate(audioBps).build());
                List<Effect> vfx = new ArrayList<>();
                if (video) {
                    eb.setRequestedVideoEncoderSettings(
                            new VideoEncoderSettings.Builder().setBitrate(videoBps).build());
                    int s = Math.min(vw, vh);
                    if (shortSide > 0 && s > shortSide) {
                        double f = (double) shortSide / s;
                        int th = (int) (Math.round(vh * f / 2.0) * 2);
                        vfx.add(Presentation.createForHeight(th));
                    }
                }

                tf = new Transformer.Builder(act)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(eb.build())
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult result) {
                                finishOk();
                            }

                            @Override
                            public void onError(Composition composition, ExportResult result, ExportException e) {
                                finishFail();
                            }
                        })
                        .build();

                EditedMediaItem.Builder ib = new EditedMediaItem.Builder(MediaItem.fromUri(inUri))
                        .setEffects(new Effects(Collections.<AudioProcessor>emptyList(), vfx));
                if (!video) ib.setRemoveVideo(true);

                running = true;
                setBusy(true);
                tf.start(ib.build(), outFile.getAbsolutePath());
                pollProgress();
            } catch (Exception e) {
                finishFail();
            }
        });
    }

    private void pollProgress() {
        if (!running || tf == null) return;
        ProgressHolder ph = new ProgressHolder();
        int st = tf.getProgress(ph);
        if (st == Transformer.PROGRESS_STATE_AVAILABLE) {
            try { emit(new JSONObject().put("type", "progress").put("p", ph.progress)); } catch (Exception ignored) { }
        }
        ui.postDelayed(this::pollProgress, 500);
    }

    void cancel() {
        ui.post(() -> {
            if (tf != null && running) {
                running = false;
                try { tf.cancel(); } catch (Exception ignored) { }
            }
            setBusy(false);
        });
    }

    private void setBusy(boolean on) {
        try {
            Intent i = new Intent(act, WorkService.class);
            if (on) act.startForegroundService(i); else act.stopService(i);
        } catch (Exception ignored) { }
        if (on) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void finishFail() {
        running = false;
        setBusy(false);
        emit("error");
    }

    private void finishOk() {
        running = false;
        setBusy(false);
        final long outSize = outFile == null ? 0 : outFile.length();
        final boolean video = videoOut();
        new Thread(() -> {
            boolean saved = false;
            if (outSize > 0 && (extract || outSize < inSize)) saved = saveToFolder(video);
            try {
                emit(new JSONObject().put("type", "done").put("in", inSize)
                        .put("out", outSize).put("saved", saved));
            } catch (Exception e) {
                emit("error");
            }
        }).start();
    }

    private boolean saveToFolder(boolean video) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "audio/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? "Movies" : "Music") + "/Sedanegar");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver cr = act.getContentResolver();
            Uri coll = video
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = cr.insert(coll, v);
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

    void shareResult() {
        ui.post(() -> {
            try {
                if (outFile == null || !outFile.exists()) return;
                Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".files", outFile);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(videoOut() ? "video/mp4" : "audio/mp4");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri("", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.startActivity(Intent.createChooser(send, "ارسال فایل"));
            } catch (Exception ignored) { }
        });
    }

    void destroy() {
        try { if (tf != null && running) tf.cancel(); } catch (Exception ignored) { }
        running = false;
    }
}
