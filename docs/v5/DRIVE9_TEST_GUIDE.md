# OpenAVM Recorder 5.1.0-drive9

Private head-unit candidate, version code 114. Install over drive8 without uninstalling. The phone remains on 5.1.0-drive4; pairing and recording-transfer formats are unchanged. No public GitHub release is changed.

## Changes

- Bounded recovery for a camera that produces no first frame, plus one clean preview reopen after sustained preview source loss. Both home and floating preview share this handling. Cleanup must be acknowledged before opening another camera.
- Session playback starts when opened.
- New cabin/IR recordings use camera-declared resolution up to 720p rather than always requesting VGA. Actual quality depends on the camera. Existing recordings remain unchanged; actual track resolution appears in playback details.
- Visible map zoom-in/zoom-out buttons, and improved native-map gesture handling inside the details pane.
- One short JSON includes recent preview startup/recovery evidence as well as the existing recording and format diagnostics.

## One stationary check

No road trip is needed for the camera/playback checks. Leave **Cabin on / IR off** to match your previous successful recording.

1. After installing, open the App and check the home preview. Return to the launcher and check the floating preview. If it stays black, let recovery finish or show an explicit failure instead of repeatedly tapping. If it still fails, copy one short JSON before manually restarting.
2. Record for at least three minutes, crossing the first two file boundaries. Stop and allow saving/cleanup to finish. Check that home and floating previews return, then start and stop once more.
3. Open the new session. It should begin playing. Switch between surround and cabin, seek across files, and check the cabin's listed resolution. A camera that only declares VGA remains at VGA.
4. Use a previously recorded route for map checks. The fixed **+ / −** buttons should zoom; pan/pinch should work on the road map. A stationary session may contain no useful route. A stationary test does not verify moving GPS/HUD accuracy.
5. Copy either **Floating window → ⋯ → Copy sleep check report** or **Settings → Developer tools → Recording diagnostics → Copy short JSON**. Developer tools can be shown by tapping the version entry seven times. One report is enough; keep the history and installed data.

## What still needs the car

Automatic source recovery, stop-to-preview reliability, higher-resolution concurrent cabin recording, and actual on-screen playback/map interaction require owner confirmation. Passing local tests does not establish hardware success. Do not uninstall or clear data as a prerequisite for testing.

`validation.json` records host test results and package checks for this exact APK. The included SHA-256 manifest identifies the delivered files.
