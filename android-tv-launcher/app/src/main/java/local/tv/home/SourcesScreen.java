package local.tv.home;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Stream source lookup (Torrentio, MediaFusion, Comet) and the source picker. */
final class SourcesScreen {

    private final MainActivity activity;

    static final class Binge {
        static String imdb, title, kind;
        static int season, episode;
        static JSONObject metadata;
        static JSONArray files;
        static void remember(String imdbId, int s, int e, String label, String type, JSONObject info, JSONArray list) {
            imdb = imdbId; season = s; episode = e; title = label; kind = type;
            metadata = info; files = list;
        }
        static void next(MainActivity activity) {
            if (imdb == null || episode < 1) return;
            int nextEpisode = episode + 1;
            JSONObject nextMetadata;
            try {
                nextMetadata = metadata == null ? new JSONObject() : new JSONObject(metadata.toString());
            } catch (JSONException error) {
                nextMetadata = new JSONObject();
            }
            try {
                nextMetadata.put("season", season).put("episode", nextEpisode);
                String key = nextMetadata.optString("episodeKey", "");
                if (!key.isEmpty()) {
                    int last = key.lastIndexOf(':');
                    if (last > 0) nextMetadata.put("episodeKey", key.substring(0, last + 1) + nextEpisode);
                }
            } catch (Exception ignored) { }
            if (files != null) {
                List<String> paths = new ArrayList<>();
                List<Long> sizes = new ArrayList<>();
                for (int i = 0; i < files.length(); i++) {
                    JSONObject file = files.optJSONObject(i);
                    if (file == null) continue;
                    paths.add(file.optString("path", file.optString("name", "")));
                    sizes.add(file.optLong("size"));
                }
                int match = VideoFiles.autoPick(paths, sizes, season, nextEpisode);
                if (match >= 0) {
                    JSONObject file = files.optJSONObject(match);
                    String link = file == null ? "" : file.optString("link");
                    if (!link.isEmpty()) {
                        remember(imdb, season, nextEpisode, title, kind, nextMetadata, files);
                        activity.play(title + " · S" + season + " E" + nextEpisode, link,
                            activity.id(link), false, nextMetadata);
                        return;
                    }
                }
            }
            remember(imdb, season, nextEpisode, title, kind, nextMetadata, null);
            activity.playbackTitle = nextMetadata;
            activity.sources.torrentSources(imdb, season, nextEpisode,
                title + " · S" + season + " E" + nextEpisode, kind);
        }
    }

    SourcesScreen(MainActivity activity) {
        this.activity = activity;
    }

    /** "4K", "1080p", "720p" or "SD", from a source's description. */
    static String quality(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("2160p") || lower.contains("4k")
            ? "4K"
            : lower.contains("1080p")
              ? "1080p"
              : lower.contains("720p")
                ? "720p"
                : "SD";
    }

    /** Badges for a source: HDR or Dolby Vision, codec and file size, when the description says. */
    static List<String> details(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (
            lower.contains("dolby vision") ||
            java.util.regex.Pattern.compile("\\b(?:dv|dovi)\\b").matcher(lower).find()
        ) out.add("Dolby Vision");
        else if (lower.contains("hdr")) out.add("HDR");
        if (lower.contains("h265") || lower.contains("hevc") || lower.contains("x265")) out.add("HEVC");
        else if (lower.contains("h264") || lower.contains("x264")) out.add("H.264");
        java.util.regex.Matcher size = java.util.regex.Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s?(TB|GB|MB)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(raw);
        if (size.find()) out.add(
            size.group(1).replace(',', '.') + " " + size.group(2).toUpperCase(Locale.ROOT)
        );
        return out;
    }

    /** The provider line (the first line of the description). */
    static String provider(String raw) {
        int split = raw.indexOf('\n');
        String name = (split >= 0 ? raw.substring(0, split) : "Source").trim();
        return name.isEmpty() ? "Source" : name;
    }

    /** The release name: the description without its provider line, on one line. */
    static String release(String raw) {
        int split = raw.indexOf('\n');
        return (split >= 0 ? raw.substring(split + 1) : raw).replace('\n', ' ').trim();
    }

