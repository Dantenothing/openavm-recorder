# Drive9 investigation — drive8 owner feedback

## Evidence and limits

The owner reports that manually starting again produced continuous surround/cabin recording and working playback. Both the home view and floating window were black on first entry and after stopping. The supplied drive8 away JSON was copied before the successful retry: it documents the failed startup, not the later recording or the subsequent stop-to-preview transition.

In process `32150-1791173835300`, the standalone preview reports its first displayed frame at elapsed 91,361,025 ms. This event does not give the total displayed frame count. Its close is acknowledged and settled before the next recording starts. Recording session `601e0ee4-08c5-49ad-b1d1-ed0ef51d3b67` starts its segment at 91,365,046 ms. At 91,380,064 ms its shared-input source watchdog fails with no encoded frames or bytes. The saved sample, 445 ms before failure, already reports a source age of 14,572 ms. The camera closes at 91,384,864 ms; incomplete codec format / missing CSD_0 is reported afterward. Final cleanup settles at 91,385,081 ms, with no retained cleanup owner in the copied report.

This identifies a no-source startup and a later terminal-format symptom. It does not prove a driver, request-template or display-buffer defect. The first-frame event alone also does not establish sustained visible preview. The old sleep cycle belongs to another process. Drive8's format-rollover change is retained; its later success is owner feedback, not independently measured in this JSON.

## Bounded recovery

- For a shared-input normal recording with no first source frame, re-submit its existing request once after four seconds. Device, capture session, encoder surface, recording identity and generation must still match. Stop, release, screen-off or revoked recording authority blocks the request. A timer delayed beyond the source deadline is discarded. The existing 15-second source watchdog is not extended, and no replacement recording or file is created.
- Standalone preview applies the same once-only request retry when its input has no frames. Capture receipts and actual input/display frames are reported separately.
- A preview that has no displayed first frame by its existing deadline, or whose previously active source remains stale, can get one clean reopen per requested preview. This uses the existing handoff gate: service destruction, camera close acknowledgement, other owners and GL release must all settle. A timeout cannot free ownership. Recording and unrelated camera/cleanup failures are not auto-restarted.
- Outgoing preview callbacks carry their original handoff identity; a cancelled or replaced request cannot fail or reopen its successor.
- Both short-copy paths retain a small `previewStartup` summary with recent source, display and handoff events. It is labelled as recent runtime evidence, separately from the selected recording. It includes no image, coordinate, file path or account credential.

These are bounded recovery measures to validate on the head unit, not a claim that the vendor source-loss cause is established.

## Playback and picture quality

Session playback previously initialized its play intent to false. Opening a session now starts playback, subject to the existing foreground, pause and decoder-error handling.

Auxiliary cabin/IR recording previously overrode every selected profile with 640×480 at 2 Mb/s. It now chooses the largest declared ordinary camera size within 1280×720, requesting 2 Mb/s through VGA and 4 Mb/s above VGA. The request remains 15 fps without asserting a measured frame rate. Undeclared sizes are never fabricated; missing or unsupported channels still fail independently. Internal-storage capacity estimates reserve up to 4 Mb/s per selected cabin channel. Playback lists actual track resolution when available. Existing VGA files cannot acquire more detail through this update; higher-resolution concurrent encoding still needs vehicle validation.

Map zoom controls were at the far end of a horizontally scrolling toolbar. They now remain visible over the map, with 48 dp targets and accessible labels. The native map also keeps pan/pinch gestures from being intercepted by its surrounding details scroller. Road tiles, local trace and map attribution remain available through the existing provider.

## Verification boundary

The initial seven source-retry/profile policy tests compiled and produced four expected failures against stubs before implementation. Additional expiry and report-size regressions failed before their corrections. Preview evidence is capped at 3 KiB and keeps a size-omission counter. Fresh final test totals, lint results, archive/signature checks and source commit are recorded in the separately hashed delivery `validation.json`. Android tests are compiled, not run on a device. No local head unit is connected, and no final-binary camera, visual-layout or higher-resolution recording success is claimed. Public releases are unchanged.
