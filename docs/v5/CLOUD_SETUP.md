# Optional Zeekr cloud setup

These instructions cover [Phone 5.0.1](https://github.com/Dantenothing/openavm-recorder/releases/tag/v5.0.1), the current phone release. Recorder remains at 5.0.0. The previous bundled Phone 5.0.0 APK remains withdrawn.

OpenAVM is a complete phone client. **Recorder pairing, recording transfer, playback and export work without a Zeekr account or connection configuration.** Use the Media tab or the recording card on Home to start there.

Cloud status, controls, preconditioning and factory Sentry rules require a configuration you prepare independently and are entitled to use, followed by your own authorised Zeekr account. OpenAVM does not include, host or automatically download manufacturer connection parameters.

## Import a configuration

1. Open **Home → Set up cloud** or **More → Account & connection**.
2. Select **Import configuration** and choose your JSON file with the system file picker.
3. When the local format check passes, sign in with your own account and select the correct vehicle. Guest accounts need vehicle access granted by the owner.
4. Check the vehicle and the data time. Enable background refresh, Sentry rules or departure schedules separately if you want them.

Importing a file does not log in, query or control the vehicle. A successful format check does not prove the file's origin, compatibility or authorisation. The current client targets the Australian AU 1.6.6 protocol; this is not a claim of support for all regions or versions.

Choose **Set up later** to continue with local recordings. A saved imported configuration and valid login are reused on later starts; your password is not saved. An expired session may require signing in again.

## File format

Use the [empty template](connection-config.template.json). It is deliberately incomplete and cannot connect to a vehicle until you supply all six values. Downloading this template does not obtain the parameters for you.

| Field | Use |
| --- | --- |
| hmac_access_key | Client signing identifier; not an account access token |
| hmac_secret_key | HMAC request signing |
| password_public_key | X.509 RSA public key used to encrypt the login password |
| prod_secret | App request signing |
| vin_key | VIN encryption key, interpreted as UTF-8 text |
| vin_iv | VIN encryption IV, interpreted as UTF-8 text |

The file must be UTF-8 JSON, at most 64 KiB, with exactly these six non-empty string fields. Duplicate or additional fields are rejected. VIN keys must encode to 16, 24 or 32 bytes; the IV must encode to 16 bytes. The password public key accepts Base64 X.509 or a PUBLIC KEY PEM wrapper.

The file cannot change the login server. Do not import account exports, passwords, account tokens, control PINs or private keys. Do not put these or actual configuration values in public issue reports. OpenAVM saves its imported copy encrypted on this phone; keep the source file private yourself.

## Upgrading from Phone 5.0.0 or earlier

Earlier versions did not record whether a configuration was imported or automatically installed. The first upgrade to this configuration format therefore removes the old cloud configuration and login, stops old cloud tasks, and asks you to import again.

Recordings and Recorder pairing are kept. Home and comfort preferences remain, and departure plans are kept disabled for review. Cloud automation will not silently resume after import or login. Re-enable only the rules you want, for the correct vehicle.

If a temperature or preconditioning operation was in progress, the app cannot claim the vehicle stopped simply because its local task stopped. Check the vehicle or the official app. The migration does not send cleanup commands using the old configuration.

Subsequent ordinary updates preserve the new imported configuration and valid session. On another phone, prepare and import your configuration again; do not expect the APK to carry account or pairing data.

## Troubleshooting

| Message or situation | Next step |
| --- | --- |
| Not configured | Use local recordings or import a configuration when ready |
| Missing, duplicate or unexpected field | Compare the file's structure with the empty template; do not add account information |
| Invalid public key or VIN parameter length | Check the expected encoding and your independently prepared source |
| Configuration could not be saved | Retry the import; no temporary unencrypted configuration is enabled |
| Login rejected after format check | Confirm the region, protocol compatibility and your account's permissions; a valid JSON file does not establish these |
| An old widget action expired | Use the updated widget after completing setup; old queued commands are not replayed |

Configuration separation addresses how this project distributes the actual parameters. It does not establish manufacturer endorsement or resolve every third-party access term.
