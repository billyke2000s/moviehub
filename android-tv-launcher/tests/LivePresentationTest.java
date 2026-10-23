package local.tv.home;

public final class LivePresentationTest {

    public static void main(String[] args) {
        LivePresentation state = new LivePresentation();
        state.result(0, false);
        if (state.missing(7999, 8000)) throw new AssertionError("Too early");
        if (!state.missing(8000, 8000)) throw new AssertionError("No buffer timeout");
        state.result(8001, true);
        if (state.missing(16001, 8000)) throw new AssertionError("Available picture");
        for (int i = 0; i < 100; i++) state.result(i * 1000L, true);
        if (state.missing(100000, 8000)) throw new AssertionError("Legitimate black/static buffers");
        state.result(100000, false);
        state.reset();
        if (state.missing(200000, 8000)) throw new AssertionError("Hidden/paused reset");
        System.out.println("5 live presentation checks passed");
    }
}
