package local.tv.home;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in actual rendered frames and bounded native media samples for content/audio review. */
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
            clear(file);
        }
    }

    private void clear(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) clear(child);
        if (!file.delete()) throw new Exception("Old evidence could not be cleared");
    }

    private File activeDirectory;
    private String activeLabel;
    private boolean recording;
    private boolean capped;

    void begin(VlcPlaybackActivity activity, JSONObject result, String label) throws Exception {
        activeLabel = label;
        activeDirectory = new File(directory, result.getString("evidenceId") + "-" + label);
        if (!activeDirectory.isDirectory() && !activeDirectory.mkdirs()) throw new Exception(
            "Clip directory unavailable"
        );
        boolean[] started = { false };
        instrumentation.runOnMainSync(() -> {
            if (activity.player != null) started[0] = activity.player.record(
                activeDirectory.getAbsolutePath()
            );
        });
        recording = started[0];
        capped = false;
        result.put(label + "NativeRecordingStarted", recording);
    }

    void limit(VlcPlaybackActivity activity) {
        if (!recording || activeDirectory == null) return;
        long bytes = 0;
        File[] files = activeDirectory.listFiles();
        if (files != null) for (File file : files) bytes += file.length();
        if (bytes >= 8 * 1024 * 1024) {
            instrumentation.runOnMainSync(() -> {
                if (activity.player != null) activity.player.record(null);
            });
            recording = false;
            capped = true;
        }
    }

    void finish(VlcPlaybackActivity activity, JSONObject result) throws Exception {
        if (activeDirectory == null) return;
        if (recording) instrumentation.runOnMainSync(() -> {
            if (activity.player != null) activity.player.record(null);
        });
        recording = false;
        // Native recording stops asynchronously. Allow its container to flush before export.
        SystemClock.sleep(1000);
        JSONArray clips = result.optJSONArray("recordedClips");
        if (clips == null) {
            clips = new JSONArray();
            result.put("recordedClips", clips);
        }
        JSONArray names = new JSONArray();
        File[] files = activeDirectory.listFiles();
        if (files != null) for (int index = 0; index < files.length; index++) {
            File file = files[index];
            if (!file.isFile() || file.length() == 0) continue;
            String name = "sample-" + index + ".media";
            // VLC's default filename can contain its input name. Export only neutral filenames.
            if (!file.renameTo(new File(activeDirectory, name))) throw new Exception(
                "Clip sanitization failed"
            );
            names.put(name);
        }
        clips.put(
            new JSONObject()
                .put("position", activeLabel)
                .put("directory", activeDirectory.getName())
                .put("files", names)
                .put("capped", capped)
        );
        activeDirectory = null;
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
