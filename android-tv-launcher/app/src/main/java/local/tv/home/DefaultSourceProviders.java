package local.tv.home;

import java.util.*;

/** Manifest endpoints observed on 2026-10-14; runtime availability is variable. */
final class DefaultSourceProviders {

    static List<String> backups() {
        return Arrays.asList(
            "https://mediafusion.elfhosted.com/manifest.json",
            "https://comet.elfhosted.com/manifest.json"
        );
    }
}
