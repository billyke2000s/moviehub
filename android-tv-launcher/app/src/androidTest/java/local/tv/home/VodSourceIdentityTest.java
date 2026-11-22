package local.tv.home;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Identical media discovered through different catalogues is only one source row. */
@RunWith(AndroidJUnit4.class)
public final class VodSourceIdentityTest {

    @Test
    public void duplicateResolvedMediaIsCollapsedButDifferentPlaybackHeadersRemain() throws Exception {
        JSONArray files = new JSONArray().put(new JSONObject().put("path", "Film.mp4"));
        String link = "https://cdn.example/Film.mp4?token=abc";
        SourceRepository.Source direct = new SourceRepository.Source(
            "VidCore 1080p", link, files, new JSONObject().put("Referer", "https://player.example/")
        );
        direct.site = "vidcore.io";
        SourceRepository.Source throughPrimeWire = new SourceRepository.Source(
            "PrimeWire VidCore 1080p", link, files, new JSONObject().put("referer", "https://player.example/")
        );
        throughPrimeWire.site = "primewire1.buzz";
        assertEquals(direct.key(), throughPrimeWire.key());

        SourceRepository.Source withCookie = new SourceRepository.Source(
            "PrimeWire VidCore 1080p", link, files,
            new JSONObject().put("Referer", "https://player.example/").put("Cookie", "session=other")
        );
        withCookie.site = "primewire1.buzz";
        assertNotEquals(direct.key(), withCookie.key());

        SourceRepository.Source signedVariant = new SourceRepository.Source(
            "VidCore 1080p", link + "2", files, new JSONObject().put("Referer", "https://player.example/")
        );
        signedVariant.site = "vidcore.io";
        assertNotEquals(direct.key(), signedVariant.key());
    }
}
