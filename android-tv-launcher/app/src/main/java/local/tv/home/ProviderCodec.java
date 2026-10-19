package local.tv.home;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.*;

/** Bounded pure-data Node/Web compatibility. No files, networking or app state. */
final class ProviderCodec {

    private ProviderCodec() {}

    static String call(String operation, String input) throws Exception {
        if (input.length() > ProviderScript.MAX_BYTES) throw new IOException("Codec input limit");
        byte[] bytes = input.getBytes(StandardCharsets.ISO_8859_1);
        switch (operation) {
            case "hexencode":
                StringBuilder encoded = new StringBuilder();
                for (byte b : bytes) encoded.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                return encoded.toString();
            case "sha256":
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(StandardCharsets.UTF_8)
                );
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                return hex.toString();
            case "base64encode":
                return Base64.getEncoder().encodeToString(bytes);
            case "base64decode":
                return new String(Base64.getDecoder().decode(input), StandardCharsets.ISO_8859_1);
            case "utf8encode":
                return new String(input.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            case "utf8decode":
                return new String(bytes, StandardCharsets.UTF_8);
            case "gunzip":
            case "inflate":
                try (
                    InputStream in = operation.equals("gunzip")
                        ? new GZIPInputStream(new ByteArrayInputStream(bytes))
                        : new InflaterInputStream(new ByteArrayInputStream(bytes));
                    ByteArrayOutputStream out = new ByteArrayOutputStream()
                ) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (out.size() + n > ProviderScript.MAX_BYTES) throw new IOException(
                            "Inflated limit"
                        );
                        out.write(buffer, 0, n);
                    }
                    return out.toString(StandardCharsets.ISO_8859_1.name());
                }
            default:
                throw new IOException("Unsupported codec");
        }
    }
}
