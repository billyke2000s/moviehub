package local.tv.home;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in rendered frames and an independent host-audio calibration marker. */
final class VodAuditEvidence {

    private final Instrumentation instrumentation;
    private final File directory;

    VodAuditEvidence(Instrumentation instrumentation, Context context) throws Exception {
        this.instrumentation = instrumentation;
        directory = new File(context.getFilesDir(), "vod-evidence");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception(
            "Evidence directory unavailable"
        );
        File[] old = directory.listFiles();
        if (old != null) for (File file : old) {
            if (file.isFile() && !file.delete()) throw new Exception("Old evidence could not be cleared");
        }
    }

    JSONObject audioFixture(Context context) throws Exception {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        manager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        );
        short[] samples = new short[44100 * 2];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (8000 *
            Math.sin((2 * Math.PI * 440 * i) / 44100));
        AudioTrack track = new AudioTrack(
            AudioManager.STREAM_MUSIC,
            44100,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            samples.length * 2,
            AudioTrack.MODE_STATIC
        );
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new Exception(
            "Audio fixture could not initialize"
        );
        JSONObject result = new JSONObject().put("kind", "audio_fixture").put("frequencyHz", 440);
        try {
            if (track.write(samples, 0, samples.length) != samples.length) throw new Exception(
                "Audio fixture write failed"
            );
            result.put("startEpochMs", System.currentTimeMillis());
            track.play();
            SystemClock.sleep(2500);
            result.put("endEpochMs", System.currentTimeMillis());
        } finally {
            track.stop();
            track.release();
        }
        return result;
    }

    void capture(JSONObject result, String label) throws Exception {
        String id = result.getString("evidenceId");
        JSONArray frames = result.optJSONArray("renderedFrames");
        if (frames == null) {
            frames = new JSONArray();
            result.put("renderedFrames", frames);
        }
        for (int index = 0; index < 2; index++) {
            Bitmap full = instrumentation.getUiAutomation().takeScreenshot();
            if (full == null) throw new Exception("Rendered frame unavailable");
            Bitmap scaled = Bitmap.createScaledBitmap(full, 960, 540, true);
            String name = id + "-" + label + "-" + index + ".jpg";
            try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)) throw new Exception(
                    "Frame encoding failed"
                );
            } finally {
                if (scaled != full) scaled.recycle();
                full.recycle();
            }
            frames.put(
                new JSONObject()
                    .put("file", name)
                    .put("epochMs", System.currentTimeMillis())
                    .put("position", label)
            );
            SystemClock.sleep(500);
        }
    }
}
