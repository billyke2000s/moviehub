package local.tv.home;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** Repeat the generated 30-second fixture into genuine 150-second media, without weakening duration checks. */
final class VodTestMedia {

    private static byte[] cached;

    static synchronized byte[] movie(Context context, Context assets) throws Exception {
        if (cached != null) return cached;
        File input = new File(context.getCacheDir(), "vod-input.mp4");
        File output = new File(context.getCacheDir(), "vod-movie.mp4");
        try (var stream = assets.getAssets().open("playback.mp4")) {
            Files.copy(stream, input.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            extractor.setDataSource(input.getPath());
            int count = extractor.getTrackCount();
            int[] tracks = new int[count];
            for (int track = 0; track < count; track++) tracks[track] = muxer.addTrack(
                extractor.getTrackFormat(track)
            );
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int track = 0; track < count; track++) {
                extractor.selectTrack(track);
                for (int repeat = 0; repeat < 5; repeat++) {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    while (true) {
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        info.set(
                            0,
                            size,
                            extractor.getSampleTime() + repeat * 30000000L,
                            extractor.getSampleFlags()
                        );
                        muxer.writeSampleData(tracks[track], buffer, info);
                        extractor.advance();
                    }
                }
                extractor.unselectTrack(track);
            }
            muxer.stop();
        } finally {
            extractor.release();
            muxer.release();
            input.delete();
        }
        cached = Files.readAllBytes(output.toPath());
        output.delete();
        return cached;
    }
}
