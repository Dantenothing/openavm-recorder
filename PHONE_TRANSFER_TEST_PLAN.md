# v0.2.0-alpha10 phone transfer test plan

This build contains two APKs:

- Car/head-unit: `app/build/outputs/apk/debug/app-debug.apk`
- Android phone: `phone/build/outputs/apk/debug/phone-debug.apk`

Use only a trusted phone hotspot or private LAN. The alpha protocol is authenticated but not encrypted.

## First connection and integrity

1. Install both APKs. Connect the car to the phone hotspot (or put both devices on the same private Wi-Fi).
2. On the phone, open AVM Receiver, tap **Start receiver**, then **New code**.
3. On the car, open **手机**, use **自动查找** or type the IP shown by the phone, enter the six-digit code, and pair.
4. Open one finalized recording on the car and tap **发送到手机**.
5. Confirm progress reaches completion, the phone lists exactly one MP4, and Play/Share work.
6. Share the MP4 to a computer and compare SHA-256 with the car task evidence if deeper integrity validation is needed.

## Required reliability cases

1. **Cancel queued:** queue two recordings, immediately cancel the second. It must become cancelled and never appear on the phone.
2. **Cancel transferring:** cancel a large recording in progress. The phone must not expose a partial MP4. A completion that won the final commit race may correctly remain completed.
3. **Network resume:** during a large transfer disable the hotspot/Wi-Fi for 30 seconds, then restore it. The task must show waiting and continue from received chunks rather than restart from zero.
4. **Phone background:** start a transfer, put AVM Receiver in the background for at least three minutes, then return. Transfer should continue.
5. **Car background:** start a transfer, switch the head unit to another app for at least three minutes, then return. Transfer should continue and recording/preview must remain unaffected.
6. **Process recovery:** interrupt one side during transfer, reopen it, check/re-establish the connection, and verify the task resumes without a duplicate final file.
7. **Concurrent recording regression:** record 360 while sending an older finalized segment. Confirm segment rollover, preview quality, manual Stop, OEM-camera recovery, and vehicle-away stop remain identical to alpha9.

Do not publish this alpha until cases 1–7 pass on real devices.
