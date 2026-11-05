package local.tv.home;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONObject;
import org.w3c.dom.*;

/** Production TCP relay: inherited DASH resources and per-origin cookie isolation. */
public final class ProviderDashTransportTest {

    public static void main(String[] args) throws Exception {
        AtomicInteger segments = new AtomicInteger();
        String mpd =
            "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'><BaseURL>cdn/</BaseURL><Period>" +
            "<AdaptationSet><SegmentTemplate media='seg-$RepresentationID$-$Number%05d$.m4s' initialization='init.m4s'/>" +
            "<Representation id='video'><BaseURL>video/</BaseURL></Representation>" +
            "<Representation id='audio'><BaseURL>https://1.1.1.1/audio/</BaseURL></Representation>" +
            "</AdaptationSet></Period></MPD>";
        ProviderRelay.testConnections = uri ->
            new HttpURLConnection(uri.toURL()) {
                public void connect() {}

                public void disconnect() {}

                public boolean usingProxy() {
                    return false;
                }

                public int getResponseCode() throws IOException {
                    if (
                        "8.8.8.8".equals(uri.getHost()) && !"fixture".equals(getRequestProperty("Cookie"))
                    ) throw new IOException("Manifest/segment lost its cookie");
                    if (
                        "1.1.1.1".equals(uri.getHost()) && getRequestProperty("Cookie") != null
                    ) throw new IOException("Credential leaked across DASH authorities");
                    if (!uri.getPath().endsWith(".mpd")) {
                        if (
                            !uri.getPath().endsWith("/init.m4s") &&
                            !uri.getPath().matches(".*/seg-(video|audio)-00003\\.m4s")
                        ) throw new IOException("Wrong substituted segment: " + uri.getPath());
                        segments.incrementAndGet();
                    }
                    return 200;
                }

                public InputStream getInputStream() {
                    return new ByteArrayInputStream(body());
                }

                public String getContentType() {
                    return uri.getPath().endsWith(".mpd") ? "application/dash+xml" : "video/mp4";
                }

                public long getContentLengthLong() {
                    return body().length;
                }

                private byte[] body() {
                    return (uri.getPath().endsWith(".mpd") ? mpd : "fixture-segment").getBytes(
                        StandardCharsets.UTF_8
                    );
                }
            };
        try (
            ProviderRelay relay = new ProviderRelay(
                new EngineSources.Stream(
                    new JSONObject()
                        .put("url", "https://8.8.8.8/path/manifest.mpd")
                        .put("headers", new JSONObject().put("Cookie", "fixture"))
                )
            )
        ) {
            String xml = read(relay.url);
            if (!xml.equals(read(relay.url))) throw new AssertionError(
                "Repeated manifests changed template grants"
            );
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory
                .newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(xml)));
            NodeList nodes = document.getElementsByTagNameNS("*", "Representation");
            if (nodes.getLength() != 2) throw new AssertionError("Representation lost");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element representation = (Element) nodes.item(i);
                Element template = (Element) representation
                    .getElementsByTagNameNS("*", "SegmentTemplate")
                    .item(0);
                if (
                    !"fixture-segment".equals(read(template.getAttribute("initialization")))
                ) throw new AssertionError("Init bytes changed");
                String media = template
                    .getAttribute("media")
                    .replace("$RepresentationID$", representation.getAttribute("id"))
                    .replace("$Number%05d$", "00003");
                if (!"fixture-segment".equals(read(media))) throw new AssertionError("Segment bytes changed");
            }
            if (segments.get() != 4) throw new AssertionError(
                "Not every manifest/init/media request went through the relay"
            );
        } finally {
            ProviderRelay.testConnections = null;
        }
        System.out.println("DASH TCP manifest, inherited init/media templates and cookie isolation passed");
    }

    private static String read(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setReadTimeout(5000);
        try (InputStream input = connection.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
