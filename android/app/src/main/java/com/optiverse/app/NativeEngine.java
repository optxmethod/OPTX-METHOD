package com.optiverse.app;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.transformer.AudioEncoderSettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Device-side video engine. Uses the phone's own MediaCodec encoders/decoders through Media3 Transformer.
 * Must be started on the main thread. Reports progress / result through Callback.
 * Anything it cannot deliver is reported as an error so the page falls back to its JavaScript engine.
 */
@SuppressWarnings("UnsafeOptInUsageError")
final class NativeEngine {
    interface Callback { void progress(float p); void done(File out, double videoBitrate); void error(String msg); }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private boolean cancelled, finished;

    NativeEngine(Context c) { ctx = c; }

    void cancel() { cancelled = true; try { if (transformer != null) transformer.cancel(); } catch (Exception e) {} }

    void start(Uri uri, JSONObject o, File out, Callback cb) {
        try {
            String codec = o.optString("codec", "h265");
            final String mime = ("h264".equals(codec) || "avc".equals(codec)) ? MimeTypes.VIDEO_H264
                    : ("h265".equals(codec) || "hevc".equals(codec)) ? MimeTypes.VIDEO_H265 : null;
            if (mime == null) { cb.error("codec not supported natively"); return; }

            // ---- source geometry ----
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            int sw, sh, rot; double fps;
            try {
                r.setDataSource(ctx, uri);
                sw = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                sh = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                rot = rs == null ? 0 : Integer.parseInt(rs);
                String fs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                fps = fs == null ? 0 : Double.parseDouble(fs);
            } catch (Exception e) { cb.error("cannot read video: " + e.getMessage()); return; }
            finally { try { r.release(); } catch (Exception e) {} }
            if (rot % 180 != 0) { int t = sw; sw = sh; sh = t; }

            int addRot = ((o.optInt("rot", 0) % 360) + 360) % 360;
            if (addRot % 180 != 0) { int t = sw; sw = sh; sh = t; }
            double srcA = (double) sw / sh;

            // ---- target aspect ----
            double a = srcA; boolean newAr = false;
            String ar = o.optString("ar", "orig");
            if (!"orig".equals(ar)) {
                double aw = 0, ah = 0;
                if ("custom".equals(ar)) { aw = o.optDouble("arW", 0); ah = o.optDouble("arH", 0); }
                else { String[] p = ar.split(":"); if (p.length == 2) { aw = Double.parseDouble(p[0]); ah = Double.parseDouble(p[1]); } }
                if (aw > 0 && ah > 0) { a = aw / ah; newAr = Math.abs(a - srcA) > 0.002; }
            }

            // ---- target size ----
            int W, H;
            String res = o.optString("res", "0");
            int ow = o.optInt("w", 0), oh = o.optInt("h", 0), shortSide = 0;
            double scale = o.optDouble("scale", 100);
            if ("custom".equals(res) && ow > 0 && oh > 0) { W = ow; H = oh; newAr = true; }
            else {
                try { shortSide = (int) Double.parseDouble(res); } catch (Exception e) { shortSide = 0; }
                double fw, fh; // size of the aspect-matched frame at source resolution
                if (a >= srcA) { fw = sw; fh = sw / a; } else { fh = sh; fw = sh * a; }
                if (shortSide > 0 && Math.min(fw, fh) > shortSide) { double k = shortSide / Math.min(fw, fh); fw *= k; fh *= k; }
                else if (shortSide == 0 && scale > 0 && scale < 100) { fw *= scale / 100; fh *= scale / 100; }
                W = (int) Math.round(fw); H = (int) Math.round(fh);
            }
            W = Math.max(2, W / 2 * 2); H = Math.max(2, H / 2 * 2);

            // ---- video effects ----
            List<Effect> fx = new ArrayList<>();
            boolean flip = o.optBoolean("flip", false);
            if (addRot != 0 || flip) {
                ScaleAndRotateTransformation.Builder sr = new ScaleAndRotateTransformation.Builder();
                if (addRot != 0) sr.setRotationDegrees(addRot);
                if (flip) sr.setScale(-1f, 1f);
                fx.add(sr.build());
            }
            double tf = o.optDouble("fps", 0);
            if (tf > 0 && (fps <= 0 || tf < fps - 0.5)) fx.add(FrameDropEffect.createDefaultFrameDropEffect((float) tf));
            if (newAr || W != sw || H != sh) fx.add(Presentation.createForWidthAndHeight(W, H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));

            // ---- bitrates ----
            double vb = o.optDouble("vbr", 0);
            if (vb > 0 && vb < 1000) vb *= 1e6;
            if (vb <= 0) vb = Math.max(1.5e6, Math.min(40e6, W * (double) H * Math.max(24, tf > 0 ? tf : (fps > 0 ? fps : 30)) * 0.07));
            final int vbps = (int) vb;
            int abr = (int) o.optDouble("abr", 192);
            if (abr > 0 && abr < 1000) abr *= 1000;
            final boolean mute = o.optBoolean("mute", false);

            runAttempt(uri, fx, mime, vbps, abr > 0 ? abr : 192000, mute, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, out, cb, true);
        } catch (Exception e) { cb.error("native setup failed: " + e); }
    }

