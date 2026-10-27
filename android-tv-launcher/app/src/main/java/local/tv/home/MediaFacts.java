package local.tv.home;

import java.util.Locale;

/** Container/playlist/playing-track facts. No provider text is promoted into a fact. */
final class MediaFacts {

    enum Origin {
        UNKNOWN,
        FILE,
        PLAYLIST,
        PLAYING,
    }

    static final class Value {

        final Origin origin;
        final int width, height, channels, videoBitrate, audioBitrate;
        final long durationMs;
        final String videoCodec, audioCodec, language;

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
                      (origin == Origin.PLAYLIST ? " (playlist)" : " ✓");
        }

        String audio() {
            String codec = audioName(audioCodec);
            String count =
                channels == 1
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
            String text = codec + (count.isEmpty() ? "" : (codec.isEmpty() ? "" : " ") + count);
            if (!language.isEmpty() && !language.equalsIgnoreCase("und")) text +=
                (text.isEmpty() ? "" : " · ") + language;
            return text.isEmpty() ? "Audio unknown" : text;
        }

        String bitrate() {
            if (videoBitrate <= 0) return "Bitrate unknown";
            return (
                VideoDetails.bitrate(videoBitrate) +
                (origin == Origin.PLAYLIST ? " (playlist)" : " video") +
                (audioBitrate > 0 ? " + " + VideoDetails.bitrate(audioBitrate) + " audio" : "")
            );
        }

        String describe() {
            return picture() + " · " + audio() + " · " + bitrate();
        }
    }

    static final Value UNKNOWN = new Value(Origin.UNKNOWN, 0, 0, "", "", 0, "", 0, 0, 0);
    volatile Value value = UNKNOWN;

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
