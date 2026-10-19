package local.tv.home;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Optional third-party video add-on registry shared by Movies and Series.
 *
 * Register an add-on once even when it supports both content types.
 * Entries are metadata only: Kodi Python add-ons cannot be executed by
 * registering their URLs here. Each entry needs a reviewed native adapter
 * before the app can discover or play streams from it.
 *
 * Do not add credentials, private playlist URLs or unreviewed executables.
 * Free services must remain disabled until the user opts in.
 */
final class FreeVideoProviders {

    private FreeVideoProviders() {}

    static final class Provider {
        final String id;
        final String name;
        final String repositoryUrl;
        final boolean movies;
        final boolean series;

        Provider(String id, String name, String repositoryUrl, boolean movies, boolean series) {
            this.id = id;
            this.name = name;
            this.repositoryUrl = repositoryUrl;
            this.movies = movies;
            this.series = series;
        }
    }

    /**
     * ADD NEW MOVIE AND SERIES ADD-ONS HERE.
     * One entry per add-on:
     * new Provider("unique-id", "Display name", "https://github.com/owner/repo", true, true)
     *
     * The final booleans indicate Movies and Series support respectively.
     * An entry is NOT enabled or executable until its adapter is implemented
     * and the user has accepted the free-services consent flow.
     */
    static final List<Provider> EXTRA = Collections.unmodifiableList(new ArrayList<Provider>());
}
