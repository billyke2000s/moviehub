package local.tv.home;

import java.util.Locale;

/** Container/playlist/playing-track facts. No provider text is promoted into a fact. */
final class MediaFacts {

    enum Origin {
        UNKNOWN,
        FILE,
        PLAYLIST,
        PLAYING,
        STREAM,
    }

    static final class Value {

        final Origin origin;
        final int width, height, channels, videoBitrate, audioBitrate;
        final long durationMs;
        final String videoCodec, audioCodec, language;
        final boolean checkedVariant;
        final int playlistBandwidth;
        final String availableAudio;

        Value(
            Origin origin,
            int width,
            int height,
            String videoCodec,
            String audioCodec,
            int channels,
            String language,
            int videoBitrate,
            int audioBitrate,
            long durationMs
        ) {
            this(
                origin,
                width,
                height,
                videoCodec,
                audioCodec,
                channels,
                language,
                videoBitrate,
                audioBitrate,
                durationMs,
                false,
                0,
                ""
            );
        }

        private Value(
            Origin origin,
            int width,
            int height,
            String videoCodec,
            String audioCodec,
            int channels,
            String language,
            int videoBitrate,
            int audioBitrate,
            long durationMs,
            boolean checkedVariant,
            int playlistBandwidth,
            String availableAudio
        ) {
            this.checkedVariant = checkedVariant;
            this.playlistBandwidth = Math.max(0, playlistBandwidth);
            this.availableAudio = availableAudio;
            this.origin = origin;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.videoCodec = clean(videoCodec);
            this.audioCodec = clean(audioCodec);
            this.channels = Math.max(0, channels);
            this.language = clean(language);
            this.videoBitrate = Math.max(0, videoBitrate);
            this.audioBitrate = Math.max(0, audioBitrate);
            this.durationMs = Math.max(0, durationMs);
        }

        Value context(boolean variant, int bandwidth, String available) {
            return new Value(
                origin,
                width,
                height,
                videoCodec,
                audioCodec,
                channels,
                language,
                videoBitrate,
                audioBitrate,
                durationMs,
                variant,
                bandwidth,
                available
            );
        }

        int tier() {
            return VideoDetails.tier(width, height);
        }

        int audioRank() {
            return channels >= 8 ? 2 : channels >= 6 ? 1 : 0;
        }

        String picture() {
            return tier() == 0
                ? "Resolution unknown"
                : (origin == Origin.PLAYLIST ? "Up to " : "") +
                      QualityClaims.name(tier()) +
                      " · " +
                      width +
                      "×" +
                      height +
                      (origin == Origin.PLAYLIST ? " (playlist)" : " ✓") +
                      (checkedVariant && origin != Origin.PLAYING ? " (checked variant)" : "");
        }

        String channelLabel() {
            return channels == 1
                ? "Mono"
                : channels == 2
                  ? "Stereo"
                  : channels == 6
                    ? "5.1"
                    : channels == 8
                      ? "7.1"
                      : channels > 0
                        ? channels + "ch"
                        : "";
        }

        String audio() {
            String codec = audioName(audioCodec);
            String count = channelLabel();
            String text = codec + (count.isEmpty() ? "" : (codec.isEmpty() ? "" : " ") + count);
            boolean known = !text.isEmpty();
            if (!known) text = "Audio unknown";
            if (!language.isEmpty() && !language.equalsIgnoreCase("und")) text += " · " + language;
            return !known || availableAudio.isEmpty()
                ? text
                : "Available " + text + " (" + availableAudio + ")";
        }

        String bitrate() {
            String rate =
                videoBitrate <= 0 && audioBitrate <= 0 && playlistBandwidth > 0 ? "" : trackBitrate();
            return playlistBandwidth > 0 && origin != Origin.PLAYLIST
                ? (rate.isEmpty() ? "" : rate + " · ") +
                      VideoDetails.bitrate(playlistBandwidth) +
                      " playlist bandwidth"
                : rate;
        }

        private String trackBitrate() {
            if (videoBitrate <= 0) return audioBitrate > 0
                ? "Video bitrate unknown · " + VideoDetails.bitrate(audioBitrate) + " audio"
                : "Bitrate unknown";
            return (
                VideoDetails.bitrate(videoBitrate) +
                (origin == Origin.PLAYLIST ? " (playlist)" : " video") +
                (audioBitrate > 0 ? " + " + VideoDetails.bitrate(audioBitrate) + " audio" : "")
            );
        }

