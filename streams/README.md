# Hosted live TV conversion

The Loop Kodi ZIP contains several different types of sources. The Kodi-specific
sports resolvers cannot be safely treated as direct M3U streams. This pipeline
supports **only public M3U feeds**: GitHub Actions fetches feeds from
`streams/loop-feeds.txt` and writes a normalized `streams/loop.m3u`.

Run **Refresh hosted live TV playlist** once manually after merging, and
verify that `streams/loop.m3u` contains real channels before enabling its
URL in MovieHub. The previously proposed Samsung TV Plus UK feed returned HTTP 404 and was
removed. Add verified public HTTPS M3U feeds before enabling the app source.

Updates run every six hours. If an individual feed fails, the converter logs a warning and publishes the\nworking feeds. If every feed fails, the action fails and leaves the previous\npublished playlist intact. No Kodi code runs on devices or
in Actions. Do not put passwords, private URLs, tokens, or copyrighted
restricted streams in the public feed list.

This is a *partial* compatibility path. Dynamic session-based sources and
The Loop's obfuscated sports extractors are not converted by this script.
