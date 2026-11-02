package local.tv.home;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;

/** Opt-in public-network audit. Ordinary device tests never depend on third-party availability. */
@RunWith(AndroidJUnit4.class)
public final class FreeVodLiveAuditTest {

    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private File output;

    @Test
    public void auditEveryDiscoveredPlaybackSource() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit live audit only", "true".equals(arguments.getString("vodLiveAudit")));
        int shard = Integer.parseInt(arguments.getString("vodShard", "0"));
        int shards = Integer.parseInt(arguments.getString("vodShards", "1"));
        output = new File(context.getFilesDir(), "vod-live-audit.jsonl");
        try (FileWriter clear = new FileWriter(output, false)) {
            clear.write("");
        }
        boolean series = Boolean.parseBoolean(arguments.getString("vodSeries", "false"));
        String providers = arguments.getString("vodProviders", "");
        List<String> sites = providers.isEmpty()
            ? FreeVodProviders.ids(context, series)
            : new ArrayList<>(java.util.Arrays.asList(providers.split(",")));
        org.junit.Assert.assertFalse("No matching enabled movie websites selected", sites.isEmpty());
        record(
            new JSONObject()
                .put("kind", "inventory")
                .put("media", series ? "series" : "movies")
                .put("sites", new org.json.JSONArray(sites))
        );
        int sourceLimit = Integer.parseInt(arguments.getString("vodSourceLimit", "200"));
        boolean stopAfterVerified = Boolean.parseBoolean(
            arguments.getString("vodStopAfterVerified", "false")
        );
        int sampleSeconds = Integer.parseInt(arguments.getString("vodSampleSeconds", "20"));
        if (
            sourceLimit < 1 || sourceLimit > 200 || sampleSeconds < 10 || sampleSeconds > 120
        ) throw new IllegalArgumentException("Invalid audit sample limits");
        for (int index = shard; index < sites.size(); index += shards) {
            String site = sites.get(index);
            // Full, unambiguous catalogue identities. Runtime tolerance allows credits and alternate cuts.
            JSONObject[] films = {
                film("Inception", "2010", "tt1375666", 27205, 148),
                film("Dune: Part Two", "2024", "tt15239678", 693134, 166),
            };
            if (series) films = new JSONObject[] {
                film("Breaking Bad", "2008", "tt0903747", 1396, 58)
                    .put("season", 1)
                    .put("episode", 1)
                    .put("premiered", "2008-01-20")
                    .put("episodeTitle", "Pilot"),
            };
            if (!series && site.equals("scrubs:rarefilmm_com")) films = new JSONObject[] {
                film("Anna", "1967", "", 45486, 87),
            };
            int tested = 0;
            boolean verified = false;
            for (JSONObject film : films) {
                List<SourceRepository.Source> sources = new ArrayList<>();
                AtomicBoolean cancelled = new AtomicBoolean();
                java.util.Timer timer = new java.util.Timer(true);
                timer.schedule(
                    new java.util.TimerTask() {
                        @Override
                        public void run() {
                            cancelled.set(true);
                        }
                    },
                    150000
                );
                JSONObject lookup = new JSONObject()
                    .put("kind", "lookup")
                    .put("website", site)
                    .put("tmdb", film.getInt("tmdb"))
                    .put("season", film.optInt("season", -1))
                    .put("episode", film.optInt("episode", -1))
                    .put("title", film.getString("title"));
                try {
                    JSONObject report = new JSONObject(
                        FreeVodProviders.search(context, site, film, cancelled, sources::add)
                    );
                    lookup.put("status", report.optString("status")).put("sources", sources.size());
                } catch (Exception error) {
                    lookup.put("status", error.getClass().getSimpleName());
                } finally {
                    cancelled.set(true);
                    timer.cancel();
                }
                record(lookup);
                String sourceNames = arguments.getString("vodSourceNames", "");
                if (!sourceNames.isEmpty()) sources.removeIf(source -> {
                    String label = SourcesScreen.provider(source.title);
                    for (String name : sourceNames.split(",")) {
                        if (label.contains(" · " + name)) return false;
                    }
                    return true;
                });
                Set<String> keys = new HashSet<>();
                for (SourceRepository.Source source : sources) {
                    boolean separate = keys.add(VodSearch.sourceKey(source));
                    if (!separate) continue;
                    JSONObject result = new JSONObject()
                        .put("kind", "playback")
                        .put("website", site)
                        .put("provider", SourcesScreen.provider(source.title))
                        .put("advertisedQuality", SourcesScreen.quality(source.title))
                        .put("tmdb", film.getInt("tmdb"))
                        .put("season", film.optInt("season", -1))
                        .put("episode", film.optInt("episode", -1))
                        .put("title", film.getString("title"))
                        .put("separateSource", separate);
                    record(new JSONObject(result.toString()).put("kind", "starting"));
                    try {
                        play(source, film.optInt("runtimeMinutes"), sampleSeconds, result);
                    } catch (Exception error) {
                        // Exception messages and native logs may contain signed URLs: retain type only.
                        result.put("status", error.getClass().getSimpleName());
                    } finally {
                        ProviderRelay.testFailures = null;
                    }
                    record(result);
                    tested++;
                    verified = "verified".equals(result.optString("status"));
                    if ((stopAfterVerified && verified) || tested >= sourceLimit) break;
                }
                if (
                    (stopAfterVerified && verified) ||
                    tested >= sourceLimit ||
                    "unsupported_type".equals(lookup.optString("status"))
                ) break;
            }
            record(
                new JSONObject()
                    .put("kind", "site_finished")
                    .put("website", site)
                    .put("media", series ? "series" : "movies")
                    .put("testedSources", tested)
                    .put("verified", verified)
            );
        }
        record(new JSONObject().put("kind", "finished").put("shard", shard).put("shards", shards));
    }

    private JSONObject film(String title, String year, String imdb, int tmdb, int minutes) throws Exception {
        return new JSONObject()
            .put("title", title)
            .put("year", year)
            .put("imdb", imdb)
            .put("tmdb", tmdb)
            .put("runtimeMinutes", minutes)
            .put("season", -1)
            .put("episode", -1)
            .put("http", false)
            .put(
                "cinestreamAuditBackends",
                InstrumentationRegistry.getArguments().getString("vodCineStreamBackends", "")
            )
            .put(
                "cinestreamAudit",
                "true".equals(InstrumentationRegistry.getArguments().getString("vodCineStreamAllBackends"))
            );
    }

    private void record(JSONObject row) throws Exception {
        try (FileWriter writer = new FileWriter(output, true)) {
            writer.write(row.toString());
            writer.write('\n');
        }
    }

    private void play(
        SourceRepository.Source source,
        int expectedMinutes,
        int sampleSeconds,
        JSONObject result
    ) throws Exception {
        java.util.concurrent.atomic.AtomicReference<SourceRepository.Source> current =
            new java.util.concurrent.atomic.AtomicReference<>(source);
        java.util.concurrent.atomic.AtomicInteger refreshes = new java.util.concurrent.atomic.AtomicInteger();
        VodSources.Session session = new VodSources.Session(
            java.util.Collections.singletonList(VodSources.entry(source)),
            index -> {
                SourceRepository.Source next = current.get().fresh(false);
                current.set(next);
                return new VodSources.Resolved(next.uri, new org.json.JSONArray());
            },
            true
        );
        session.free = true;
        session.managed = true;
        session.expectedDuration = expectedMinutes * 60000L;
        session.refreshable = index -> current.get().refresh != null;
        session.refresh = index -> {
            refreshes.incrementAndGet();
            SourceRepository.Source next = current.get().fresh(true);
            current.set(next);
            return new VodSources.Resolved(next.uri, new org.json.JSONArray());
        };
        session.transport = index -> current.get().stream;
        List<String> transportFailures = new java.util.concurrent.CopyOnWriteArrayList<>();
        ProviderRelay.testFailures = error -> {
            // Class and source location only. Exception messages may contain signed URLs.
            StackTraceElement[] stack = error.getStackTrace();
            String origin =
                stack.length == 0
                    ? "unknown"
                    : stack[0].getClassName().replaceAll("[^A-Za-z0-9_.]", "_") +
                      ":" +
                      stack[0].getLineNumber();
            String code = error.getClass().getSimpleName() + ":" + origin;
            if (!transportFailures.contains(code) && transportFailures.size() < 20) transportFailures.add(
                code
            );
        };
        VlcPlaybackActivity activity = (VlcPlaybackActivity) instrumentation.startActivitySync(
            new Intent(context, VlcPlaybackActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("url", source.uri)
                .putExtra("title", "Individual source audit")
                .putExtra("vodSession", VodSources.remember(session))
        );
        ErrorLog.clear();
        try {
            long deadline = SystemClock.elapsedRealtime() + 45000;
            long[] sample = new long[8];
            boolean decoded = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                snapshot(activity, sample);
                if (sample[0] > 0 && sample[1] > 500 && sample[2] > 5 && sample[7] > 5) {
                    decoded = true;
                    break;
                }
                SystemClock.sleep(250);
            }
            long duration = sample[0];
            boolean plausible =
                expectedMinutes > 0 &&
                duration >= expectedMinutes * 60000L * .85 &&
                duration <= expectedMinutes * 60000L * 1.15;
            result
                .put("durationSeconds", duration / 1000)
                .put("expectedMinutes", expectedMinutes)
                .put("decodedFrames", sample[2])
                .put("decodedAudio", sample[5])
                .put("selectedAudioLanguage", audioLanguage(activity))
                .put("width", sample[3])
                .put("height", sample[4])
                .put("decoded", decoded)
                .put("plausibleRuntime", plausible);
            boolean smoothStart = decoded && plausible && observe(activity, sampleSeconds, result, "start");
            boolean seek = decoded && plausible && seekAndDecode(activity, duration / 2);
            boolean smoothMiddle = seek && observe(activity, sampleSeconds, result, "middle");
            boolean lateSeek = smoothMiddle && seekAndDecode(activity, (duration * 9) / 10);
            boolean smoothLate = lateSeek && observe(activity, 10, result, "late");
            snapshot(activity, sample);
            result
                .put("refreshes", refreshes.get())
                .put("smoothStart", smoothStart)
                .put("smoothMiddle", smoothMiddle)
                .put("lateSeekDecoded", lateSeek)
                .put("smoothLate", smoothLate)
                .put("decodedFrames", sample[2])
                .put("decodedAudio", sample[5])
                .put("playedAudioBuffers", sample[6])
                .put("displayedPictures", sample[7])
                .put("receivedBytes", activity.vod == null ? 0 : activity.vod.incomingBytes())
                .put("selectedAudioLanguage", audioLanguage(activity))
                .put("width", sample[3])
                .put("height", sample[4])
                .put("midpointDecoded", seek)
                .put("measured4k", decoded && sample[3] >= 3800 && sample[4] >= 1500)
                .put(
                    "status",
                    !decoded
                        ? "no_decoded_video"
                        : sample[5] == 0 && sample[6] == 0
                          ? "audio_unverified"
                          : expectedMinutes == 0
                            ? "runtime_unverified"
                            : !plausible
                              ? "runtime_mismatch"
                              : !seek
                                ? "seek_unverified"
                                : !lateSeek
                                  ? "late_seek_unverified"
                                  : !(smoothStart && smoothMiddle && smoothLate)
                                    ? "playback_stall_or_audio_output_unverified"
                                    : "verified"
                );
        } finally {
            org.json.JSONArray errors = new org.json.JSONArray();
            java.util.Set<String> uniqueErrors = new java.util.LinkedHashSet<>();
            for (ErrorLog.Entry error : ErrorLog.snapshot())
                uniqueErrors.add(error.where + ": " + error.type);
            for (String error : uniqueErrors) errors.put(error);
            result.put("transportErrors", errors);
            result.put("transportFailureOrigins", new org.json.JSONArray(transportFailures));
            ProviderRelay.testFailures = null;
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (!activity.isDestroyed() && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100);
        }
    }

    private boolean seekAndDecode(VlcPlaybackActivity activity, long target) {
        instrumentation.runOnMainSync(() -> {
            if (activity.player != null && activity.player.isSeekable()) activity.player.setTime(target);
        });
        long[] sample = new long[8];
        snapshot(activity, sample);
        long frames = sample[7],
            deadline = SystemClock.elapsedRealtime() + 20000;
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250);
            snapshot(activity, sample);
            // Some demuxers reset statistics on seek. Require fresh frames after that reset.
            if (sample[7] < frames) frames = sample[7];
            if (Math.abs(sample[1] - target) < 15000 && sample[7] > frames + 5) return true;
        }
        return false;
    }

    private boolean observe(VlcPlaybackActivity activity, int seconds, JSONObject result, String label)
        throws Exception {
        long[] sample = new long[8];
        snapshot(activity, sample);
        long firstTime = sample[1],
            firstVideo = sample[7],
            firstAudio = sample[6];
        long video = firstVideo,
            audio = firstAudio;
        long start = SystemClock.elapsedRealtime(),
            videoAt = start,
            audioAt = start;
        long videoFrames = 0,
            audioBuffers = 0;
        long maxVideoGap = 0,
            maxAudioGap = 0;
        while (SystemClock.elapsedRealtime() - start < seconds * 1000L) {
            SystemClock.sleep(250);
            snapshot(activity, sample);
            long now = SystemClock.elapsedRealtime();
            if (sample[7] > video) {
                videoFrames += sample[7] - video;
                videoAt = now;
            }
            if (sample[6] > audio) {
                audioBuffers += sample[6] - audio;
                audioAt = now;
            }
            video = sample[7];
            audio = sample[6];
            maxVideoGap = Math.max(maxVideoGap, now - videoAt);
            maxAudioGap = Math.max(maxAudioGap, now - audioAt);
        }
        long elapsed = SystemClock.elapsedRealtime() - start,
            progress = sample[1] - firstTime;
        boolean smooth =
            maxVideoGap <= 4000 &&
            maxAudioGap <= 4000 &&
            videoFrames > 5 &&
            audioBuffers > 0 &&
            progress >= elapsed * .8 &&
            progress <= elapsed * 1.3;
        result
            .put(label + "ObservationSeconds", elapsed / 1000)
            .put(label + "DisplayedPictures", videoFrames)
            .put(label + "DisplayedFps", (videoFrames * 1000.0) / Math.max(1, elapsed))
            .put(label + "VideoGapMs", maxVideoGap)
            .put(label + "AudioGapMs", maxAudioGap)
            .put(label + "ProgressMs", progress)
            .put(label + "PlayedAudioBuffers", audioBuffers);
        return smooth;
    }

    private String audioLanguage(VlcPlaybackActivity activity) {
        String[] language = { "" };
        instrumentation.runOnMainSync(() -> {
            MediaPlayer player = activity.player;
            if (player == null) return;
            IMedia media = player.getMedia();
            if (media == null) return;
            try {
                for (int index = 0; index < media.getTrackCount(); index++) {
                    IMedia.Track track = media.getTrack(index);
                    if (track instanceof IMedia.AudioTrack && track.id == player.getAudioTrack()) {
                        language[0] = (track.language == null ? "" : track.language).replaceAll(
                            "[^a-zA-Z-]",
                            ""
                        );
                        if (language[0].length() > 20) language[0] = "";
                    }
                }
            } finally {
                media.release();
            }
        });
        return language[0];
    }

    private void snapshot(VlcPlaybackActivity activity, long[] sample) {
        instrumentation.runOnMainSync(() -> {
            MediaPlayer player = activity.player;
            if (player == null) return;
            sample[0] = player.getLength();
            sample[1] = player.getTime();
            IMedia media = player.getMedia();
            if (media == null) return;
            try {
                IMedia.Stats stats = media.getStats();
                sample[2] = stats == null ? 0 : stats.decodedVideo;
                sample[5] = stats == null ? 0 : stats.decodedAudio;
                sample[6] = stats == null ? 0 : stats.playedAbuffers;
                sample[7] = stats == null ? 0 : stats.displayedPictures;
                for (int index = 0; index < media.getTrackCount(); index++) if (
                    media.getTrack(index) instanceof IMedia.VideoTrack
                ) {
                    IMedia.VideoTrack video = (IMedia.VideoTrack) media.getTrack(index);
                    sample[3] = video.width;
                    sample[4] = video.height;
                }
            } finally {
                media.release();
            }
        });
    }
}
