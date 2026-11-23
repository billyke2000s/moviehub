package local.tv.home;

/**
 * The messages a new box shows when MovieHub first opens, one after another:
 *
 * <ol>
 *   <li>"Connect to the internet", only if the box is offline ({@link ConnectionPrompt});
 *   <li>the free TV providers question ({@link ProviderSettings#askOnce});
 *   <li>the safety notice ({@link SafetyNotice});
 *   <li>setting up TMDB for films and series, only if it is not set up yet ({@link #askTmdbOnce}).
 * </ol>
 *
 * Every other account (Xtream, M3U, Premiumize and so on) is optional and stays in Settings only.
 */
final class FirstStart {

    /** Set once the TMDB question has been answered, so it is asked only on a new box. */
    static final String TMDB_ASKED = "tmdbAsked";

    static final String TMDB_TITLE = "Set up films and series";
    static final String TMDB_TEXT =
        "Films and series need a free TMDB key. TMDB (The Movie Database) supplies their posters, " +
        "titles and details.\n\n" +
        "Whoever looks after this box sets it up once: make a free account at themoviedb.org, " +
        "then copy the API Key (v3 auth) from themoviedb.org/settings/api.\n\n" +
        "You can type it with the remote or send it from your phone. Live TV works without it.\n\n" +
        "You can also do this later in Settings → Accounts → TMDB.";

    private FirstStart() {}

    /** Starts the connection watch; the messages follow once MovieHub is on screen and online. */
    static ConnectionPrompt begin(MainActivity activity) {
        return ConnectionPrompt.start(activity, () ->
            ProviderSettings.askOnce(activity, () ->
                SafetyNotice.showOnce(activity, () -> askTmdbOnce(activity))
            )
        );
    }

    /**
     * Asks once, on a new box, whether to set up TMDB. Skipped when TMDB is already connected. Back
     * leaves the question unanswered, so it is asked again the next time MovieHub opens.
     */
    static void askTmdbOnce(MainActivity activity) {
        if (activity.prefs.getBoolean(TMDB_ASKED, false) || activity.secrets.has("tmdb")) return;
        SidePanel.of(activity, TMDB_TITLE)
            .message(TMDB_TEXT)
            .action("Set up TMDB", () -> {
                answered(activity);
                activity.settingsScreens.accountScreen("TMDB");
            })
            .action("Later", () -> answered(activity))
            .show();
    }

    private static void answered(MainActivity activity) {
        activity.prefs.edit().putBoolean(TMDB_ASKED, true).apply();
    }
}
