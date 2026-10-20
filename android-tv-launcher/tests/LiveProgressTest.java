package local.tv.home;

public final class LiveProgressTest {

    private static int checks;

    private static void check(boolean value) {
        if (!value) throw new AssertionError("Live progress " + checks);
        checks++;
    }

    public static void main(String[] args) {
        LiveProgress p = new LiveProgress();
        check(!p.stalled(0, 100, 5, true, 8000));
        check(!p.stalled(7999, 100, 5, true, 8000));
        check(p.stalled(8000, 100, 5, true, 8000));
        p.reset();
        check(!p.stalled(0, 100, 5, true, 8000));
        check(!p.stalled(7000, 7100, 5, true, 8000));
        check(p.stalled(8000, 8100, 5, true, 8000));
        p.reset();
        for (int now = 0; now <= 240000; now += 1000) {
            if (p.stalled(now, now, now / 40, true, 8000)) throw new AssertionError("Moving stream stalled");
        }
        check(true);
        check(!p.stalled(300000, 240000, 6000, false, 8000));
        check(!p.stalled(400000, 240000, 6000, false, 8000));
        check(!p.stalled(500000, 240000, 6000, true, 8000));
        p.reset();
        check(!p.stalled(0, 0, -1, true, 8000));
        check(!p.stalled(9000, 9000, -1, true, 8000));
        check(p.stalled(17000, 9000, -1, true, 8000));
        p.reset();
        check(!p.stalled(0, 10000, 300, true, 8000));
        check(!p.stalled(7000, 100, 1, true, 8000));
        check(!p.stalled(14000, 7100, 200, true, 8000));
        p.reset();
        p.stalled(0, 0, 10, true, 8000);
        check(!p.stalled(9000, 0, 300, true, 8000));
        System.out.println(checks + " live progress checks passed");
    }
}
