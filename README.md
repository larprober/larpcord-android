# Larpcord for Android

An Android app that opens Discord's web app with Larpcord added. The Larpcord web build is injected before Discord's own code runs. The app also provides what a userscript manager would: network requests that aren't blocked by CORS (local and LAN addresses are refused), Android notifications, microphone and camera for calls, file uploads, and the back button.

## Building

The app needs the Larpcord userscript at `app/src/main/assets/larpcord.user.js`. It's generated, so it isn't in git. The easiest way to get everything is the release script in the main Larpcord repo, which builds the web version, copies it here and builds a signed APK:

```sh
cd ../larpcord
node scripts/larpcord/release.mjs
```

To build only the app after that, use `gradlew.bat assembleRelease`, or `gradlew.bat assembleDebug` for a debug build. The debug build lets Chrome DevTools attach to the WebView and logs page errors to logcat under the `Larpcord` tag.

## Signing

Release builds are signed with `keystore/larpcord-release.jks`, and its passwords are in `keystore.properties`. Both are kept out of git. Back them up somewhere safe: Android only installs an update over an existing install when it's signed with the same key.

## License

GPL-3.0-or-later, like Larpcord itself.
