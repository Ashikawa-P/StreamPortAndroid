package de.gabriel.streamport;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.datasource.DefaultDataSource;
import com.google.android.exoplayer2.datasource.DefaultHttpDataSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final Pattern VIDEO_ID_REGEX = Pattern.compile(
            "([a-z0-9]{10})(?:/|\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final String CDN_ROOT = "https://d13z5uuzt1wkbz.cloudfront.net";
    private static final String REFERER = "https://www.skill-capped.com/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/149 Mobile Safari/537.36";

    private EditText urlInput;
    private Button streamButton;
    private TextView statusText;
    private ProgressBar progressBar;
    private PlayerView playerView;
    private ExoPlayer player;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isLoading = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        streamButton = findViewById(R.id.streamButton);
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
        playerView = findViewById(R.id.playerView);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        streamButton.setOnClickListener(view -> startStream());
        urlInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startStream();
                return true;
            }
            return false;
        });
    }

    private void startStream() {
        if (!isLoading.compareAndSet(false, true)) {
            return;
        }

        String rawUrl = urlInput.getText().toString().trim();
        if (rawUrl.isEmpty()) {
            finishLoading("Bitte eine URL eingeben.");
            return;
        }

        setLoadingUi(true, "Verarbeite URL …");

        if (rawUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            playHls(Uri.parse(rawUrl));
            finishLoading("Stream wird geladen.");
            return;
        }

        String videoId = extractVideoId(rawUrl);
        if (videoId == null) {
            finishLoading("Keine gültige zehnstellige Video-ID gefunden.");
            return;
        }

        worker.execute(() -> {
            try {
                int lastSegment = findLastSegment(videoId);
                File playlistFile = createPlaylist(videoId, lastSegment);

                runOnUiThread(() -> {
                    playHls(Uri.fromFile(playlistFile));
                    finishLoading("Gefunden: " + (lastSegment + 1) + " Segmente.");
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    String message = exception.getMessage() == null
                            ? "Unbekannter Fehler"
                            : exception.getMessage();
                    finishLoading("Fehler: " + message);
                    Toast.makeText(
                            MainActivity.this,
                            "Stream konnte nicht geladen werden.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private String extractVideoId(String rawUrl) {
        Matcher matcher = VIDEO_ID_REGEX.matcher(rawUrl);
        List<String> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        if (ids.isEmpty()) {
            return null;
        }
        return rawUrl.toLowerCase(Locale.ROOT).contains("browse3")
                ? ids.get(0)
                : ids.get(ids.size() - 1);
    }

    private int findLastSegment(String videoId) throws IOException {
        final int start = 300;
        final int maximum = 5000;
        int knownPresent;
        int knownMissing;

        if (segmentExists(videoId, start)) {
            knownPresent = start;
            int candidate = start + 100;
            while (candidate <= maximum && segmentExists(videoId, candidate)) {
                knownPresent = candidate;
                candidate += 100;
            }
            if (candidate > maximum) {
                throw new IOException("Kein Streamende bis Segment " + maximum + " gefunden.");
            }
            knownMissing = candidate;
        } else {
            knownMissing = start;
            int candidate = start - 50;
            while (candidate >= 0 && !segmentExists(videoId, candidate)) {
                knownMissing = candidate;
                candidate -= 50;
            }
            if (candidate < 0) {
                if (!segmentExists(videoId, 0)) {
                    throw new IOException("Keine erreichbaren Videosegmente gefunden.");
                }
                knownPresent = 0;
            } else {
                knownPresent = candidate;
            }
        }

        int low = knownPresent;
        int high = knownMissing;
        while (low + 1 < high) {
            int middle = low + (high - low) / 2;
            if (segmentExists(videoId, middle)) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private boolean segmentExists(String videoId, int index) throws IOException {
        updateStatus("Prüfe Segment " + index + " …");
        return requestExists(segmentUrl(videoId, index), true);
    }

    private boolean requestExists(String url, boolean useHead) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(useHead ? "HEAD" : "GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", REFERER);
            if (!useHead) {
                connection.setRequestProperty("Range", "bytes=0-0");
            }

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode <= 399) {
                return true;
            }
            if (responseCode == HttpURLConnection.HTTP_BAD_METHOD && useHead) {
                return requestExists(url, false);
            }
            if (responseCode == HttpURLConnection.HTTP_FORBIDDEN
                    || responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return false;
            }
            if (responseCode >= 500 && responseCode <= 599) {
                throw new IOException("Serverfehler HTTP " + responseCode);
            }
            return false;
        } finally {
            connection.disconnect();
        }
    }

    private File createPlaylist(String videoId, int lastSegment) throws IOException {
        StringBuilder playlist = new StringBuilder();
        playlist.append("#EXTM3U\n");
        playlist.append("#EXT-X-VERSION:3\n");
        playlist.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
        playlist.append("#EXT-X-TARGETDURATION:10\n");
        playlist.append("#EXT-X-MEDIA-SEQUENCE:0\n");

        for (int index = 0; index <= lastSegment; index++) {
            playlist.append("#EXTINF:10.0,\n");
            playlist.append(segmentUrl(videoId, index)).append('\n');
        }
        playlist.append("#EXT-X-ENDLIST\n");

        File output = new File(getCacheDir(), "generated-stream.m3u8");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(playlist.toString().getBytes(StandardCharsets.UTF_8));
        }
        return output;
    }

    private void playHls(Uri uri) {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setDefaultRequestProperties(java.util.Collections.singletonMap("Referer", REFERER));

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(uri)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();

        DefaultDataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(this, httpFactory);
        HlsMediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem);

        player.setMediaSource(mediaSource);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private String segmentUrl(String videoId, int index) {
        return CDN_ROOT + "/" + videoId + "/HIDDEN4500-"
                + String.format(Locale.ROOT, "%05d", index) + ".ts";
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void setLoadingUi(boolean loading, String message) {
        streamButton.setEnabled(!loading);
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        statusText.setText(message);
    }

    private void finishLoading(String message) {
        isLoading.set(false);
        setLoadingUi(false, message);
    }

    @Override
    protected void onDestroy() {
        playerView.setPlayer(null);
        player.release();
        worker.shutdownNow();
        super.onDestroy();
    }
}
