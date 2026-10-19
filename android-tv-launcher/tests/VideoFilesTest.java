package local.tv.home;

public class VideoFilesTest {

    private static int checks;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    /** E: exact (may play straight away), L: likely (suggested only), N: not suggested. */
    private static void episode(String path, int season, int episode, char expected) {
        int score = VideoFiles.score(path, "", season, episode);
        char got = score == VideoFiles.EXACT ? 'E' : score == VideoFiles.LIKELY ? 'L' : 'N';
        check(
            got == expected,
            path + " for S" + season + "E" + episode + ": expected " + expected + ", got " + got
        );
        check(
            VideoFiles.suggested(path, season, episode) == (expected != 'N'),
            path + ": suggested() agrees with score()"
        );
    }

    public static void main(String[] args) {
        // The original rules.
        check(VideoFiles.score("Show.S01E02.mkv", "", 1, 2) == 2, "S01E02");
        check(VideoFiles.score("Show.S01E20.mkv", "", 1, 2) == -1, "S01E20 is not episode 2");
        check(VideoFiles.score("sample.mkv", "", -1, -1) == -1, "sample");
        check(VideoFiles.score("film.mp4", "film.mp4", -1, -1) == 3, "hint");
        check(VideoFiles.score("Show.1x02.mkv", "", 1, 2) == 2, "1x02");
        check(VideoFiles.score("subtitle.srt", "", 1, 2) == -1, "not a video");
        java.util.List<String> paths = java.util.Arrays.asList(
            "Film/Sample/sample.mkv",
            "Film/film.mkv",
            "Film/extra.mkv"
        );
        java.util.List<Long> sizes = java.util.Arrays.asList(50L, 9000L, 800L);
        check(VideoFiles.autoPick(paths, sizes, -1, -1) == 1, "film beside extras");
        java.util.List<String> parts = java.util.Arrays.asList("Film/cut.one.mkv", "Film/cut.two.mkv");
        check(
            VideoFiles.autoPick(parts, java.util.Arrays.asList(900L, 800L), -1, -1) == -1,
            "two similar files: ask"
        );
        java.util.List<String> season = java.util.Arrays.asList("S01E01.mkv", "S01E02.mkv", "S01E03.mkv");
        java.util.List<Long> same = java.util.Arrays.asList(1L, 1L, 1L);
        check(VideoFiles.autoPick(season, same, 1, 2) == 1, "episode in a pack");
        check(VideoFiles.autoPick(season, same, 1, 5) == -1, "missing episode: ask");

        // Exact: the name says this season and episode.
        episode("Show.Name.S01E03.1080p.WEB-DL.x264.mkv", 1, 3, 'E');
        episode("show.name.s1e3.720p.mkv", 1, 3, 'E');
        episode("Show Name - S01 E03 - Title.mkv", 1, 3, 'E');
        episode("Show.Name.S01.E03.mkv", 1, 3, 'E');
        episode("Show Name S01Ep03.mkv", 1, 3, 'E');
        episode("Show Name S01 Episode 03.mkv", 1, 3, 'E');
        episode("Show Name 1x03 Title.mkv", 1, 3, 'E');
        episode("Show Name Season 1 Episode 3.mp4", 1, 3, 'E');
        episode("Show Name - Series 2 - Ep 3.mkv", 2, 3, 'E');
        episode("Pack/Season 1/E03 - Title.mkv", 1, 3, 'E');
        episode("Show.S02.1080p/Show - Episode 03.mkv", 2, 3, 'E');
        episode("Show.S01E03.1080p.WEB/video.mkv", 1, 3, 'E');
        episode("Show.S00E01.Special.mkv", 0, 1, 'E');
        episode("Show.S10E100.mkv", 10, 100, 'E');
        // Shows with numbers in their names.
        episode("The.100.S02E01.mkv", 2, 1, 'E');
        episode("9-1-1.S01E02.mkv", 1, 2, 'E');
        episode("24.S03E02.mkv", 3, 2, 'E');
        episode("1923.S01E04.2160p.mkv", 1, 4, 'E');

        // Likely: suggested for you to confirm, never played straight away.
        episode("Show.S01E01E02.mkv", 1, 2, 'L');
        episode("Show.S01E01-E02.mkv", 1, 1, 'L');
        episode("Show.S01E01-03.mkv", 1, 2, 'L');
        episode("Pack/Season 1/03 - Title.mkv", 1, 3, 'L');
        episode("Show Name - 03.mkv", 1, 3, 'L');
        episode("Show Name 103.mkv", 1, 3, 'L');
        episode("Show (2019) - 03 [1080p].mkv", 1, 3, 'L');
        episode("Pack/E03.mkv", 1, 3, 'L');
        episode("Pack/Season 1/E03 - Part 2 of 2.mkv", 1, 3, 'L');
        episode("Extras.S01E02.mkv", 1, 2, 'L');
        episode("Show.S01/Featurettes/Show.S01E02.Behind.the.Scenes.mkv", 1, 2, 'L');

        // Not suggested: another episode or season, or a name that could mean something else.
        episode("Show.S01E30.mkv", 1, 3, 'N');
        episode("Show.S11E03.mkv", 1, 3, 'N');
        episode("Show.S02E03.mkv", 1, 3, 'N');
        episode("Show.S01E03.mkv", 1, 30, 'N');
        episode("Show.S01E01-720p.mkv", 1, 720, 'N');
        episode("Show.S01E01.1080p.mkv", 1, 1080, 'N');
        episode("Show.1920x1080.mkv", 19, 20, 'N');
        episode("Show.Name.x264.mkv", 1, 264, 'N');
        episode("Pack/Season 2/E03.mkv", 1, 3, 'N');
        episode("Pack/Season 2/03 - Title.mkv", 1, 3, 'N');
        episode("Show Name - 04.mkv", 1, 3, 'N');
        episode("05 - The Finale Pt 2.mkv", 1, 2, 'N');
        episode("Show 2 - 03.mkv", 1, 3, 'N');
        episode("Show.Name.2019.1080p.mkv", 1, 19, 'N');
        episode("Show.5.1.Audio.mkv", 1, 5, 'N');
        episode("Show.S01E03.Sample.mkv", 1, 3, 'N');
        episode("Show.S01E03.srt", 1, 3, 'N');
        episode("Pack/Season 1/Trailer.mkv", 1, 3, 'N');
        episode("video.mkv", 1, 3, 'N');

        // A season pack: plays the one exact file; suggests but never plays a likely one.
        java.util.List<String> pack = java.util.Arrays.asList(
            "Show.S01/Show.S01E01.mkv",
            "Show.S01/Show.S01E02.mkv",
            "Show.S01/Show.S01E02.Sample.mkv",
            "Show.S01/Show.S01E03.mkv"
        );
        java.util.List<Long> four = java.util.Arrays.asList(9L, 9L, 1L, 9L);
        check(VideoFiles.autoPick(pack, four, 1, 2) == 1, "the one exact file plays");
        java.util.List<String> bare = java.util.Arrays.asList("Pack/01.mkv", "Pack/02.mkv", "Pack/03.mkv");
        check(VideoFiles.autoPick(bare, same, 1, 2) == -1, "a likely file is only suggested");
        check(VideoFiles.suggested("Pack/02.mkv", 1, 2), "and it is marked");
        check(!VideoFiles.suggested("Show.S01E02.mkv", -1, -1), "films are never marked");
        System.out.println(checks + " episode/file checks passed");
    }
}
