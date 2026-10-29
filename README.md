# Xstream

Android streaming app (phone). Built from the StreamX codebase with the Spotlight Stage interface.

Builds run on GitHub Actions (**Build APK** workflow). Each published run creates a release with:

- `Xstream-phone.apk`: 64-bit ARM phones, with the **Live server**.
- `Xstream-phone-universal.apk`: every phone (64-bit and 32-bit ARM), without the Live server.
- `versions.json`: read by the in-app updater (each app downloads its own APK).

## Live server

The 64-bit app runs [PencariMovie Server](https://github.com/aiskendi/pencarimovie-server) (MIT) on the phone at
`127.0.0.1`. It is built unchanged from its tagged source for `aarch64-linux-android` by `tools/build-live-server.sh`
(cargo-ndk). The only thing left out is its own web player (`public/` player files, ~17 MB). The binary ships as
`lib/arm64-v8a/libxlive.so`, because Android only runs programs that come with the app. CI caches it per version
(`PM_TAG` in the workflows).

In the app it is "Live server": a third way to watch (Turbo / Classic / Live), its links also join Turbo and Classic,
and its music addon feeds the Music player.
