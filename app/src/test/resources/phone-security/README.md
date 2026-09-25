# Public test identities

These DER certificates and PKCS#8 keys are synthetic fixtures for the JVM secure-transport tests. The tests exercise valid, renewed, expired, wrong-name, weak and impostor identities.

They are intentionally public, are not application-signing keys, and must never be used as a real device identity or as a trusted production key. They live only in the test source set and are not bundled in the Recorder APK. Production phone TLS identities are generated per installation with Android Keystore.
