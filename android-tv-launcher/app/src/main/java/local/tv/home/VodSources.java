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
        boolean pinned;
        int active;

        Session(List<VodSourceFailover.Source> sources, Resolve resolve, boolean pinned) {
            this.sources = Collections.unmodifiableList(
                new ArrayList<>(sources.subList(0, Math.min(LIMIT, sources.size())))
            );
            this.resolve = resolve;
            this.pinned = pinned;
        }
    }

    private VodSources() {}

    static synchronized String remember(Session session) {
        if (session == null) return null;
        String token = UUID.randomUUID().toString();
        while (sessions.size() >= 2) sessions.remove(sessions.keySet().iterator().next());
        sessions.put(token, session);
        return token;
    }

    static synchronized Session get(String token) {
        return sessions.get(token);
    }

    static synchronized void forget(String token) {
        sessions.remove(token);
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
        return new Session(
            entries,
            index -> {
                if (index == 0) return new Resolved(link, currentFiles);
                JSONArray files = repo.direct(ordered.get(index));
                int pick = AutoPlayback.pick(files, season, episode, title);
                if (pick < 0) throw new IllegalArgumentException("No exact playable file in backup");
                return new Resolved(files.getJSONObject(pick).getString("link"), files);
            },
            pinned
        );
    }
}
