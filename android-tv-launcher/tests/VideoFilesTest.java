package local.tv.home;

public class VideoFilesTest {

    public static void main(String[] args) {
        if (VideoFiles.score("Show.S01E02.mkv", "", 1, 2) != 2) throw new AssertionError();
        if (VideoFiles.score("Show.S01E20.mkv", "", 1, 2) != -1) throw new AssertionError();
        if (VideoFiles.score("sample.mkv", "", -1, -1) != -1) throw new AssertionError();
        if (VideoFiles.score("film.mp4", "film.mp4", -1, -1) != 3) throw new AssertionError();
        if (VideoFiles.score("Show.1x02.mkv", "", 1, 2) != 2) throw new AssertionError();
        if (VideoFiles.score("subtitle.srt", "", 1, 2) != -1) throw new AssertionError();
        java.util.List<String> paths = java.util.Arrays.asList(
            "Film/Sample/sample.mkv",
            "Film/film.mkv",
            "Film/extra.mkv"
        );
        java.util.List<Long> sizes = java.util.Arrays.asList(50L, 9000L, 800L);
        if (VideoFiles.autoPick(paths, sizes, -1, -1) != 1) throw new AssertionError("film beside extras");
        java.util.List<String> parts = java.util.Arrays.asList("Film/cut.one.mkv", "Film/cut.two.mkv");
        if (
            VideoFiles.autoPick(parts, java.util.Arrays.asList(900L, 800L), -1, -1) != -1
        ) throw new AssertionError("two similar files: ask");
        java.util.List<String> season = java.util.Arrays.asList("S01E01.mkv", "S01E02.mkv", "S01E03.mkv");
        java.util.List<Long> same = java.util.Arrays.asList(1L, 1L, 1L);
        if (VideoFiles.autoPick(season, same, 1, 2) != 1) throw new AssertionError("episode in a pack");
        if (VideoFiles.autoPick(season, same, 1, 5) != -1) throw new AssertionError("missing episode: ask");
        System.out.println("10 episode/file checks passed");
    }
}
