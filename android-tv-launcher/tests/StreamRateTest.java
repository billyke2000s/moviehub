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
        check(SourceRanking.resolution("4KHDHub 1080p") == 1080);
        check(SourceRanking.resolution("1080p UHD BluRay") == 1080);
        check(SourceRanking.resolution("720p from 4khdhub.one") == 720);
        check(SourceRanking.rank("1080p HDCAM", 1080) < SourceRanking.rank("SD WEB-DL", 1080));
        check(SourceRanking.readyRank("1080p", 1080, 1, 500) > SourceRanking.readyRank("720p", 1080, 99, 1));
        check(
            SourceRanking.readyRank("1080p", 1080, 20, 500) > SourceRanking.readyRank("1080p", 1080, 2, 500)
        );
        check(SourceRanking.readyRank("1080p", 1080, 0, 50) > SourceRanking.readyRank("1080p", 1080, 0, 500));
        check(SourceRanking.readyRank("1080p", 1080, 0, 500) > SourceRanking.readyRank("4K", 1080, 99, 1));
        // A quality read from the file beats the same quality claimed by a website, at any speed,
        // but never beats a better quality.
        check(
            SourceRanking.readyRank("1080p", 1080, 0, 999, true) >
                SourceRanking.readyRank("1080p", 1080, 99, 1, false)
        );
        check(
            SourceRanking.readyRank("1080p", 1080, 0, 999, false) >
                SourceRanking.readyRank("720p", 1080, 99, 1, true)
        );
        check(
            SourceRanking.readyRank("720p", 1080, 5, 300) ==
                SourceRanking.readyRank("720p", 1080, 5, 300, false)
        );
        // Confirmed small pictures rank below plain SD; ordinary SD and unknown labels stay 480.
        check(SourceRanking.resolution("360p") == 360);
        check(SourceRanking.resolution("240p · Film.mp4") == 240);
        check(SourceRanking.resolution("480p") == 480);
        check(SourceRanking.resolution("SD") == 480);
        check(SourceRanking.resolution("Film.2019.x264") == 480);
        check(SourceRanking.resolution("1080p 360p") == 1080);
        // Audio claimed by the release name.
        check(SourceRanking.audio("Show.S01E02.1080p.WEB-DL.DDP5.1.Atmos.H.264") == 3);
        check(SourceRanking.audio("Film 2019 2160p UHD BluRay TrueHD 7.1 x265") == 2);
        check(SourceRanking.audio("Film.2019.1080p.BluRay.DTS-HD.MA.7.1.x264") == 2);
        check(SourceRanking.audio("Show S01E02 1080p WEB DDP5 1 H 264") == 1);
        check(SourceRanking.audio("Film.2019.1080p.WEBRip.6CH.x265") == 1);
        check(SourceRanking.audio("Film (2019) 1080p 5.1") == 1);
        check(SourceRanking.audio("Film.2019.720p.AAC2.0.x264") == 0);
        check(SourceRanking.audio("Film 2019 1080p · 5.1 GB") == 0);
        check(SourceRanking.audio("Film 2019 1080p 5.1GB") == 0);
        check(SourceRanking.audio("Show Season 5 1080p") == 0);
        check(SourceRanking.audio("Film 2019 1080p") == 0);
        check(SourceRanking.audioLabel("x DDP5.1 Atmos").equals("Atmos"));
        check(SourceRanking.audioLabel("x DTS 7.1").equals("7.1"));
        check(SourceRanking.audioLabel("x 1080p").isEmpty());
        // Among equal picture quality more channels come first; never above a better picture,
        // and a confirmed picture still outranks a claimed one.
        check(
            SourceRanking.readyRank("1080p", 1080, 0, 999, false, 1) >
                SourceRanking.readyRank("1080p", 1080, 99, 1, false, 0)
        );
        check(
            SourceRanking.readyRank("1080p", 1080, 0, 999, false, 3) >
                SourceRanking.readyRank("1080p", 1080, 0, 999, false, 2)
        );
        check(
            SourceRanking.readyRank("720p", 1080, 99, 1, true, 3) <
                SourceRanking.readyRank("1080p", 1080, 0, 999, false, 0)
        );
        check(
            SourceRanking.readyRank("1080p", 1080, 0, 999, true, 0) >
                SourceRanking.readyRank("1080p", 1080, 99, 1, false, 3)
        );
        System.out.println("46 incoming-rate, ready-source and audio ranking checks passed");
    }

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
}
