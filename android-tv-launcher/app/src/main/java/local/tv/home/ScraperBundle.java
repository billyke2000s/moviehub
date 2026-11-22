package local.tv.home;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A film/series scraper package: a zip holding scrapers.properties and a complete copy of the app's
 * python/ folder. The whole folder ships every time (about 0.9 MB), so a package can never mix old and
 * new scraper files. The signature is checked over the exact zip bytes before this class reads it.
 *
 * Pure Java (no Android types) so the core checks can run it on a desktop JVM.
 */
final class ScraperBundle {

    /**
     * The Java-to-Python contract (FreeVodProviders calling moviehub_vod). Raise it in the same change
     * as any change to that contract; boxes ignore packages built for a different number.
     */
    static final int API = 1;

    static final int MAX_ZIP = 4 * 1024 * 1024;
    static final int MAX_FILES = 3000;
    static final long MAX_TOTAL = 24L * 1024 * 1024;
    static final int MAX_FILE = 4 * 1024 * 1024;
    static final String MANIFEST = "scrapers.properties";
    static final String PREFIX = "python/";

    /** File names inside python/: plain relative paths of .py, .json and .txt files. */
    private static final Pattern NAME = Pattern.compile(
        "[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*\\.(py|json|txt)"
    );

    /** Files a package must contain, and the loader that always stays the APK's own copy. */
    static final List<String> REQUIRED = Collections.unmodifiableList(
        Arrays.asList("moviehub_vod.py", "moviehub_providers.json")
    );
    static final String LOADER = "moviehub_loader.py";

    final long version;
    final int api;
    /** versionCode of the app source the package was built from. */
    final long app;
    /** Path inside python/ to file contents. */
    final Map<String, byte[]> files;

    private ScraperBundle(long version, int api, long app, Map<String, byte[]> files) {
        this.version = version;
        this.api = api;
        this.app = app;
        this.files = files;
    }

    static boolean safeName(String name) {
        return (
            name != null &&
            name.length() <= 200 &&
            NAME.matcher(name).matches() &&
            !name.contains("..") &&
            !name.equals(LOADER)
        );
    }

    /**
     * Whether this box should run the package. A package built from older app source than the
     * installed APK is ignored: the APK's own scrapers are at least as new.
     */
    boolean compatible(long installedVersionCode) {
        return api == API && app >= installedVersionCode;
    }

    static ScraperBundle read(byte[] zip) throws IOException {
        if (zip.length == 0 || zip.length > MAX_ZIP) throw new IOException("Scraper package size rejected");
        Map<String, byte[]> files = new TreeMap<>();
        Properties manifest = null;
        long total = 0;
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    if (!name.equals(PREFIX) && !(name.startsWith(PREFIX) && safeDir(name))) {
                        throw new IOException("Scraper package folder rejected");
                    }
                    continue;
                }
                if (++count > MAX_FILES) throw new IOException("Scraper package has too many files");
                byte[] data = readEntry(in);
                total += data.length;
                if (total > MAX_TOTAL) throw new IOException("Scraper package too large");
                if (name.equals(MANIFEST)) {
                    if (manifest != null) throw new IOException("Duplicate scraper manifest");
                    manifest = new Properties();
                    manifest.load(
                        new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)
                    );
                    continue;
                }
                if (!name.startsWith(PREFIX) || !safeName(name.substring(PREFIX.length()))) {
                    throw new IOException("Scraper package file rejected: " + name);
                }
                if (files.put(name.substring(PREFIX.length()), data) != null) {
                    throw new IOException("Duplicate scraper file");
                }
            }
        }
        if (manifest == null) throw new IOException("Missing scraper manifest");
        for (String required : REQUIRED)
            if (!files.containsKey(required)) throw new IOException("Scraper package incomplete");
        try {
            long version = Long.parseLong(manifest.getProperty("version", "").trim());
            int api = Integer.parseInt(manifest.getProperty("api", "").trim());
            long app = Long.parseLong(manifest.getProperty("app", "").trim());
            if (version < 1 || app < 1) throw new IOException("Scraper manifest rejected");
            return new ScraperBundle(version, api, app, Collections.unmodifiableMap(files));
        } catch (NumberFormatException e) {
            throw new IOException("Scraper manifest rejected");
        }
    }

    private static boolean safeDir(String name) {
        String inner = name.substring(PREFIX.length(), name.length() - 1);
        return (
            inner.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*") &&
            !inner.contains("..")
        );
    }

    private static byte[] readEntry(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_FILE) throw new IOException("Scraper file too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** Writes python/ files and the manifest under dir, which must not exist yet. */
    void extractTo(File dir) throws IOException {
        if (dir.exists()) throw new IOException("Scraper folder already exists");
        File python = new File(dir, "python");
        String root = python.getCanonicalPath() + File.separator;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            File target = new File(python, file.getKey());
            // Belt and braces: safeName already refuses anything that could leave the folder.
            if (!target.getCanonicalPath().startsWith(root)) throw new IOException("Scraper path rejected");
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException(
                "Cannot create scraper folder"
            );
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(file.getValue());
            }
        }
        try (OutputStream out = new FileOutputStream(new File(dir, MANIFEST))) {
            out.write(manifestText().getBytes(StandardCharsets.UTF_8));
        }
    }

    String manifestText() {
        return "version=" + version + "\napi=" + api + "\napp=" + app + "\n";
    }

    /** Reads an extracted package's manifest; null when missing or unreadable. */
    static long[] installedManifest(File dir) {
        Properties p = new Properties();
        try (
            Reader in = new InputStreamReader(
                new FileInputStream(new File(dir, MANIFEST)),
                StandardCharsets.UTF_8
            )
        ) {
            p.load(in);
            return new long[] {
                Long.parseLong(p.getProperty("version").trim()),
                Long.parseLong(p.getProperty("api").trim()),
                Long.parseLong(p.getProperty("app").trim()),
            };
        } catch (Exception e) {
            return null;
        }
    }
}
