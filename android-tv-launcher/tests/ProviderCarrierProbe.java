package local.tv.home;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Desktop diagnostic for captured public media; uses the production carrier decoder unchanged. */
public final class ProviderCarrierProbe {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
            "Expected encoded input and decoded output paths"
        );
        try (
            InputStream input = new FileInputStream(args[0]);
            OutputStream output = new FileOutputStream(args[1])
        ) {
            ProviderContainerSegments.write(input, output);
        }
        System.out.println("Decoded bytes: " + Files.size(Paths.get(args[1])));
    }
}
