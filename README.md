# StreamPort Android v2.2

Version 2.2 keeps the proven v2.0/v2.1 WebView, hls.js, CORS proxy and
fullscreen implementation unchanged and adds background playback.

Behavior:
- The HTML video reports real play, pause and ended events to Android.
- On play, Android starts a media-playback foreground service.
- The foreground service holds a partial CPU wake lock and a Wi-Fi lock while
  playback is active.
- Pressing Home while playback is active enters Android Picture-in-Picture.
- Turning off the display does not intentionally pause the WebView.
- Pause, video end, fatal HLS errors or destroying the player release the
  service and both locks.
- Explicitly dismissing or force-stopping the app ends playback because the
  WebView player no longer exists.

The background service exists only while a video is actively playing, so it
does not hold battery-related locks while the player is paused.