    void torrentSources(String imdb, int season, int episode, String title, String kind) {
        final JSONObject metadata = activity.playbackTitle;
        activity.screen("", () -> {
            if (metadata != null && metadata.has("episode")) activity.seriesScreens.seasons(
                metadata.optInt("id"),
                metadata.optString("name", title),
                metadata.optInt("season", 1)
            );
            else if (metadata != null) activity.posters.tmdbTitle(metadata);
            else activity.catalogues.catalogueMenu(kind);
        });
        activity.posters.listBackdrop(metadata);
        activity.pageTitle(
            activity.page,
            "Choose a source",
            title,
            "Ready-to-play sources start straight away. Others are sent to your cloud first."
        );
        if (!activity.secrets.has("premiumize")) {
            activity.emptyState(
                activity.page,
                "Connect Premiumize",
                "Check available sources and play from your cloud account.",
                "Connect Premiumize",
                () -> activity.settingsScreens.accountScreen("Premiumize")
            );
            return;
        }
        LinearLayout tools = activity.horizontal(activity.page);
        activity.chip(tools, "Premiumize cloud", () -> activity.cloudScreens.cloud("", kind));
        activity.chip(tools, "Transfers", () -> activity.cloudScreens.transfers(kind));
        TextView loading = activity.muted("Loading sources and checking your Premiumize cache…", 16);
        activity.page.addView(loading);
        SourceRepository repo = new SourceRepository(activity.secrets);
        activity.async(
            () -> repo.torrentio(imdb, season, episode),
            sources -> {
                activity.page.removeView(loading);
                if (sources.isEmpty()) {
                    activity.emptyState(
                        activity.page,
                        "No sources found",
                        "Try again later or choose a file from your cloud.",
                        "Open cloud",
                        () -> activity.cloudScreens.cloud("", kind)
                    );
                    return;
                }
                LinearLayout filters = activity.horizontal(activity.page),
                    results = activity.vertical();
                activity.page.addView(results);
                List<Button> tabs = new ArrayList<>();
                for (String quality : new String[] { "All", "4K", "1080p", "720p" }) {
                    Button tab = activity.chip(filters, quality, () -> {});
                    tabs.add(tab);
                    tab.setOnClickListener(v -> {
                        for (Button other : tabs) {
                            other.setSelected(other == v);
                            activity.styleFocus(other, other.hasFocus());
                        }
                        renderSources(
                            results,
                            sources,
                            quality,
                            repo,
                            metadata,
                            imdb,
                            season,
                            episode,
                            title,
                            kind
                        );
                    });
                }
                tabs.get(0).setSelected(true);
                activity.styleFocus(tabs.get(0), false);
                renderSources(results, sources, "All", repo, metadata, imdb, season, episode, title, kind);
            }
        );
    }

