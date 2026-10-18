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

    SourcesScreen(MainActivity activity) {
        this.activity = activity;
    }

    private String sourceLabel(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String quality =
            lower.contains("2160p") || lower.contains("4k")
                ? "4K"
                : lower.contains("1080p")
                  ? "1080p"
                  : lower.contains("720p")
                    ? "720p"
                    : "Other";
        String hdr =
            lower.contains("dolby vision") ||
            java.util.regex.Pattern.compile("\\b(?:dv|dovi)\\b").matcher(lower).find()
                ? " · Dolby Vision"
                : lower.contains("hdr")
                  ? " · HDR"
                  : "";
        String codec =
            lower.contains("h265") || lower.contains("hevc") || lower.contains("x265")
                ? " · HEVC"
                : lower.contains("h264") || lower.contains("x264")
                  ? " · H.264"
                  : "";
        int split = raw.indexOf(10);
        String provider = split >= 0 ? raw.substring(0, split) : "Source";
        return quality + hdr + codec + "  ·  " + provider;
    }

    void torrentSources(String imdb, int season, int episode, String title, String kind) {
        final JSONObject metadata = activity.playbackTitle;
        activity.screen("Choose a source", () -> {
            if (metadata != null) activity.posters.tmdbTitle(metadata);
            else activity.catalogues.catalogueMenu(kind);
        });
        activity.page.addView(activity.text(title, 24));
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
        TextView loading = activity.text("Checking sources and Premiumize cache…", 16);
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
                    source.cache == status &&
                    (quality.equals("All") || sourceLabel(source.title).startsWith(quality))
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
                LinearLayout card = activity.vertical();
                card.setPadding(activity.dp(18), activity.dp(12), activity.dp(18), activity.dp(12));
                TextView label = activity.compactText(sourceLabel(source.title), 17);
                label.setMaxLines(1);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(label);
                TextView filename = activity.muted(source.title.replace('\n', ' '), 13);
                filename.setMaxLines(2);
                filename.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(filename);
                activity.focus(card);
                activity.styleFocus(card, false);
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
                                    label.setText(sourceLabel(source.title));
                                });
                            }
                        },
                        files -> {
                            activity.screen("Choose a file", () -> {
                                activity.playbackTitle = metadata;
                                torrentSources(imdb, season, episode, title, kind);
                            });
                            activity.page.addView(activity.muted(title, 18));
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
                            for (int i = 0; i < files.length(); i++) {
                                JSONObject file = files.getJSONObject(i);
                                String link = file.getString("link"),
                                    name = file.optString("path", "File");
                                int match = VideoFiles.score(name, "", season, episode);
                                Button fileButton = activity.button(
                                    (match >= 2 ? "Suggested episode · " : "") + name,
                                    () ->
                                        activity.play(
                                            title,
                                            link,
                                            activity.id(source.uri + ":" + name),
                                            false,
                                            metadata
                                        )
                                );
                                fileButton.setSingleLine(false);
                                fileButton.setMaxLines(2);
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    -1,
                                    activity.dp(68)
                                );
                                lp.topMargin = activity.dp(8);
                                activity.page.addView(fileButton, lp);
                            }
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
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, activity.dp(88));
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
