package local.tv.home;

import java.net.URI;

/** Extra HTTPS ports granted by a specific provider protocol; public-address checks remain separate. */
final class ProviderPorts {

    private ProviderPorts() {}

    static boolean allowed(String provider, URI uri) {
        if (uri.getPort() == -1 || uri.getPort() == 443) return true;
        // Current Player 2 publishes signed HLS on this CDN's HTTPS endpoint.
        return (
            provider.equals("dlhd") &&
            uri.getPort() == 8443 &&
            uri.getHost() != null &&
            uri.getHost().matches("[A-Za-z0-9-]+\\.7odxv0l067ka\\.net") &&
            uri.getRawPath().startsWith("/hls/")
        );
    }
}