    void renderSources(
        LinearLayout target,
        List<SourceRepository.Source> sources,
        String quality,
        SourceRepository repo,
        JSONObject metadata,
        String imdb,
        int season,
        int episode,
        String title,
        String kind
    ) {
        target.removeAllViews();
        int shown = 0;
        for (SourceRepository.Cache status : SourceRepository.Cache.values()) {
            List<SourceRepository.Source> group = new ArrayList<>();
            for (SourceRepository.Source source : sources)
                if (
                    source.cache == status && (quality.equals("All") || quality(source.title).equals(quality))
                ) group.add(source);
            if (group.isEmpty()) continue;
            shown += group.size();
            activity.section(
                target,
                (status == SourceRepository.Cache.CACHED
                    ? "Ready to play"
                    : status == SourceRepository.Cache.NOT_CACHED
                      ? "Available to transfer"
                      : "Cache check unavailable") +
                    " · " +
                    group.size()
            );
            for (SourceRepository.Source source : group) {
                LinearLayout card = activity.listRow();
                TextView grade = activity.rowText(quality(source.title), 20, false, 1);
                grade.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                grade.setGravity(Gravity.CENTER);
                card.addView(grade, new LinearLayout.LayoutParams(activity.dp(84), -2));
                LinearLayout copy = activity.vertical();
                LinearLayout top = activity.row();
                TextView label = activity.rowText(provider(source.title), 17, false, 1);
                label.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                top.addView(label, new LinearLayout.LayoutParams(-2, -2));
                for (String detail : details(source.title)) {
                    LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(-2, -2);
                    badgeLayout.leftMargin = activity.dp(8);
                    top.addView(activity.badge(detail), badgeLayout);
                }
                copy.addView(top);
                copy.addView(activity.rowText(release(source.title), 13, true, 1));
                card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                TextView next = activity.rowText(
                    source.cache == SourceRepository.Cache.CACHED
                        ? "▶  Play"
                        : source.cache == SourceRepository.Cache.NOT_CACHED
                          ? "Send to cloud"
                          : "Check again",
                    15,
                    false,
                    1
                );
                LinearLayout.LayoutParams nextLayout = new LinearLayout.LayoutParams(-2, -2);
                nextLayout.leftMargin = activity.dp(16);
                card.addView(next, nextLayout);
                activity.styleListRow(card, false);
                card.setOnClickListener(v -> {
                    if (source.cache == SourceRepository.Cache.UNKNOWN) {
                        activity.playbackTitle = metadata;
                        torrentSources(imdb, season, episode, title, kind);
                        return;
                    }
                    if (source.cache == SourceRepository.Cache.NOT_CACHED) {
                        SidePanel.of(activity, "Not ready to play yet")
                            .message(
                                "This source is not in your Premiumize cache yet. Send it to your cloud and it will appear in Transfers; it can be played once it finishes."
                            )
                            .action("Send to cloud", () ->
                                activity.async(
                                    () -> repo.queue(source),
                                    id -> {
                                        activity.toast("Transfer queued");
                                        activity.cloudScreens.transfers(kind);
                                    }
                                )
                            )
                            .action("Cancel", null)
                            .show();
                        return;
                    }
                    card.setEnabled(false);
                    label.setText("Opening source…");
                    activity.async(
                        () -> {
                            try {
                                return repo.direct(source);
                            } finally {
                                activity.runOnUiThread(() -> {
                                    card.setEnabled(true);
                                    label.setText(provider(source.title));
                                });
                            }
                        },
                        files -> {
                            // Play straight away when the right file is obvious (the film, or
                            // the episode inside a season pack), as streaming apps do.
                            List<String> paths = new ArrayList<>();
                            List<Long> sizes = new ArrayList<>();
                            for (int i = 0; i < files.length(); i++) {
                                JSONObject file = files.getJSONObject(i);
                                paths.add(file.optString("path", file.optString("name", "")));
                                sizes.add(file.optLong("size", 0));
                            }
                            Binge.remember(imdb, season, episode, title, kind, metadata, files);
                            int pick = VideoFiles.autoPick(paths, sizes, season, episode);
                            String pickLink = pick < 0 ? "" : files.getJSONObject(pick).optString("link");
                            if (!pickLink.isEmpty()) {
                                activity.play(
                                    title,
                                    pickLink,
                                    activity.id(
                                        source.uri + ":" + files.getJSONObject(pick).optString("path", "File")
                                    ),
                                    false,
                                    metadata
                                );
                                return;
                            }
                            activity.screen("", () -> {
                                activity.playbackTitle = metadata;
                                torrentSources(imdb, season, episode, title, kind);
                            });
                            activity.posters.listBackdrop(metadata);
                            activity.pageTitle(
                                activity.page,
                                "Choose a file",
                                title,
                                files.length() +
                                    (files.length() == 1 ? " file" : " files") +
                                    " in this source"
                            );
                            if (files.length() == 0) activity.emptyState(
                                activity.page,
                                "No playable files",
                                "Try a different source.",
                                "Back to sources",
                                () -> {
                                    activity.playbackTitle = metadata;
                                    torrentSources(imdb, season, episode, title, kind);
                                }
                            );
                            // Focus starts on the suggested episode: an exact match before a likely one.
                            View first = null;
                            int firstRank = -1;
                            for (int i = 0; i < files.length(); i++) {
                                JSONObject file = files.getJSONObject(i);
                                String link = file.getString("link"),
                                    name = file.optString("path", "File");
                                boolean suggest = VideoFiles.suggested(name, season, episode);
                                int rank = !suggest
                                    ? 0
                                    : VideoFiles.score(name, "", season, episode) == VideoFiles.EXACT
                                      ? 2
                                      : 1;
                                LinearLayout row = activity.listRow();
                                LinearLayout fileCopy = activity.vertical();
                                String shortName = name.substring(name.lastIndexOf('/') + 1);
                                fileCopy.addView(activity.rowText(shortName, 17, false, 2));
                                if (!shortName.equals(name)) fileCopy.addView(
                                    activity.rowText(name, 12, true, 1)
                                );
                                row.addView(fileCopy, new LinearLayout.LayoutParams(0, -2, 1));
                                if (suggest) {
                                    LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(
                                        -2,
                                        -2
                                    );
                                    badgeLayout.leftMargin = activity.dp(12);
                                    row.addView(activity.badge("Suggested episode"), badgeLayout);
                                }
                                activity.styleListRow(row, false);
                                row.setOnClickListener(clicked ->
                                    activity.play(
                                        title,
                                        link,
                                        activity.id(source.uri + ":" + name),
                                        false,
                                        metadata
                                    )
                                );
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                                lp.topMargin = activity.dp(8);
                                activity.page.addView(row, lp);
                                if (rank > firstRank) {
                                    first = row;
                                    firstRank = rank;
                                }
                            }
                            if (first != null) first.requestFocus();
                        }
                    );
                });
                card.setOnLongClickListener(v -> {
                    SidePanel.of(activity, "Source details")
                        .message(source.title)
                        .action("Close", null)
                        .show();
                    return true;
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = activity.dp(8);
                target.addView(card, lp);
            }
        }
        if (shown == 0) activity.emptyState(
            target,
            "No " + quality + " sources",
            "Choose another quality to see available sources.",
            null,
            null
        );
    }
}
