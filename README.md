# StreamPort Android

Native Android port of the supplied HTML player.

The application accepts the original video URL, extracts its ten-character video ID, discovers the available MPEG-TS segments, creates a local HLS playlist, and plays it with ExoPlayer. Android's native HTTP stack does not apply browser CORS restrictions, so no CORS extension is required. FFmpeg is not bundled.

The GitHub Actions workflow creates an installable, debug-signed APK with a one-day artifact retention period.
