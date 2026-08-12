# Third-party notices

This file concerns third-party components only. It does not grant a licence to the AVM Recorder project code.

## Android project dependencies

The Android project depends on components from the following upstream projects. Release builds use R8, so unused dependency code may be removed from a particular APK:

- AndroidX and Jetpack Compose — Apache License 2.0;
- Kotlin standard libraries and plugins — Apache License 2.0;
- Kotlin coroutines — Apache License 2.0;
- Kotlin serialization — Apache License 2.0;
- OkHttp and Okio — Apache License 2.0; and
- ZXing Core — Apache License 2.0.

The complete Apache License 2.0 text distributed with the application is available at [`app/src/main/assets/THIRD_PARTY_NOTICES.txt`](app/src/main/assets/THIRD_PARTY_NOTICES.txt).

Upstream licence references:

- AndroidX: <https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt>
- Kotlin: <https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt>
- Kotlin coroutines: <https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt>
- Kotlin serialization: <https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt>
- OkHttp 4.12.0: <https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt>
- Okio: <https://github.com/square/okio/blob/master/LICENSE.txt>
- ZXing: <https://github.com/zxing/zxing/blob/master/LICENSE>

Build and test tools downloaded by Gradle are not bundled in the release APK. They retain their respective upstream licences.
