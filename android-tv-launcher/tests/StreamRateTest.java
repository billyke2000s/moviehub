package local.tv.home;

public final class StreamRateTest {

    public static void main(String[] args) {
        StreamRate rate = new StreamRate();
        check(rate.sample(1000, 0) == 0);
        check(rate.sample(2000, 1000000) == 8);
        check(rate.sample(2100, 2000000) == 8);
        check(rate.sample(3000, 2000000) == 8);
        check(rate.sample(4000, 2000000) == 0);
        check(rate.sample(5000, 0) == 0);
        check(rate.sample(6000, 500000) == 4);
        check(SourceRanking.resolution("FHD") == 1080);
        check(SourceRanking.resolution("HD") == 720);
        check(SourceRanking.resolution("UHD") == 2160);
        check(SourceRanking.resolution("4Kings") == 480);
        check(SourceRanking.rank("1080p HDCAM", 1080) < SourceRanking.rank("SD WEB-DL", 1080));
        check(SourceRanking.readyRank("1080p", 1080, 1, 500) > SourceRanking.readyRank("720p", 1080, 99, 1));
        check(
            SourceRanking.readyRank("1080p", 1080, 20, 500) > SourceRanking.readyRank("1080p", 1080, 2, 500)
        );
        check(SourceRanking.readyRank("1080p", 1080, 0, 50) > SourceRanking.readyRank("1080p", 1080, 0, 500));
        check(SourceRanking.readyRank("1080p", 1080, 0, 500) > SourceRanking.readyRank("4K", 1080, 99, 1));
        System.out.println("16 incoming-rate and ready-source ranking checks passed");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}
