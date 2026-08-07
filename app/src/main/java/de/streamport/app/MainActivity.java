package de.streamport.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String APP_ORIGIN = "https://app.local";
    private static final String CDN_HOST =
            "d13z5uuzt1wkbz.cloudfront.net";

    private WebView webView;
    private FrameLayout fullscreenContainer;

    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int previousOrientation =
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private int previousSystemUiVisibility;
    private volatile boolean playbackActive;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        webView.addJavascriptInterface(
                new PlaybackBridge(),
                "AndroidPlayback"
        );

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        String browserUserAgent = settings.getUserAgentString();

        webView.setWebViewClient(
                new CdnCorsWebViewClient(browserUserAgent)
        );
        webView.setWebChromeClient(new FullscreenWebChromeClient());

        try {
            String html = readAsset("player.html");
            webView.loadDataWithBaseURL(
                    APP_ORIGIN + "/",
                    html,
                    "text/html",
                    "UTF-8",
                    null
            );
        } catch (IOException exception) {
            Toast.makeText(
                    this,
                    "Player konnte nicht geladen werden: "
                            + exception.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private final class PlaybackBridge {
        @JavascriptInterface
        public void onPlay() {
            runOnUiThread(() -> setPlaybackActive(true));
        }

        @JavascriptInterface
        public void onPause() {
            runOnUiThread(() -> setPlaybackActive(false));
        }

        @JavascriptInterface
        public void onEnded() {
            runOnUiThread(() -> setPlaybackActive(false));
        }
    }

    private void setPlaybackActive(boolean active) {
        playbackActive = active;
        updatePictureInPictureSettings();

        Intent serviceIntent = new Intent(
                this,
                PlaybackKeepAliveService.class
        );

        if (active) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            stopService(serviceIntent);
        }
    }

    private void updatePictureInPictureSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        PictureInPictureParams.Builder builder =
                new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(playbackActive);
            builder.setSeamlessResizeEnabled(true);
        }

        try {
            setPictureInPictureParams(builder.build());
        } catch (IllegalArgumentException ignored) {
            /*
             * A few heavily customized Android builds reject PiP parameters
             * temporarily during orientation changes. Playback itself remains
             * protected by the foreground service.
             */
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        if (!playbackActive
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                || isInPictureInPictureMode()) {
            return;
        }

        try {
            enterPictureInPictureMode(
                    new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9))
                            .build()
            );
        } catch (IllegalStateException ignored) {
            /*
             * PiP can be disabled by the user or unavailable during a short
             * configuration transition. The foreground service still keeps
             * playback alive in the background.
             */
        }
    }

    @Override
    public void onPictureInPictureModeChanged(
            boolean isInPictureInPictureMode,
            Configuration newConfig
    ) {
        super.onPictureInPictureModeChanged(
                isInPictureInPictureMode,
                newConfig
        );

        if (webView != null) {
            webView.evaluateJavascript(
                    "window.setNativePipMode && "
                            + "window.setNativePipMode("
                            + isInPictureInPictureMode
                            + ");",
                    null
            );
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream input = getAssets().open(name)) {
            byte[] buffer = new byte[8192];
            StringBuilder result = new StringBuilder();
            int count;

            while ((count = input.read(buffer)) != -1) {
                result.append(
                        new String(
                                buffer,
                                0,
                                count,
                                StandardCharsets.UTF_8
                        )
                );
            }
            return result.toString();
        }
    }

    private final class FullscreenWebChromeClient
            extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            return true;
        }

        @Override
        public void onShowCustomView(
                View view,
                CustomViewCallback callback
        ) {
            enterFullscreen(view, callback);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onShowCustomView(
                View view,
                int requestedOrientation,
                CustomViewCallback callback
        ) {
            enterFullscreen(view, callback);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }
    }

    private void enterFullscreen(
            View view,
            WebChromeClient.CustomViewCallback callback
    ) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden();
            return;
        }

        fullscreenView = view;
        fullscreenCallback = callback;
        previousOrientation = getRequestedOrientation();

        Window window = getWindow();
        View decorView = window.getDecorView();
        previousSystemUiVisibility = decorView.getSystemUiVisibility();

        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }

        fullscreenContainer.addView(
                view,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        webView.setVisibility(View.GONE);
        fullscreenContainer.setVisibility(View.VISIBLE);

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        );
        applyImmersiveFullscreen();
    }

    private void exitFullscreen() {
        if (fullscreenView == null) {
            return;
        }

        fullscreenContainer.removeView(fullscreenView);
        fullscreenContainer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        fullscreenView = null;

        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }

        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        getWindow().getDecorView().setSystemUiVisibility(
                previousSystemUiVisibility
        );
        setRequestedOrientation(previousOrientation);
    }

    @SuppressWarnings("deprecation")
    private void applyImmersiveFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus && fullscreenView != null) {
            applyImmersiveFullscreen();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (fullscreenView != null) {
            exitFullscreen();
            return;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        playbackActive = false;
        stopService(
                new Intent(this, PlaybackKeepAliveService.class)
        );
        exitFullscreen();

        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        }

        super.onDestroy();
    }

    private static final class CdnCorsWebViewClient
            extends WebViewClient {
        private final String userAgent;

        CdnCorsWebViewClient(String userAgent) {
            this.userAgent = userAgent;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request
        ) {
            String host = request.getUrl().getHost();
            if (host == null
                    || !CDN_HOST.equalsIgnoreCase(host)) {
                return null;
            }

            return proxyCdnRequest(request);
        }

        private WebResourceResponse proxyCdnRequest(
                WebResourceRequest request
        ) {
            HttpURLConnection connection = null;

            try {
                String method =
                        request.getMethod().toUpperCase(Locale.ROOT);

                if ("OPTIONS".equals(method)) {
                    return emptyCorsResponse(204, "No Content");
                }

                if (!"GET".equals(method)
                        && !"HEAD".equals(method)) {
                    return errorResponse(
                            405,
                            "Method Not Allowed",
                            "Nicht unterstützte Methode: " + method
                    );
                }

                connection = (HttpURLConnection)
                        new URL(request.getUrl().toString())
                                .openConnection();

                connection.setRequestMethod(method);
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setUseCaches(false);

                /*
                 * Reproduce the successful browser request, not the previous
                 * ExoPlayer request:
                 *
                 * - no fabricated Origin header;
                 * - no fabricated Referer header;
                 * - no cookies or credentials;
                 * - HEAD remains HEAD;
                 * - GET remains a normal browser-like GET.
                 *
                 * The missing CORS permission is added only to the response,
                 * exactly like a browser CORS-disabler extension does.
                 */
                connection.setRequestProperty(
                        "User-Agent",
                        userAgent
                );
                connection.setRequestProperty(
                        "Accept",
                        request.getRequestHeaders()
                                .getOrDefault("Accept", "*/*")
                );
                connection.setRequestProperty(
                        "Accept-Encoding",
                        "identity"
                );

                String range =
                        request.getRequestHeaders().get("Range");
                if (range != null && !range.isEmpty()) {
                    connection.setRequestProperty("Range", range);
                }

                int statusCode = connection.getResponseCode();
                String reason = safeReason(
                        statusCode,
                        connection.getResponseMessage()
                );

                Map<String, String> responseHeaders =
                        copyResponseHeaders(connection);
                addCorsHeaders(responseHeaders);

                String mimeType = determineMimeType(
                        connection.getContentType(),
                        request.getUrl().toString()
                );

                if ("HEAD".equals(method)
                        || statusCode == 204
                        || statusCode == 304) {
                    connection.disconnect();
                    return new WebResourceResponse(
                            mimeType,
                            null,
                            statusCode,
                            reason,
                            responseHeaders,
                            new ByteArrayInputStream(new byte[0])
                    );
                }

                InputStream body;
                if (statusCode >= 400) {
                    body = connection.getErrorStream();
                } else {
                    body = connection.getInputStream();
                }

                if (body == null) {
                    body = new ByteArrayInputStream(new byte[0]);
                }

                return new WebResourceResponse(
                        mimeType,
                        null,
                        statusCode,
                        reason,
                        responseHeaders,
                        new DisconnectingInputStream(body, connection)
                );
            } catch (Exception exception) {
                if (connection != null) {
                    connection.disconnect();
                }

                return errorResponse(
                        502,
                        "Bad Gateway",
                        exception.getClass().getSimpleName()
                                + ": " + exception.getMessage()
                );
            }
        }

        private WebResourceResponse emptyCorsResponse(
                int statusCode,
                String reason
        ) {
            Map<String, String> headers = new LinkedHashMap<>();
            addCorsHeaders(headers);

            return new WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    statusCode,
                    reason,
                    headers,
                    new ByteArrayInputStream(new byte[0])
            );
        }

        private WebResourceResponse errorResponse(
                int statusCode,
                String reason,
                String message
        ) {
            Map<String, String> headers = new LinkedHashMap<>();
            addCorsHeaders(headers);

            return new WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    statusCode,
                    reason,
                    headers,
                    new ByteArrayInputStream(
                            message.getBytes(StandardCharsets.UTF_8)
                    )
            );
        }

        private Map<String, String> copyResponseHeaders(
                HttpURLConnection connection
        ) {
            Map<String, String> result = new LinkedHashMap<>();

            for (Map.Entry<String, List<String>> entry
                    : connection.getHeaderFields().entrySet()) {
                String name = entry.getKey();
                List<String> values = entry.getValue();

                if (name == null
                        || values == null
                        || values.isEmpty()
                        || isHopByHopHeader(name)) {
                    continue;
                }

                result.put(name, String.join(", ", values));
            }

            return result;
        }

        private boolean isHopByHopHeader(String name) {
            String normalized = name.toLowerCase(Locale.ROOT);

            return normalized.equals("connection")
                    || normalized.equals("keep-alive")
                    || normalized.equals("proxy-authenticate")
                    || normalized.equals("proxy-authorization")
                    || normalized.equals("te")
                    || normalized.equals("trailers")
                    || normalized.equals("transfer-encoding")
                    || normalized.equals("upgrade")
                    || normalized.equals("content-encoding");
        }

        private void addCorsHeaders(Map<String, String> headers) {
            headers.put(
                    "Access-Control-Allow-Origin",
                    APP_ORIGIN
            );
            headers.put(
                    "Access-Control-Allow-Methods",
                    "GET, HEAD, OPTIONS"
            );
            headers.put(
                    "Access-Control-Allow-Headers",
                    "Range, Accept, Content-Type"
            );
            headers.put(
                    "Access-Control-Expose-Headers",
                    "Content-Length, Content-Range, Accept-Ranges, ETag"
            );
            headers.put("Vary", "Origin");
        }

        private String determineMimeType(
                String contentType,
                String url
        ) {
            if (contentType != null
                    && !contentType.trim().isEmpty()) {
                int separator = contentType.indexOf(';');
                return separator >= 0
                        ? contentType.substring(0, separator).trim()
                        : contentType.trim();
            }

            if (url.toLowerCase(Locale.ROOT).endsWith(".ts")) {
                return "video/mp2t";
            }

            return "application/octet-stream";
        }

        private String safeReason(
                int statusCode,
                String reason
        ) {
            if (reason != null
                    && !reason.trim().isEmpty()
                    && reason.chars().allMatch(
                            character -> character >= 32
                                    && character <= 126
                    )) {
                return reason;
            }

            switch (statusCode) {
                case 200:
                    return "OK";
                case 204:
                    return "No Content";
                case 206:
                    return "Partial Content";
                case 301:
                    return "Moved Permanently";
                case 302:
                    return "Found";
                case 304:
                    return "Not Modified";
                case 400:
                    return "Bad Request";
                case 403:
                    return "Forbidden";
                case 404:
                    return "Not Found";
                case 405:
                    return "Method Not Allowed";
                case 416:
                    return "Range Not Satisfiable";
                case 500:
                    return "Internal Server Error";
                case 502:
                    return "Bad Gateway";
                default:
                    return "HTTP " + statusCode;
            }
        }
    }

    private static final class DisconnectingInputStream
            extends FilterInputStream {
        private final HttpURLConnection connection;

        DisconnectingInputStream(
                InputStream input,
                HttpURLConnection connection
        ) {
            super(input);
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
