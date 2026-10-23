package local.tv.home;

final class SourceWindowTest {

    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    public static void main(String[] args) {
        SourceWindow<Integer> window = new SourceWindow<>(1000, value -> value);
        check(window.pick(3999) == null);
        check(window.add("720", 720));
        check(window.add("1080", 1080));
        check(!window.add("1080", 2160));
        check(window.pick(3999) == null);
        check(window.pick(4000) == 1080);
        check(window.add("late", 2160));
        check(window.pick(4050) == 2160);
        SourceWindow<Integer> empty = new SourceWindow<>(0, value -> value);
        check(empty.pick(3000) == null);
        check(empty.add("first-late", 720));
        check(empty.pick(5100) == 720);
        empty.cancel();
        check(empty.pick(5200) == null);
        check(!empty.add("cancelled", 1080));
        VodSourceFailover queue = new VodSourceFailover();
        queue.setSources(java.util.Arrays.asList(new VodSourceFailover.Source("a", "a", "one")));
        queue.onPlaying();
        check(!queue.onBufferingStarted(100));
        queue.appendSources(java.util.Arrays.asList(new VodSourceFailover.Source("b", "b", "two")));
        check(queue.isStalled(4100));
        check(queue.failover().url.equals("b"));
        queue.appendSources(
            java.util.Arrays.asList(
                new VodSourceFailover.Source("a", "a", "one"),
                new VodSourceFailover.Source("c", "c", "three")
            )
        );
        check(queue.failover().url.equals("c"));
        check(queue.failover() == null);
        System.out.println("20 source selection and late-backup checks passed");
    }
}
