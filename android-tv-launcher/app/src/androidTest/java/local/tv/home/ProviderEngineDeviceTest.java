package local.tv.home;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import org.json.*;
import org.junit.*;

public final class ProviderEngineDeviceTest {

    private Context context;

    @Before
    public void before() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        clean();
    }

    @After
    public void clean() {
        ProviderPackages.testTrust = null;
        if (context == null) return;
        for (String slot : new String[] { "active", "previous" }) {
            new android.util.AtomicFile(
                new File(context.getFilesDir(), "provider-" + slot + ".json")
            ).delete();
        }
        context.getSharedPreferences("providerEngine", 0).edit().clear().commit();
        context.getSharedPreferences("ui", 0).edit().remove("providerAutoUpdates").commit();
    }

    @Test
    public void isolatedBridgeRunsActualCompiledAdapterAndRejectsJava() throws Exception {
        String source = ProviderPackages.module(context);
        JSONArray rows = new JSONArray(
            ProviderBridge.call(
                context,
                source,
                "catalogue",
                "[{\"_id\":\"a\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8\"}]"
            )
        );
        assertEquals("a", rows.getJSONObject(0).getString("channelId"));
        JSONObject resolved = new JSONObject(
            ProviderBridge.call(context, source, "resolve", "https://example.com/a.m3u8?cb=[CB]")
        );
        assertFalse(resolved.getString("url").contains("[CB]"));
        try {
            ProviderBridge.call(
                context,
                "function invoke(){return java.lang.System.getProperties();}",
                "",
                ""
            );
            fail("Java access accepted");
        } catch (Exception expected) {}
        // A failed operation must not poison the next isolated process.
        assertTrue(new JSONObject(ProviderBridge.call(context, source, "metadata", "")).has("url"));
    }

    @Test
    public void signedInstallRejectsTamperingAndDowngradeAndRollsBack() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keys = generator.generateKeyPair();
        ProviderPackages.testTrust = new JSONObject().put(
            "publicKey",
            Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())
        );
        byte[] second = candidate(2),
            third = candidate(3);
        ProviderPackages.install(context, second, signature(keys, second));
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
        try {
            ProviderPackages.install(context, third, signature(keys, second));
            fail("Tampering accepted");
        } catch (GeneralSecurityException expected) {}
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
        ProviderPackages.install(context, third, signature(keys, third));
        assertEquals(3, ProviderPackages.current(context).getLong("version"));
        ProviderPackages.rollback(context);
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
        assertFalse(context.getSharedPreferences("ui", 0).getBoolean("providerAutoUpdates", true));
        assertEquals(3, context.getSharedPreferences("providerEngine", 0).getLong("highWater", 0));
        try {
            ProviderPackages.install(context, second, signature(keys, second));
            fail("Downgrade accepted");
        } catch (java.io.IOException expected) {}
        // Corrupted active disk content falls back to the independently verified previous slot.
        try (
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                new File(context.getFilesDir(), "provider-active.json")
            )
        ) {
            out.write(1);
        }
        assertEquals(2, ProviderPackages.current(context).getLong("version"));
    }

    @Test
    public void incompatiblePackageAndInvalidLocatorAreRejected() throws Exception {
        JSONObject pkg = new JSONObject(ProviderPackages.asset(context, "builtin.json"));
        pkg.put("runtime", 2);
        try {
            ProviderPackages.validate(pkg.toString().getBytes(StandardCharsets.UTF_8));
            fail("Runtime accepted");
        } catch (java.io.IOException expected) {}
        try {
            EngineSources.id(EngineSources.ROOT + "../bad");
            fail("Locator accepted");
        } catch (IllegalArgumentException expected) {}
    }

    private byte[] candidate(long version) throws Exception {
        return new JSONObject(ProviderPackages.asset(context, "builtin.json"))
            .put("version", version)
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] signature(KeyPair keys, byte[] payload) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(keys.getPrivate());
        s.update(payload);
        return s.sign();
    }
}
