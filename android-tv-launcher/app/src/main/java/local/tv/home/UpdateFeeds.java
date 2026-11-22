package local.tv.home;

import java.net.URI;
import java.util.*;

/**
 * Where the app looks for updates. A self-hosted address, set at build time with the
 * MOVIEHUB_FEED_URL environment variable, is tried first; the original GitHub release feeds stay as a
 * fallback so boxes keep updating while the self-hosted feed is set up. Authentication of what is
 * downloaded never depends on the address: APKs are checked against the installed signing certificate
 * and packages against the pinned public key in providers/trust.json.
 *
 * Pure Java (no Android types) so the core checks can run it on a desktop JVM.
 */
final class UpdateFeeds {

    static final String GITHUB_APP =
        "https://github.com/billyke2000s/moviehub/releases/download/android-tv-latest/";
    static final String GITHUB_APP_EXPERIMENTAL =
        "https://github.com/billyke2000s/moviehub/releases/download/android-tv-experimental/";
    static final String GITHUB_PROVIDERS =
        "https://github.com/billyke2000s/moviehub/releases/download/provider-engine-latest/";

    /** Hosts GitHub redirects release downloads to. Only allowed when the feed itself is on GitHub. */
    static final Set<String> GITHUB_HOSTS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "github.com",
                "release-assets.githubusercontent.com",
                "objects.githubusercontent.com"
            )
        )
    );

    /** One place to download from. The key is sent only to the feed's own host, never to GitHub. */
    static final class Feed {

        final String root, key;

        Feed(String root, String key) {
            this.root = root;
            this.key = key;
        }

        String host() {
            return URI.create(root).getHost();
        }

        boolean github() {
            return GITHUB_HOSTS.contains(host());
        }

        /** Hosts a download from this feed may be redirected to. */
        boolean allows(String host) {
            if (host == null) return false;
            return github() ? GITHUB_HOSTS.contains(host) : host.equalsIgnoreCase(host());
        }
    }

    private UpdateFeeds() {}

    /**
     * The self-hosted base address in canonical form ("https://host/path/"), or "" when unset or
     * unsafe. Plain http, user names, custom ports, queries and fragments are refused.
     */
    static String base(String configured) {
        if (configured == null) return "";
        String value = configured.trim();
        if (value.isEmpty()) return "";
        try {
            URI uri = URI.create(value);
            if (
                !"https".equalsIgnoreCase(uri.getScheme()) ||
                uri.getHost() == null ||
                uri.getUserInfo() != null ||
                uri.getPort() != -1 ||
                uri.getRawQuery() != null ||
                uri.getRawFragment() != null
            ) return "";
        } catch (IllegalArgumentException e) {
            return "";
        }
        return value.endsWith("/") ? value : value + "/";
    }

    /** App (APK) updates: self-hosted first, then the GitHub feed of the same app identity. */
    static List<Feed> app(String configured, String key, boolean experimental) {
        List<Feed> feeds = new ArrayList<>();
        String base = base(configured);
        if (!base.isEmpty()) feeds.add(new Feed(base + (experimental ? "experimental/" : "app/"), key));
        feeds.add(new Feed(experimental ? GITHUB_APP_EXPERIMENTAL : GITHUB_APP, ""));
        return feeds;
    }

    /** Live TV provider packages (signed JavaScript). */
    static List<Feed> providers(String configured, String key) {
        List<Feed> feeds = new ArrayList<>();
        String base = base(configured);
        if (!base.isEmpty()) feeds.add(new Feed(base + "providers/", key));
        feeds.add(new Feed(GITHUB_PROVIDERS, ""));
        return feeds;
    }

    /** Film and series scraper packages (signed Python). Only ever self-hosted. */
    static List<Feed> scrapers(String configured, String key, boolean experimental) {
        String base = base(configured);
        if (base.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(
            new Feed(base + (experimental ? "scrapers-experimental/" : "scrapers/"), key)
        );
    }
}
