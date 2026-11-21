package local.tv.home;

public final class LiveSourceDeadlineTest {

    public static void main(String[] args) {
        boolean[][] cases = {
            { false, false, false },
            { false, false, true },
            { true, false, false },
            { true, false, true },
            { false, true, false },
            { false, true, true },
            { true, true, false },
            { true, true, true },
        };
        long[] expected = { 0, 0, 15000, 8000, 60000, 60000, 15000, 8000 };
        for (int i = 0; i < cases.length; i++) {
            boolean[] c = cases[i];
            if (LiveSourceDeadline.milliseconds(c[0], c[1], c[2]) != expected[i]) throw new AssertionError(
                "Startup budget case " + i
            );
        }
        daddyLiveFirstFrameDeadline();
        System.out.println((cases.length + 7) + " live source deadline checks passed");
    }

    private static void daddyLiveFirstFrameDeadline() {
        long opened = 1_000;
        long limit = LiveSourceDeadline.DADDYLIVE_FIRST_FRAME_MS;
        boolean[] actual = {
            LiveSourceDeadline.daddyFirstFrameMissing(0, opened + limit, true),
            LiveSourceDeadline.daddyFirstFrameMissing(opened, opened + limit, false),
            LiveSourceDeadline.daddyFirstFrameMissing(opened, opened - 1, true),
            LiveSourceDeadline.daddyFirstFrameMissing(opened, opened + limit - 1, true),
            LiveSourceDeadline.daddyFirstFrameMissing(opened, opened + limit, true),
            LiveSourceDeadline.daddyFirstFrameMissing(opened, opened + limit + 1, true),
            LiveSourceDeadline.daddyFirstFrameMissing(0, opened + 2 * limit, true),
        };
        boolean[] expected = { false, false, false, false, true, true, false };
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != expected[i]) throw new AssertionError("DaddyLive first-frame case " + i);
        }
    }
}
