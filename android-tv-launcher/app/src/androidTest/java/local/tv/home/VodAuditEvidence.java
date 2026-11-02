package local.tv.home;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.MediaPlayer;

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

    private static final class Recording {

        final File directory;
        final String label;
        final MediaPlayer player;
        final long baselineBytes;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile String path;
        volatile boolean active;
        boolean accepted, stopRequested, capped;

        Recording(File directory, String label, MediaPlayer player, long baselineBytes) {
            this.directory = directory;
            this.label = label;
            this.player = player;
            this.baselineBytes = baselineBytes;
        }
    }

    private Recording current;

    void sample(VlcPlaybackActivity activity, JSONObject result, String label) throws Exception {
        begin(activity, result, label);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (
            result.optBoolean(label + "NativeRecordingStarted") && SystemClock.elapsedRealtime() < deadline
        ) {
            limit(activity);
            SystemClock.sleep(250);
        }
        finish(activity, result);
    }

    private long size(File file) {
        if (file.isFile()) return file.length();
        long bytes = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) bytes += size(child);
        return bytes;
    }

    void begin(VlcPlaybackActivity activity, JSONObject result, String label) throws Exception {
        File path = new File(directory, result.getString("evidenceId") + "-" + label);
        if (!path.isDirectory() && !path.mkdirs()) throw new Exception("Clip directory unavailable");
        Recording capture = new Recording(path, label, activity.player, size(directory));
        current = capture;
        instrumentation.runOnMainSync(() -> {
            if (activity.player != capture.player || capture.player == null) return;
            capture.player.setEventListener(event -> {
                // Preserve the app's active-player event routing. No stale player can drive its UI.
                if (activity.player == capture.player) activity.onPlayerEvent(event);
                if (event.type == MediaPlayer.Event.RecordChanged) {
                    if (event.getRecording()) {
                        capture.active = true;
                        capture.started.countDown();
                    } else if (capture.started.getCount() == 0) {
                        capture.active = false;
                        capture.path = event.getRecordPath();
                        capture.stopped.countDown();
                    }
                }
            });
            capture.accepted = capture.player.record(path.getAbsolutePath());
        });
        boolean started = capture.accepted && capture.started.await(5, TimeUnit.SECONDS);
        result.put(label + "NativeRecordingStarted", started);
    }

    private void stop(VlcPlaybackActivity activity, Recording capture) {
        if (capture.stopRequested || !capture.accepted || capture.stopped.getCount() == 0) return;
        capture.stopRequested = true;
        instrumentation.runOnMainSync(() -> {
            if (activity.player == capture.player) capture.player.record(null);
        });
    }

    void limit(VlcPlaybackActivity activity) {
        Recording capture = current;
        if (capture == null || !capture.active || capture.stopRequested) return;
        if (size(directory) - capture.baselineBytes >= 8 * 1024 * 1024) {
            stop(activity, capture);
            capture.capped = true;
        }
    }

    void finish(VlcPlaybackActivity activity, JSONObject result) throws Exception {
        Recording capture = current;
        if (capture == null) return;
        current = null;
        stop(activity, capture);
        boolean stopped = capture.accepted && capture.stopped.await(5, TimeUnit.SECONDS);
        JSONArray clips = result.optJSONArray("recordedClips");
        if (clips == null) {
            clips = new JSONArray();
            result.put("recordedClips", clips);
        }
        // RecordChanged supplies the actual closed-file path; do not guess from a timed directory scan.
        if (capture.path != null) {
            File nativeFile = new File(capture.path);
            if (
                nativeFile.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator) &&
                nativeFile.isFile() &&
                nativeFile.length() > 0
            ) {
                File target = new File(capture.directory, "sample-0.media");
                if (!nativeFile.equals(target) && !nativeFile.renameTo(target)) throw new Exception(
                    "Clip sanitization failed"
                );
            }
        }
        JSONArray names = new JSONArray();
        File[] files = capture.directory.listFiles();
        if (files != null) for (int index = 0; index < files.length; index++) {
            File file = files[index];
            if (!file.isFile() || file.length() == 0) continue;
            String name = file.getName().matches("sample-[0-9]+\\.media")
                ? file.getName()
                : "sample-" + (index + 1) + ".media";
            File target = new File(capture.directory, name);
            if (!file.equals(target) && !file.renameTo(target)) throw new Exception(
                "Clip sanitization failed"
            );
            names.put(name);
        }
        clips.put(
            new JSONObject()
                .put("position", capture.label)
                .put("directory", capture.directory.getName())
                .put("files", names)
                .put("capped", capture.capped)
                .put("eventsConfirmed", capture.started.getCount() == 0 && stopped)
        );
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
