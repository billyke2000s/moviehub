package local.tv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Moves the full-screen Live TV channel to another copy while the one playing carries on (rules in
 * LiveUpgradePlan, core-tested): to a better copy as soon as one is found (checked once a second),
 * or, when the copy playing keeps pausing ({@link #replace}), to a smoother one. It uses the source
 * race's link for the chosen copy when the race already found it, else looks it up, and in both
 * cases checks it delivers stream data ({@link LiveProbe}) before anything changes on screen. Then
 * LiveStage plays it on trial: if it fails or does no better, the copy before comes back.
 * Main thread only, except the lookup and the check.
 */
final class LiveUpgrade {

    /** Copies cooling down after falling back, kept across channels for the app session. */
    static final LiveUpgradePlan.Cooldowns cooldowns = new LiveUpgradePlan.Cooldowns();
    /** How long an upgraded copy is on trial; device tests may shorten it. */
    static long trialMs = LiveUpgradePlan.TRIAL_MS;
    static final long TICK_MS = 1000;

    private static final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private final LiveStage stage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LiveUpgradePlan plan = new LiveUpgradePlan(cooldowns, trialMs);
    private final Runnable tick = this::tick;
    private int ticket;
    private NativeHttp http;
    private Future<?> task;
    /** The address of the copy playing before the upgrade, to go back to. */
    private String previousUrl;
    /** The copy looked for or on trial replaces one that kept pausing (not a better picture). */
    private boolean replacing;
    /** The copy chosen, loading silently in a second player until it really plays. */
    private ExoStandby loading;
    /**
     * The picture froze ({@link #rescue}): the copy playing is not playing at all, so a copy that
     * cannot be loaded leads on to the next, and {@link #giveUp} runs when none can.
     */
    private boolean urgent;
    private Runnable giveUp;
    /** How long a frozen picture waits for another copy to load before the usual failover. */
    static final long RESCUE_MS = 20_000;
    private final Runnable rescueTimeout = this::rescueTimedOut;

    LiveUpgrade(LiveStage stage) {
        this.stage = stage;
    }

    /** Whether {@code key} fell back recently and should not be chosen first. */
    static boolean cooling(String key, long now) {
        return cooldowns.active(key, now);
    }

    /** A new channel or a copy chosen another way: forget any upgrade, and keep watching. */
    void reset() {
        endUrgent();
        cancelLookup();
        plan = new LiveUpgradePlan(cooldowns, trialMs);
        previousUrl = null;
        replacing = false;
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    /** Full screen ended or MovieHub left: stop looking. */
    void stop() {
        endUrgent();
        cancelLookup();
        plan = new LiveUpgradePlan(cooldowns, trialMs);
        previousUrl = null;
        replacing = false;
        main.removeCallbacks(tick);
    }

    /** An upgraded copy is on trial: another source (the one before) is waiting. */
    boolean trial() {
        return plan.trial();
    }

    /**
     * The playing copy failed (an error, a missed start or the buffering rules). During a trial
     * this goes back to the copy before and returns true; otherwise it returns false and the
     * usual failover applies.
     */
    boolean failed() {
        if (!plan.trial()) return false;
        int failedCopy = plan.target();
        int back = plan.fallBack(SystemClock.elapsedRealtime());
        String url = previousUrl;
        previousUrl = null;
        if (back < 0 || url == null) return false;
        ExoLiveLog.event(
            (replacing ? "Other source did no better" : "Better source did not hold") +
                "; back to source " +
                (back + 1)
        );
        replacing = false;
        stage.upgradeFellBack(back, failedCopy, url);
        return true;
    }

    private void cancelLookup() {
        ticket++;
        if (http != null) http.cancel();
        if (task != null) task.cancel(true);
        http = null;
        task = null;
        if (loading != null) loading.release();
        loading = null;
    }

    private void tick() {
        main.postDelayed(tick, TICK_MS);
        if (!stage.upgradeWatching()) return;
        long now = SystemClock.elapsedRealtime();
        if (plan.held(now)) {
            ExoLiveLog.event(replacing ? "Smoother source kept" : "Better source kept");
            replacing = false;
        }
        if (plan.idle()) look(false);
    }

    /**
     * The copy playing keeps pausing (ExoLive's poor-picture rule): look for another copy and
     * check it while this one plays on. With nothing else to try, the copy playing stays.
     */
    void replace() {
        if (!stage.upgradeWatching() || !plan.idle()) return;
        if (!look(true)) ExoLiveLog.event("Picture keeps pausing; no other source to try, staying");
    }

    /**
     * The copy playing froze (ExoLive's stuck rule). Another copy is loaded in the background
     * while the frozen picture stays on screen (it may still recover by itself); it takes over
     * only once it really plays. Returns false when there is nothing to load, or a copy is on
     * trial (its failure brings the copy before back). Otherwise {@code whenNone} runs if no copy
     * takes over within {@link #RESCUE_MS}, or every copy fails to load.
     */
    boolean rescue(Runnable whenNone) {
        if (plan.trial()) return false;
        if (plan.looking()) {
            // Already looking for a smoother copy: it now hurries.
            startUrgent(whenNone);
            ExoLiveLog.event("Picture froze; still loading another source");
            return true;
        }
        if (!stage.upgradeWatching()) return false;
        startUrgent(whenNone);
        if (!look(true)) {
            endUrgent();
            return false;
        }
        ExoLiveLog.event("Picture froze; loading another source while it waits");
        return true;
    }

    private void startUrgent(Runnable whenNone) {
        urgent = true;
        giveUp = whenNone;
        main.removeCallbacks(rescueTimeout);
        main.postDelayed(rescueTimeout, RESCUE_MS);
    }

    private void endUrgent() {
        urgent = false;
        giveUp = null;
        main.removeCallbacks(rescueTimeout);
    }

    /** A copy failed to load during a rescue: the next one, or give up when none is left. */
    private void continueUrgent() {
        if (!urgent) return;
        if (ExoLive.isPlaying()) {
            ExoLiveLog.event("Frozen source recovered by itself");
            endUrgent();
            return;
        }
        if (!look(true)) giveUpNow();
    }

    private void rescueTimedOut() {
        if (!urgent) return;
        ExoLiveLog.event("No other source loaded within " + RESCUE_MS / 1000 + " s");
        // A copy taken from the race to load did not take over: the race is told so.
        if (loading != null && plan.looking()) undoRace(plan.target());
        cancelLookup();
        plan.notFound();
        giveUpNow();
    }

    private void giveUpNow() {
        Runnable whenNone = giveUp;
        endUrgent();
        if (whenNone != null) whenNone.run();
    }

    /** Chooses a copy to look for (a better one, or any as a {@code replacement}) and starts. */
    private boolean look(boolean replacement) {
        long now = SystemClock.elapsedRealtime();
        LiveZapper zapper = stage.zapper();
        if (zapper == null || !zapper.automaticSources()) return false;
        List<ChannelDatabase.Row> copies = zapper.copies();
        int current = zapper.selectedCopy();
        if (copies.size() <= 1 || current < 0 || current >= copies.size()) return false;
        LiveRace race = stage.race();
        // While the race is still choosing a copy to play, there is nothing to upgrade yet.
        if (race != null && !race.settled()) return false;
        int[] lines = new int[copies.size()];
        String[] keys = new String[copies.size()];
        boolean[] untried = new boolean[copies.size()];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = SourceRanking.resolution(copies.get(i).name);
            keys[i] = copies.get(i).key;
            // A copy the race is still looking up is left to it: its link arrives as a standby.
            untried[i] = zapper.untriedCopy(i) && (race == null || !race.lookingUp(i));
        }
        int index;
        if (replacement) index = plan.chooseReplacement(current, lines, keys, untried, now);
        else index = plan.choose(
            current,
            Math.max(lines[current], ExoLiveTracks.bestVideoLines()),
            lines,
            keys,
            untried,
            now
        );
        if (index < 0) return false;
        replacing = replacement;
        String kind = replacement ? "smoother" : "better";
        // Only looked at: the race keeps it until it is checked and actually played.
        LiveRace.Found standby = race != null && race.ready(index) ? race.peek(index) : null;
        if (standby != null) ExoLiveLog.event(
            "Checking " + kind + " source " + (index + 1) + ", already found by the race"
        );
        else ExoLiveLog.event("Looking up " + kind + " source " + (index + 1) + " while this one plays");
        lookUp(index, copies.get(index), zapper, standby);
        return true;
    }

    /**
     * Looks up copy {@code index} (unless the race already found it, {@code standby}) and checks it
     * delivers stream data, off the main thread; the copy playing carries on meanwhile.
     */
    private void lookUp(int index, ChannelDatabase.Row row, LiveZapper zapper, LiveRace.Found standby) {
        int mine = ++ticket;
        Context context = stage.activity().getApplicationContext();
        ChannelDatabase db = ChannelDatabase.get(context);
        boolean allowHttp = stage.allowHttp();
        NativeHttp request = new NativeHttp();
        http = request;
        boolean fromRace = standby != null;
        task = lookups.submit(() -> {
            LiveRace.Found found = standby;
            Exception failure = null;
            try {
                if (found == null) found = LiveRace.find(context, db, row, allowHttp, request);
                if (!delivers(found)) throw new java.io.IOException("No stream data");
            } catch (Exception e) {
                failure = e;
            }
            LiveRace.Found result = found;
            Exception error = failure;
            main.post(() -> arrived(mine, index, zapper, result, error, fromRace));
        });
    }

    /**
     * The lookup and check of copy {@code index} finished. {@code fromRace}: the link is the race's
     * standby, which the race still owns (and closes) unless it is taken here to play.
     */
    private void arrived(
        int mine,
        int index,
        LiveZapper zapper,
        LiveRace.Found found,
        Exception failure,
        boolean fromRace
    ) {
        if (mine != ticket || !plan.looking() || plan.target() != index) {
            if (!fromRace) close(found);
            return;
        }
        http = null;
        task = null;
        LiveRace race = stage.race();
        if (failure != null || found == null) {
            if (!fromRace) close(found);
            else if (race != null) race.drop(index);
            if (failure != null) Diagnostics.recovered("Checking another live source", failure);
            ExoLiveLog.event("Other source " + (index + 1) + " did not answer; staying");
            plan.notFound();
            // Tried: neither upgrades nor failover try it again this visit.
            zapper.raceFailed(new int[] { index });
            continueUrgent();
            return;
        }
        if (
            !stage.upgradeWatching() || stage.zapper() != zapper || zapper.selectedCopy() != plan.previous()
        ) {
            // The screen or the copy playing changed meanwhile: try again from what plays now.
            if (!fromRace) close(found);
            plan.notFound();
            if (urgent) giveUpNow();
            return;
        }
        LiveRace.Found taken = race == null ? null : race.take(index);
        if (fromRace) {
            if (taken == null) {
                // The race let go of its standby meanwhile (and closed it).
                plan.notFound();
                continueUrgent();
                return;
            }
            found = taken;
        } else close(taken);
        load(index, zapper, found);
    }

    /**
     * Loads copy {@code index} silently in a second player while the copy on screen carries on
     * (or stays frozen); it takes over only once it has really played (ExoStandby).
     */
    private void load(int index, LiveZapper zapper, LiveRace.Found found) {
        int mine = ticket;
        ExoLiveLog.event("Loading source " + (index + 1) + " in the background before switching");
        loading = new ExoStandby(
            stage.activity(),
            found.locator,
            found.source,
            new ExoStandby.Callback() {
                @Override
                public void ready(ExoStandby standby) {
                    loaded(mine, index, zapper, standby);
                }

                @Override
                public void failed(ExoStandby standby, String reason) {
                    notLoaded(mine, index, zapper, standby, reason);
                }
            }
        );
    }

    private void loaded(int mine, int index, LiveZapper zapper, ExoStandby standby) {
        if (mine != ticket || standby != loading || !plan.looking() || plan.target() != index) {
            standby.release();
            return;
        }
        loading = null;
        if (
            !stage.upgradeWatching() || stage.zapper() != zapper || zapper.selectedCopy() != plan.previous()
        ) {
            standby.release();
            undoRace(index);
            plan.notFound();
            if (urgent) giveUpNow();
            return;
        }
        if (urgent && ExoLive.isPlaying()) {
            // The frozen picture came back by itself while the other copy loaded: keep it.
            ExoLiveLog.event("Frozen source recovered by itself; staying");
            standby.release();
            undoRace(index);
            plan.notFound();
            endUrgent();
            return;
        }
        endUrgent();
        begin(index, standby);
    }

    private void notLoaded(int mine, int index, LiveZapper zapper, ExoStandby standby, String reason) {
        standby.release();
        if (mine != ticket || standby != loading) return;
        loading = null;
        ExoLiveLog.event("Other source " + (index + 1) + " " + reason + "; staying");
        undoRace(index);
        plan.notFound();
        // Tried: neither upgrades nor failover try it again this visit.
        zapper.raceFailed(new int[] { index });
        continueUrgent();
    }

    /** The race was told copy {@code index} plays; it did not, so the copy before still does. */
    private void undoRace(int index) {
        LiveRace race = stage.race();
        if (race != null && plan.previous() >= 0) race.fellBack(plan.previous(), index);
    }

    private void begin(int index, ExoStandby standby) {
        previousUrl = stage.playbackUrl();
        plan.started(SystemClock.elapsedRealtime());
        ExoLiveLog.event(
            (replacing ? "Moving to smoother source " : "Moving to better source ") +
                (index + 1) +
                " on trial"
        );
        stage.upgradeTo(index, standby, replacing);
    }

    private static void close(LiveRace.Found found) {
        if (found != null && found.source != null && found.source.relay != null) found.source.relay.close();
    }

    /**
     * Whether a found copy delivers stream data, so a dead copy is never switched to over a
     * working one: the address the player would open (its relay, else its link), with the
     * provider's headers, checked by {@link LiveProbe}.
     */
    private static boolean delivers(LiveRace.Found found) {
        if (found == null || found.source == null) return false;
        LiveStream.Resolution source = found.source;
        String address = source.relay != null ? source.relay.url : source.url;
        if (address == null) return false;
        EngineSources.Stream stream = source.provider;
        String userAgent =
            stream != null
                ? stream.userAgent
                : PlutoSource.nativeUrl(found.locator)
                  ? PlutoSource.USER_AGENT
                  : null;
        return LiveProbe.answers(address, userAgent, stream == null ? null : stream.referer);
    }
}
