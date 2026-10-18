package local.tv.home;

import java.util.*;
import java.util.regex.Pattern;

/** Port of moviehub debrid._pick rules. Suggestions never auto-play a different episode. */
final class VideoFiles {

    static final Pattern VIDEO = Pattern.compile("(?i).*\\.(mkv|mp4|avi|m4v|mov|ts|webm|wmv|mpg|mpeg)$");
    static final Pattern JUNK = Pattern.compile("(?i)\\bsample\\b|\\btrailer\\b|\\bextras?\\b|featurette");

    static int score(String path, String hint, int season, int episode) {
        if (!VIDEO.matcher(path).matches() || JUNK.matcher(path).find()) return -1;
        String file = path.replace('\\', '/');
        file = file.substring(file.lastIndexOf('/') + 1);
        if (
            hint != null &&
            !hint.isEmpty() &&
            file.equalsIgnoreCase(hint.substring(hint.lastIndexOf('/') + 1))
        ) return 3;
        if (season >= 0 && episode > 0) {
            Pattern match = Pattern.compile(
                "(?i)s0*" +
                    season +
                    "[ ._-]*e0*" +
                    episode +
                    "(?!\\d)|\\b0*" +
                    season +
                    "x0*" +
                    episode +
                    "(?!\\d)"
            );
            return match.matcher(file).find() ? 2 : -1;
        }
        return 1;
    }

    /**
     * The file to play straight away, or -1 to let the user choose. For an episode: the only
     * file matching it. For a movie: the only video file, or one at least three times larger than
     * any other (the film beside samples or extras).
     */
    static int autoPick(List<String> paths, List<Long> sizes, int season, int episode) {
        boolean wantEpisode = season >= 0 && episode > 0;
        int best = -1,
            matches = 0;
        long bestSize = -1,
            secondSize = -1;
        for (int i = 0; i < paths.size(); i++) {
            int score = score(paths.get(i), "", season, episode);
            if (score < (wantEpisode ? 2 : 1)) continue;
            matches++;
            long size = i < sizes.size() && sizes.get(i) != null ? sizes.get(i) : 0;
            if (best < 0 || size > bestSize) {
                secondSize = bestSize;
                best = i;
                bestSize = size;
            } else if (size > secondSize) secondSize = size;
        }
        if (matches == 1) return best;
        if (wantEpisode || matches == 0) return -1;
        return bestSize > 0 && bestSize >= secondSize * 3 ? best : -1;
    }
}