    private void runAttempt(final Uri src, final List<Effect> fx, final String mime, final int vbps, final int abr, final boolean mute,
                            final int mode, final File out, final Callback cb, final boolean retryVbr) {
        try {
            if (out.exists()) out.delete();
            VideoEncoderSettings vs = new VideoEncoderSettings.Builder().setBitrate(vbps).setBitrateMode(mode).build();
            AudioEncoderSettings as = new AudioEncoderSettings.Builder().setBitrate(abr).build();
            DefaultEncoderFactory ef = new DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(vs).setRequestedAudioEncoderSettings(as).setEnableFallback(false).build();

            List<AudioProcessor> ap = new ArrayList<>();
            EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(src))
                    .setRemoveAudio(mute).setEffects(new Effects(ap, fx)).build();
            Composition comp = new Composition.Builder(Collections.singletonList(new EditedMediaItemSequence(item)))
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build();

            transformer = new Transformer.Builder(ctx)
                    .setVideoMimeType(mime).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(ef)
                    .addListener(new Transformer.Listener() {
                        @Override public void onCompleted(Composition c, ExportResult r) {
                            finished = true;
                            cb.done(out, measureVideoBitrate(out));
                        }
                        @Override public void onError(Composition c, ExportResult r, ExportException e) {
                            finished = true;
                            if (cancelled) { cb.error("Cancelled."); return; }
                            if (retryVbr) { finished = false; runAttempt(src, fx, mime, vbps, abr, mute, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, out, cb, false); return; }
                            cb.error("export failed: " + e.getMessage());
                        }
                    }).build();
            transformer.start(comp, out.getAbsolutePath());
            poll(cb);
        } catch (Exception e) { cb.error("native start failed: " + e); }
    }

    private void poll(final Callback cb) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (finished || cancelled || transformer == null) return;
                ProgressHolder ph = new ProgressHolder();
                int st = transformer.getProgress(ph);
                if (st == Transformer.PROGRESS_STATE_AVAILABLE) cb.progress(Math.min(0.99f, ph.progress / 100f));
                main.postDelayed(this, 250);
            }
        }, 250);
    }

    /** Average bitrate (bits/s) of the finished file's video track, measured from real sample sizes. */
    static double measureVideoBitrate(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            int vt = -1; long dur = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fm = ex.getTrackFormat(i);
                String m = fm.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) { vt = i; if (fm.containsKey(MediaFormat.KEY_DURATION)) dur = fm.getLong(MediaFormat.KEY_DURATION); break; }
            }
            if (vt < 0 || dur <= 0) return 0;
            ex.selectTrack(vt);
            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            long bytes = 0;
            while (true) {
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                bytes += n;
                if (!ex.advance()) break;
            }
            return bytes * 8.0 / (dur / 1e6);
        } catch (Exception e) { return 0; }
        finally { try { ex.release(); } catch (Exception e) {} }
    }
}
