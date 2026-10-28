package local.tv.home;

import java.util.*;
import org.json.*;

/** Bounded, in-memory VOD alternatives. Only the selected file is resolved before playback. */
final class VodSources {

    static final int LIMIT = 5;
    private static final Map<String, Session> sessions = new LinkedHashMap<>();

    interface Resolve {
        Resolved get(int index) throws Exception;
    }

    static final class Resolved {

        final String url;
        final JSONArray files;

        Resolved(String url, JSONArray files) {
            this.url = url;
            this.files = files;
        }
    }

    static final class Session {

        final List<VodSourceFailover.Source> sources;
        final Resolve resolve;
        Resolve refresh;
        java.util.function.IntPredicate refreshable = index -> false;
        boolean managed;
        long expectedDuration;
        Runnable close = () -> {};
        java.util.function.IntFunction<EngineSources.Stream> transport = index -> null;
        boolean pinned;
        boolean free;
        java.util.function.BooleanSupplier searching = () -> false;
        java.util.function.Supplier<String> report = () -> "";
        int active;

        Session(List<VodSourceFailover.Source> sources, Resolve resolve, boolean pinned) {
            this.sources = new java.util.concurrent.CopyOnWriteArrayList<>(
                sources.subList(0, Math.min(LIMIT, sources.size()))
            );
            this.resolve = resolve;
            this.pinned = pinned;
        }
    }

    private VodSources() {}

    static synchronized String remember(Session session) {
        if (session == null) return null;
        String token = UUID.randomUUID().toString();
        while (sessions.size() >= 2) {
            Session removed = sessions.remove(sessions.keySet().iterator().next());
            removed.close.run();
        }
        sessions.put(token, session);
        return token;
    }

    static synchronized Session get(String token) {
        return sessions.get(token);
    }

    static synchronized void forget(String token) {
        Session removed = sessions.remove(token);
        if (removed != null) removed.close.run();
    }

    static Session progressive(
        SourceRepository.Source selected,
        String link,
        VodSearch search,
        SourceRepository repo,
        int season,
        int episode,
        String title,
        boolean pinned
    ) {
        List<SourceRepository.Source> ordered = new java.util.concurrent.CopyOnWriteArrayList<>();
        ordered.add(selected);
        Session session = new Session(
            Collections.singletonList(entry(selected)),
            index -> {
                SourceRepository.Source source = ordered.get(index).fresh(false);
                ordered.set(index, source);
                JSONArray files = repo.direct(source);
                int pick = AutoPlayback.pick(files, season, episode, title);
                if (pick < 0) throw new IllegalArgumentException("No exact playable backup");
                return new Resolved(files.getJSONObject(pick).getString("link"), files);
            },
            pinned
        );
        session.sources.get(0).rank = SourceRanking.readyRank(
            selected.ranking == null ? selected.title : selected.ranking,
            search.preferred,
            selected.probeMbps,
            selected.responseMs,
            selected.confirmed
        );
        session.managed = true;
        session.refreshable = index -> ordered.get(index).refresh != null || ordered.get(index).files == null;
        session.refresh = index -> {
            SourceRepository.Source source = ordered.get(index).fresh(true);
            ordered.set(index, source);
            JSONArray files = repo.direct(source);
            int pick = AutoPlayback.pick(files, season, episode, title);
            if (pick < 0) throw new IllegalArgumentException("No matching refreshed file");
            return new Resolved(files.getJSONObject(pick).getString("link"), files);
        };
        session.free = true;
        // Choosing a free service chooses the first attempt; a failed host can use every backup.
        session.pinned = false;
        session.searching = () -> !search.finished();
        session.report = search::report;
        session.transport = index -> ordered.get(index).stream;
        session.close = search::close;
        search.observe(source -> {
            for (var existing : ordered) if (existing.key().equals(source.key())) return;
            if (ordered.size() >= 200) return;
            ordered.add(source);
            VodSourceFailover.Source choice = entry(source);
            choice.rank = SourceRanking.readyRank(
                source.ranking == null ? source.title : source.ranking,
                search.preferred,
                source.probeMbps,
                source.responseMs,
                source.confirmed
            );
            session.sources.add(choice);
        });
        return session;
    }

    static VodSourceFailover.Source entry(SourceRepository.Source source) {
        VodSourceFailover.Source entry = new VodSourceFailover.Source(
            source.key(),
            SourcesScreen.provider(source.title) + " · " + SourcesScreen.badge(source),
            SourcesScreen.provider(source.title)
        );
        // A camera copy's picture size is real but it is never a better source to suggest.
        if (source.confirmed && source.ranking != null && !SourceRanking.camera(source.ranking)) {
            entry.confirmed = SourceRanking.resolution(source.ranking);
        }
        // Cloud results have no separate claim; their release name is the claim.
        entry.claimed = source.claimed.isEmpty() ? SourcesScreen.quality(source.title) : source.claimed;
        entry.site = source.site;
        entry.playlist = source.playlist;
        try {
            String host = java.net.URI.create(source.uri).getHost();
            entry.failureDomain = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            /* Cloud locators have no playback host yet. */
        }
        return entry;
    }

    static Session prepare(
        SourceRepository.Source selected,
        String link,
        JSONArray currentFiles,
        List<SourceRepository.Source> available,
        SourceRepository repo,
        int season,
        int episode,
        String title,
        int quality,
        boolean pinned
    ) {
        List<SourceRepository.Source> ordered = new ArrayList<>();
        ordered.add(selected);
        List<SourceRepository.Source> rest = new ArrayList<>();
        for (var source : available)
            if (source.cache == SourceRepository.Cache.CACHED && !source.uri.equals(selected.uri)) rest.add(
                source
            );
        rest.sort(
            Comparator.comparingInt((SourceRepository.Source source) ->
                SourceRanking.rank(source.title, quality)
            ).reversed()
        );
        Set<String> seen = new HashSet<>();
        seen.add(selected.uri);
        for (var source : rest) if (ordered.size() < LIMIT && seen.add(source.uri)) ordered.add(source);
        List<VodSourceFailover.Source> entries = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) entries.add(
            new VodSourceFailover.Source(
                ordered.get(i).uri,
                "Source " + (i + 1) + " · " + SourcesScreen.quality(ordered.get(i).title),
                "Premiumize"
            )
        );
        Session session = new Session(
            entries,
            index -> {
                if (
                    index == 0 && System.currentTimeMillis() - selected.resolvedAt < 120_000
                ) return new Resolved(link, currentFiles);
                JSONArray files = repo.direct(ordered.get(index));
                int pick = AutoPlayback.pick(files, season, episode, title);
                if (pick < 0) throw new IllegalArgumentException("No exact playable file in backup");
                return new Resolved(files.getJSONObject(pick).getString("link"), files);
            },
            pinned
        );
        session.managed = true;
        session.refreshable = index -> ordered.get(index).files == null;
        session.refresh = index -> {
            JSONArray files = repo.direct(ordered.get(index));
            int pick = AutoPlayback.pick(files, season, episode, title);
            if (pick < 0) throw new IllegalArgumentException("No matching refreshed cloud file");
            return new Resolved(files.getJSONObject(pick).getString("link"), files);
        };
        return session;
    }
}
