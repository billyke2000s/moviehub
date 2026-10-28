package local.tv.home;

import java.net.URI;
import java.util.*;

final class VodLinkPolicyTest {

    private static int checks;

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError(checks);
    }

    public static void main(String[] args) throws Exception {
        check(!VodMediaPolicy.rejected(60_000, 60_000));
        check(!VodMediaPolicy.rejected(0, -1));
        check(VodMediaPolicy.rejected(0, 119_999));
        check(VodMediaPolicy.rejected(7_200_000, 300_000));
        check(!VodMediaPolicy.rejected(7_200_000, 7_150_000));
        check(!VodMediaPolicy.compatible(7_200_000, 7_800_000));
        check(VodMediaPolicy.compatible(7_200_000, 7_210_000));
        VodSourceFailover.Source a = new VodSourceFailover.Source("a", "A", "p");
        VodSourceFailover.Source b = new VodSourceFailover.Source("b", "B", "p");
        VodSourceFailover.Source c = new VodSourceFailover.Source("c", "C", "p");
        a.failureDomain = b.failureDomain = "one";
        c.failureDomain = "two";
        b.rank = 100;
        c.rank = 10;
        VodSourceFailover queue = new VodSourceFailover();
        queue.setSources(Arrays.asList(a, b, c));
        check(queue.failover() == c); // independent host before an alias of the failed CDN
        check(queue.failover() == b); // still try the alias rather than discard different headers
        queue.setSources(Arrays.asList(a, b, c));
        a.failureDomain = b.failureDomain = c.failureDomain = "";
        check(queue.failover() == b); // ranked at selection, not arrival order
        List<String> resources = new ArrayList<>();
        String xml =
            "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'><BaseURL>cdn/</BaseURL><Period>" +
            "<AdaptationSet><SegmentTemplate media='seg-$RepresentationID$-$Number%05d$.m4s' initialization='init.m4s'/>" +
            "<Representation id='v'><BaseURL>video/</BaseURL></Representation>" +
            "<Representation id='a'><BaseURL>audio/</BaseURL></Representation></AdaptationSet></Period></MPD>";
        String rewritten = DashManifest.rewrite(
            xml,
            URI.create("https://example.com/path/main.mpd"),
            target -> {
                resources.add(target);
                return "http://127.0.0.1/grant/" + resources.size();
            }
        );
        check(
            resources.contains("https://example.com/path/cdn/video/seg-$RepresentationID$-$Number%05d$.m4s")
        );
        check(resources.contains("https://example.com/path/cdn/audio/init.m4s"));
        check(!rewritten.contains("<BaseURL>"));
        check(!rewritten.contains("media=\"seg-"));
        DashManifest.Template template = new DashManifest.Template(
            "https://cdn.example/$RepresentationID$/seg-$Number%05d$-$Time$.m4s"
        );
        check(
            template
                .expand("v0-video-1v1-00003v2-9000")
                .equals("https://cdn.example/video-1/seg-00003-9000.m4s")
        );
        for (String suffix : Arrays.asList(
            "v0-../secretv1-3v2-1",
            "v0-http://evilv1-3v2-1",
            "v0-okv1--1v2-0"
        )) {
            try {
                template.expand(suffix);
                throw new AssertionError();
            } catch (java.io.IOException expected) {
                checks++;
            }
        }
        try {
            DashManifest.rewrite(
                "<!DOCTYPE MPD [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><MPD>&x;</MPD>",
                URI.create("https://example.com/a"),
                target -> target
            );
            throw new AssertionError();
        } catch (java.io.IOException expected) {
            checks++;
        }
        try {
            DashManifest.rewrite(
                "<MPD><Period><ContentProtection/></Period></MPD>",
                URI.create("https://example.com/a"),
                target -> target
            );
            throw new AssertionError();
        } catch (java.io.IOException expected) {
            checks++;
        }
        System.out.println(checks + " VOD identity, duration, ranked recovery and DASH checks passed");
    }
}
