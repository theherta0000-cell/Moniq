Moniq - HiFi/OpenSubsonic client

This is a minimal Kotlin Android client built on Jetpack Compose that talks to an OpenSubsonic-compatible API (for example the hifi API).

Features added:
- Retrofit + SimpleXML for API calls
- AuthInterceptor that appends legacy `u` and `p` query params
- ExoPlayer based playback of `/rest/stream.view` URLs (project-ready).

Note: Media3 is the successor to ExoPlayer and you can migrate to AndroidX Media3 by replacing the ExoPlayer dependency with the Media3 BOM and artifacts and swapping imports from `com.google.android.exoplayer2.*` to `androidx.media3.*`. I attempted a Media3 migration but the required BOM artifacts couldn't be resolved in this environment; if you want I can try again with a different Media3 version or adjust repositories.
- A simple Compose UI to enter `hostname`, `username`, `password`, load top songs and play them

How to run
1. Open this project in Android Studio.
2. Let Gradle sync and build (dependencies were added in `app/build.gradle.kts`).
3. Run on an Android device or emulator with network access.
4. In the app, enter your `Hostname` (e.g. `https://api.401658.xyz`), `Username`, and `Password`, then tap "Connect & Load Top Songs".
5. Tap a song to stream.

Notes & Caveats
- Credentials are sent as query parameters `u` and `p` per legacy opensubsonic usage. This may be insecure over plain HTTP. Use HTTPS and/or tokens when available.
- This is a minimal demo to get you started. You may want to add proper settings storage (DataStore), error handling, progress states, album/artist browsing and proper XML models for more endpoints.
- If the API expects MD5 tokens instead of plaintext password, you should compute the token before adding it to the request (the AuthInterceptor currently forwards the provided value unchanged).