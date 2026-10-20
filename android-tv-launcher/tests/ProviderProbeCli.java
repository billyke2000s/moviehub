package local.tv.home;

import java.nio.file.*;

/**
 * Runs one provider operation step with the app's own interpreter limits (ProviderScript) and
 * prints how long the step took on stderr, for the live provider probe. Not part of the app.
 */
public final class ProviderProbeCli {

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        String out;
        try {
            out = ProviderScript.evaluate(
                Files.readString(Path.of(args[0])),
                args[1],
                Files.readString(Path.of(args[2]))
            );
        } catch (Throwable e) {
            System.err.println("ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.err.println("ELAPSED " + (System.nanoTime() - start) / 1_000_000);
            System.exit(2);
            return;
        }
        System.out.print(out);
        System.err.println("ELAPSED " + (System.nanoTime() - start) / 1_000_000);
    }
}
