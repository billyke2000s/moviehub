package local.tv.home;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Over-the-air scraper packages and update addresses: what a box accepts and refuses. */
public final class ScraperBundleTest {

    private static int checks;

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("Scraper " + checks + ": " + what);
        checks++;
    }

    private static void refused(byte[] zip, String what) {
        try {
            ScraperBundle.read(zip);
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Scraper " + checks + ": accepted " + what);
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, String> valid() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("scrapers.properties", "version=1700000000\napi=" + ScraperBundle.API + "\napp=124\n");
        entries.put("python/moviehub_vod.py", "print('vod')\n");
        entries.put("python/moviehub_providers.json", "[]");
        entries.put("python/moviehub_native/primewire.py", "x = 1\n");
        return entries;
    }

    private static Map<String, String> with(String name, String value) {
        Map<String, String> entries = valid();
        entries.put(name, value);
        return entries;
    }

    private static Map<String, String> without(String name) {
        Map<String, String> entries = valid();
        entries.remove(name);
        return entries;
    }

    public static void main(String[] args) throws Exception {
        ScraperBundle bundle = ScraperBundle.read(zip(valid()));
        check(bundle.version == 1700000000L && bundle.app == 124, "manifest read");
        check(bundle.files.containsKey("moviehub_native/primewire.py"), "nested file kept");
        check(bundle.compatible(124), "same app version accepted");
        check(bundle.compatible(123), "older app accepted (same Java-Python contract)");
        check(!bundle.compatible(125), "newer APK keeps its own newer scrapers");

        // Files that could escape the folder, replace the loader or are not scraper source.
        refused(zip(with("python/../evil.py", "")), "path traversal");
        refused(zip(with("python/a/../../evil.py", "")), "nested traversal");
        refused(zip(with("/python/abs.py", "")), "absolute path");
        refused(zip(with("python/moviehub_loader.py", "")), "replacement loader");
        refused(zip(with("python/lib.so", "")), "native library");
        refused(zip(with("python/.hidden.py", "")), "hidden file");
        refused(zip(with("other/file.py", "")), "file outside python/");
        refused(zip(with("python/sub\\x.py", "")), "backslash path");
        // Incomplete or malformed packages.
        refused(zip(without("scrapers.properties")), "missing manifest");
        refused(zip(without("python/moviehub_vod.py")), "missing moviehub_vod.py");
        refused(zip(without("python/moviehub_providers.json")), "missing provider list");
        refused(zip(with("scrapers.properties", "version=abc\napi=1\napp=1\n")), "bad version");
        refused(zip(with("scrapers.properties", "version=0\napi=1\napp=1\n")), "zero version");
        refused(new byte[0], "empty file");
        refused(new byte[ScraperBundle.MAX_ZIP + 1], "oversized file");
        refused("not a zip".getBytes(StandardCharsets.UTF_8), "non-zip");

        Map<String, String> otherApi = with(
            "scrapers.properties",
            "version=5\napi=" + (ScraperBundle.API + 1) + "\napp=999\n"
        );
        check(!ScraperBundle.read(zip(otherApi)).compatible(1), "other Java-Python contract ignored");

        // Extraction writes the files and a manifest the installer can read back.
        File dir = new File(Files.createTempDirectory("scrapers").toFile(), "1700000000");
        bundle.extractTo(dir);
        check(new File(dir, "python/moviehub_native/primewire.py").isFile(), "extracted nested file");
        long[] manifest = ScraperBundle.installedManifest(dir);
        check(
            manifest != null && manifest[0] == 1700000000L && manifest[1] == ScraperBundle.API,
            "manifest back"
        );
        try {
            bundle.extractTo(dir);
            throw new AssertionError("extracted over an existing folder");
        } catch (IOException expected) {
            checks++;
        }

        // Update addresses: self-hosted first, GitHub kept as a fallback; unsafe addresses ignored.
        check(UpdateFeeds.base("").isEmpty(), "unset address");
        check(UpdateFeeds.base("http://updates.example.com").isEmpty(), "plain http refused");
        check(UpdateFeeds.base("https://u:p@updates.example.com").isEmpty(), "user name refused");
        check(UpdateFeeds.base("https://updates.example.com:8443").isEmpty(), "custom port refused");
        check(UpdateFeeds.base("https://updates.example.com/?a=b").isEmpty(), "query refused");
        check(
            UpdateFeeds.base(" https://updates.example.com ").equals("https://updates.example.com/"),
            "normalised"
        );
        List<UpdateFeeds.Feed> app = UpdateFeeds.app("https://updates.example.com/mh", "k", false);
        check(app.size() == 2, "two app feeds");
        check(
            app.get(0).root.equals("https://updates.example.com/mh/app/") && app.get(0).key.equals("k"),
            "own first"
        );
        check(
            app.get(1).root.equals(UpdateFeeds.GITHUB_APP) && app.get(1).key.isEmpty(),
            "GitHub without key"
        );
        check(UpdateFeeds.app("", "k", false).size() == 1, "GitHub only when unset");
        check(
            UpdateFeeds.app("https://u.example.com", "", true)
                .get(1)
                .root.equals(UpdateFeeds.GITHUB_APP_EXPERIMENTAL),
            "experimental keeps its own feed"
        );
        check(UpdateFeeds.scrapers("", "k", false).isEmpty(), "no scraper feed without own server");
        check(
            UpdateFeeds.scrapers("https://u.example.com", "k", true)
                .get(0)
                .root.endsWith("/scrapers-experimental/"),
            "experimental scraper feed"
        );
        UpdateFeeds.Feed own = app.get(0),
            github = app.get(1);
        check(own.allows("updates.example.com") && !own.allows("evil.example.com"), "own feed host only");
        check(!own.allows("objects.githubusercontent.com"), "own feed cannot redirect to GitHub");
        check(
            github.allows("objects.githubusercontent.com") && !github.allows("updates.example.com"),
            "GitHub hosts"
        );
        check(!own.allows(null), "missing host refused");

        System.out.println("ScraperBundleTest: " + checks + " checks passed");
    }
}
