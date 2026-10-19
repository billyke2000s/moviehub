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
        EngineSources.testCatalogue = null;
        EngineSources.invalidate();
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
        String large =
            "[{\"_id\":\"large\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8\",\"unused\":\"" +
            new String(new char[1200000]).replace('\0', 'x') +
            "\"}]";
        assertEquals(
            "large",
            new JSONArray(ProviderBridge.call(context, source, "catalogue", large))
                .getJSONObject(0)
                .getString("channelId")
        );
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

    @Test
    public void realBridgeRefreshPreservesStableChannelsFavouritesAndIndependentGuides() throws Exception {
        ChannelDatabase db = ChannelDatabase.get(context);
        db.clear();
        EngineSources.testCatalogue =
            "[{\"_id\":\"sport\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8?cb=[CB]\",\"epg\":{\"entries\":[{\"title\":\"Live Sport\",\"start\":\"2026-10-19T00:00:00Z\",\"stop\":\"2026-10-19T01:00:00Z\"}]}}]";
        try {
            assertEquals(1, EngineSources.refresh(context, db, () -> {}));
            String key = ChannelQuery.key(EngineSources.ROOT + "sport");
            ChannelDatabase.Row row = db.byKey(key);
            assertNotNull(row);
            assertEquals(EngineSources.ROOT + "sport", db.url(row.id, row.key));
            assertEquals(1, db.browse("favourites", "", java.util.Collections.singleton(key), 0, 10).size());
            // Separate XMLTV refresh must retain the JS provider's programme mapping.
            ChannelDatabase.GuideWriter xml = db.new GuideWriter(false);
            try {
                xml.match("xmlfixture", "xmltv");
                xml.programme("xmltv", 1, 100, "Other", "", "", "");
                xml.commit();
            } finally {
                xml.finish();
            }
            assertEquals("Live Sport", db.programmes(row, "uk", 1792368000000L, 1792371600000L).get(0).title);
            EngineSources.testCatalogue = "[]";
            try {
                EngineSources.refresh(context, db, () -> {});
                fail("Empty catalogue accepted");
            } catch (java.io.IOException expected) {}
            assertNotNull(db.byKey(key));
        } finally {
            db.clear();
            for (String prefix : new String[] { "enginefls:", null }) {
                ChannelDatabase.GuideWriter guide = db.new GuideWriter(prefix);
                try {
                    guide.commit();
                } finally {
                    guide.finish();
                }
            }
        }
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
