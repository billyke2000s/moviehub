package local.tv.home;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in throughput measurement, with synthetic media rather than a provider-success fixture. */
@RunWith(AndroidJUnit4.class)
public final class VodRunnerCalibrationTest {

    @Test
    public void measureSynthetic720p24FpsThroughTheExistingPlayer() throws Exception {
        Assume.assumeTrue(
            "Explicit calibration only",
            "true".equals(InstrumentationRegistry.getArguments().getString("vodRunnerCalibration"))
        );
        VodSessionPlaybackTest fixture = new VodSessionPlaybackTest();
        try {
            fixture.setup();
            fixture.measureKnownFixture();
        } finally {
            fixture.cleanup();
        }
    }
}
