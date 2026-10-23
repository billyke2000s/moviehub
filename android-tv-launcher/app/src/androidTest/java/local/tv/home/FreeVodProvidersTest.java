package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FreeVodProvidersTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void packagedPythonImportsEveryProviderAndHostResolverWithoutKodi() throws Exception {
        assertEquals(68, FreeVodProviders.ids(context).size());
        assertEquals(68, FreeVodProviders.validate(context));
    }

    @Test
    public void javaCancellationReachesPackagedPythonBeforeAnyProviderRequest() throws Exception {
        AtomicInteger emitted = new AtomicInteger();
        FreeVodProviders.search(
            context,
            "free99:my4hydra_top",
            new JSONObject().put("http", false),
            new AtomicBoolean(true),
            source -> emitted.incrementAndGet()
        );
        assertEquals(0, emitted.get());
    }
}
