# Phone 5.0.1 candidate

This is a complete OpenAVM phone client with optional, user-configured Zeekr cloud access. Manufacturer connection parameters are no longer included or automatically downloaded. Local Recorder pairing, recording transfer, playback and export remain available without cloud setup.

- Import your independently prepared configuration, then sign in with your authorised account. Importing alone performs no cloud requests or vehicle actions.
- Existing users must import and sign in once when upgrading from the old configuration format. Cloud tasks are stopped; recordings and Recorder pairing are retained. Enable automation again when ready.
- Old queued controls cannot become valid again after changing configuration or accounts.
- Setup, help and unconfigured home-screen widgets explain the optional cloud connection. English and Simplified Chinese are supported.

Package identity remains com.dante.zeekrbridge, version code 52. The vehicle Recorder remains 5.0.0 and the local recording/pairing protocols are unchanged.

See [cloud setup](CLOUD_SETUP.md) and the [empty template](connection-config.template.json). The template contains no working parameters.

This file is a candidate release description. It does not mean the APK has been published or that every compatibility scenario has passed. Record final verification separately before publishing.
