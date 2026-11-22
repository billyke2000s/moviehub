package local.tv.home;

import java.net.URI;

public final class ProviderPortsTest {

    private static int checks;

    private static void check(boolean expected, String provider, String url) {
        if (ProviderPorts.allowed(provider, URI.create(url)) != expected) throw new AssertionError(url);
        checks++;
    }

    public static void main(String[] args) {
        String media = "https://8z09jo.7odxv0l067ka.net:8443/hls/fixture.m3u8";
        check(true, "dlhd", media);
        check(false, "zlive", media);
        check(false, "freeshot", media);
        check(false, "dlhd", media.replace(":8443", ":8444"));
        check(false, "dlhd", media.replace("/hls/", "/admin/"));
        check(false, "dlhd", media.replace("/hls/", "/hls%2f"));
        check(false, "dlhd", media.replace("7odxv0l067ka.net", "unrelated.example"));
        check(false, "dlhd", media.replace("7odxv0l067ka.net", "7odxv0l067ka.net.evil.example"));
        check(false, "dlhd", media.replace("8z09jo.7odxv0l067ka.net", "127.0.0.1"));
        check(false, "dlhd", media.replace("8z09jo.", ""));
        check(true, "dlhd", media.replace(":8443", ":443"));
        check(true, "dlhd", media.replace(":8443", ""));
        check(true, "freeshot", "https://wideiptv.top/player/fixture");
        System.out.println(checks + " provider port checks passed");
    }
}