        /** Two-line source row: measured quality/channels/site, then the file's other facts. */
        String listLabel(String website) {
            String quality =
                tier() == 0
                    ? "Resolution unknown"
                    : (origin == Origin.PLAYLIST ? "Up to " : "") +
                      QualityClaims.name(tier()) +
                      (origin == Origin.PLAYLIST ? " (playlist)" : " ✓");
            String channels = channelLabel();
            String codec = audioName(audioCodec);
            String audio = channels.isEmpty() ? (codec.isEmpty() ? "Audio unknown" : codec) : channels;
            if (availableAudio.equals("playlist")) audio += " (playlist)";
            String detail = width > 0 && height > 0 ? width + "×" + height + " · " : "";
            String video = VideoDetails.codecName(videoCodec);
            if (!video.isEmpty()) detail += video + " · ";
            detail += codec.isEmpty()
                ? "Audio format unknown"
                : codec + (availableAudio.equals("playlist") ? " (playlist)" : "");
            if (!language.isEmpty() && !language.equalsIgnoreCase("und")) detail += " · " + language;
            return (
                quality +
                " · " +
                audio +
                " · " +
                website +
                "\n" +
                (checkedVariant ? "Checked HLS variant · " : "") +
                detail +
                " · " +
                bitrate()
            );
        }

        String describe() {
            return picture() + " · " + audio() + " · " + bitrate();
        }
    }

    static final Value UNKNOWN = new Value(Origin.UNKNOWN, 0, 0, "", "", 0, "", 0, 0, 0);
    volatile Value value = UNKNOWN;
    volatile Value inspected = UNKNOWN;

    void inspection(Value facts) {
        inspected = facts;
        if (value.origin != Origin.PLAYING) value = facts;
    }

    /** Native track snapshots can be partial. Never erase known headers or invent selected audio. */
    static Value combine(Value nativeFacts, Value headers) {
        boolean video = nativeFacts.width > 0 && nativeFacts.height > 0;
        boolean audio = !nativeFacts.audioCodec.isEmpty() || nativeFacts.channels > 0;
        boolean sameVideo =
            video &&
            nativeFacts.width == headers.width &&
            nativeFacts.height == headers.height &&
            !VideoDetails.codecName(nativeFacts.videoCodec).isEmpty() &&
            VideoDetails.codecName(nativeFacts.videoCodec).equals(VideoDetails.codecName(headers.videoCodec));
        boolean sameAudio =
            audio &&
            !audioName(nativeFacts.audioCodec).isEmpty() &&
            audioName(nativeFacts.audioCodec).equals(audioName(headers.audioCodec)) &&
            nativeFacts.channels == headers.channels;
        String available =
            audio || (headers.audioCodec.isEmpty() && headers.channels == 0)
                ? ""
                : !headers.availableAudio.isEmpty()
                  ? headers.availableAudio
                  : headers.origin == Origin.PLAYLIST
                    ? "playlist"
                    : "media headers";
        Origin origin = video
            ? Origin.PLAYING
            : headers.origin == Origin.UNKNOWN
              ? nativeFacts.origin
              : headers.origin;
        return new Value(
            origin,
            video ? nativeFacts.width : headers.width,
            video ? nativeFacts.height : headers.height,
            video ? nativeFacts.videoCodec : headers.videoCodec,
            audio ? nativeFacts.audioCodec : headers.audioCodec,
            audio ? nativeFacts.channels : headers.channels,
            audio ? nativeFacts.language : headers.language,
            origin == Origin.PLAYLIST
                ? headers.videoBitrate
                : nativeFacts.videoBitrate > 0
                  ? nativeFacts.videoBitrate
                  : sameVideo || !video
                    ? headers.origin == Origin.PLAYLIST
                        ? 0
                        : headers.videoBitrate
                    : 0,
            nativeFacts.audioBitrate > 0
                ? nativeFacts.audioBitrate
                : sameAudio || !audio
                  ? headers.audioBitrate
                  : 0,
            nativeFacts.durationMs > 0 ? nativeFacts.durationMs : headers.durationMs,
            headers.checkedVariant,
            headers.origin == Origin.PLAYLIST ? headers.videoBitrate : headers.playlistBandwidth,
            available
        );
    }

    private static String clean(String s) {
        return s == null
            ? ""
            : s
                  .replaceAll("[^a-zA-Z0-9 ._/-]", "")
                  .substring(0, Math.min(40, s.replaceAll("[^a-zA-Z0-9 ._/-]", "").length()));
    }

    static String audioName(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (s.contains("eac3") || s.contains("e-ac-3") || s.contains("ec-3")) return "EAC3";
        if (s.contains("ac3") || s.contains("ac-3")) return "AC3";
        if (s.contains("truehd") || s.contains("mlp")) return "TrueHD";
        if (s.contains("dts")) return "DTS";
        if (s.contains("aac") || s.contains("mp4a")) return "AAC";
        if (s.contains("opus")) return "Opus";
        if (s.contains("vorbis")) return "Vorbis";
        if (s.contains("flac")) return "FLAC";
        if (s.contains("mpeg") || s.contains("mp3")) return "MP3";
        if (s.contains("pcm") || s.contains("raw")) return "PCM";
        return raw == null ? "" : clean(raw);
    }
}
