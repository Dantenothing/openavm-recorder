# OpenAVM V5 setup guide

[Project introduction](../../README.md) · [V5 downloads](https://github.com/Dantenothing/openavm-recorder/releases/tag/v5.0.0)

For vehicle recording, start at section 1. For phone vehicle controls, start at section 2. Add section 3 only when you want to transfer recordings.

## 1. Vehicle: make your first recording

1. Park the vehicle. Open the [release page](https://github.com/Dantenothing/openavm-recorder/releases) in the head-unit browser and choose the V5 `OpenAVM-Recorder-` APK.
2. Install through the App Lab or installer flow available on your firmware. Update an existing project installation in place; do not uninstall first.
3. Open **AVM Recorder** and grant the camera and storage access it requests. Grant overlay access if you want the floating mirror. Available installation and permission screens depend on firmware.
4. Check the camera directions while parked. The first V5 upgrade applies right-hand drive; Front 1, Rear 2, Left 3, Right 4; Cabin Camera 1 and Infrared Camera 0. Adjust these if your vehicle differs.
5. Insert a recognised, writable USB drive, choose USB preferred, and check the **actual recording destination** shown by the app.
6. Select Surround and Normal, start recording, and confirm the recording state. Record a short clip, stop, and wait for saving to finish.
7. Open the recording library and play the clip.

**Success means the clip is saved and plays.** A preview alone is not a recording, and an inserted USB drive is not proof it is the active destination.

Same-signature updates preserve recordings and ordinary settings. Stop recording and wait for saving before unplugging USB. Back up important clips.

### Choose what happens when you return

In the return-behaviour setting, choose:

| Option | What happens |
| --- | --- |
| Logo entry | Tap the OpenAVM Logo to resume preview; long-press for its menu |
| Full floating window | Resume preview when the screen is on and unlocked |
| Full window with automatic recording | Explicitly enable the additional recording option; it is unchecked by default |
| No retained entry | Open the app manually when you return |

Confirm this preference after each installation or update. Saving it does not start recording immediately. Cameras can take time to become ready; check the preview and recording state. A manual stop prevents repeated automatic starts on that return.

Reopen the app after system termination or a head-unit restart. **This does not provide continuous parking recording or remote vehicle wake-up.** Closing the floating window does not stop a recording already in progress; use Stop.

### Keep an important clip

During normal recording, use the emergency-video action. It marks the available two preceding segments, the current segment and the next segment. The current and next segments must finish before they can be saved; fewer than four may exist.

Check the emergency-events library. Protection prevents automatic cleanup, but is not a backup and does not prevent explicitly confirmed permanent deletion.

## 2. Phone: vehicle connection and daily setup

### Sign in

Install the V5 `OpenAVM-Phone-` APK on Android, updating the original OpenAVM app in place. Open **OpenAVM**, then **More → Account & connection**. Sign in with an authorised ZEEKR account and select your vehicle. Guest access requires permission from the owner.

Refresh once and check the vehicle and data time. **Success means the correct vehicle is selected and status is readable.** The current Australian connection configuration is included; no protocol JSON is required. The current cloud-control baseline is Australian 7X / AU 1.6.6, not a claim of compatibility with every region.

The login session is encrypted on this phone and reused. Expired sessions, permission changes or a new phone may require another sign-in. You can skip the ZEEKR account if you only want local recordings.

### Set home and Sentry rules

Open the home/parking-location setting under **More**. Enter part of an address and choose a match, or use the phone's current location while at home. Check it and set a suitable home radius to allow for vehicle-position drift.

In the parking-guard settings, separately enable automatic factory Sentry activation away from home and automatic deactivation after confirmed arrival, as desired. Saving an address does not enable either rule.

Activation requires suitable recent vehicle, position, parking and lock evidence. Arrival deactivation needs two fresh confirmations at least one minute apart; fetching the same old record twice does not count.

A manual off action in OpenAVM or its widget pauses activation for the current parking session. A manual on action at home preserves it for that session. Actions in the factory app or head unit can only be recognised when observable state changes provide enough evidence. New confirmed driving and parking allow the rules to resume.

**Success means the saved home and enabled rules match your intentions.** Check operation history for later actions and results.

### Choose comfort preferences

Under **More**, open preconditioning settings and choose the target temperature, maximum duration and whether seats may ventilate or heat. Saving preferences does not start climate control.

Tap preconditioning on the home screen or widget to start; tap the same active button again to stop. Follow the sending, waiting and heating/cooling status. Acceptance of a command is not confirmation that the vehicle has completed it. Configure scheduled departures in **Automation**.

Weather, vehicle state and data age affect the result; no fixed time to reach the target is guaranteed. Amber/blue airflow indicates the heating/cooling process, not a separate temperature sensor.

### Add a widget

Open **More → Widgets** and choose **2×2, 4×1, 4×2 or 4×3**. Request addition, then confirm in the launcher's dialog.

If no dialog or widget appears, long-press an empty area of the home screen, open the widget picker, find OpenAVM and drag it onto the home screen. Grid size and resizing depend on the launcher.

Common lock, Sentry, find-car and preconditioning actions start from the widget. Its app entry opens the full interface. Check progress and vehicle confirmation rather than treating a tap animation as success.

**Success means a widget is present, opens OpenAVM and shows your vehicle with its data time.**

### Understand the two refresh buttons

| Control | Behaviour |
| --- | --- |
| Normal top refresh | Reads cloud vehicle status; does not start temporary climate control |
| Refresh next to temperature | Updates cabin temperature; may briefly start climate control, then request that this temporary session stop |
| Background updates | Query status; do not initiate the temperature-acquisition flow |

Tap an active temperature update again to end it. Existing climate control or preconditioning is not treated as a temporary session to shut down. If stopping is not yet confirmed, follow the status; a sent request is not confirmation that it stopped.

A sleeping vehicle may return the same old temperature. **Refresh success means a read succeeded, not that a new measurement was taken.** Temperature acquisition also requires vehicle feedback and may not produce a new value immediately.

### Allow the background work you need

Use **More → Background & automation** to check notifications, battery restrictions, background data and scheduled-departure alarm permissions. Nearby-device access is only needed for the optional vehicle Bluetooth trigger. Location is used for relevant actions such as setting home from the phone's current location, not for playing recordings.

**Success means the desired rules are enabled without missing required permissions.** Android restrictions, connectivity and vehicle sleep can still delay execution; this does not provide permanent real-time connectivity.

## 3. Pair both apps and transfer a recording

This is separate from ZEEKR account login. Local recording transfer does not require a cloud vehicle account or private server.

1. Use matching V5 versions. Connect the vehicle to the phone hotspot, or connect both devices to Wi-Fi that allows them to communicate.
2. On the phone, open the vehicle-connection page from **Media** or **More**. Start receiving, then open the secure pairing window.
3. On the vehicle's **Phone and vehicle tools** page, use **Find automatically** or enter the address shown on the phone. Enter the six-digit code and select **Verify and pair**.
4. In **Verify phone identity**, compare the **complete fingerprint** on both devices, group by group. If it matches, check the confirmation box and select **Confirm and pair**. Otherwise, cancel.
5. Send a saved recording from the vehicle. Wait for reception, open it on the phone, and try jumping to another segment.

**Success means a real recording arrived and plays across segments.** Discovery or a saved pairing alone does not demonstrate a successful transfer.

Later connections use the saved pairing while both devices share a network and reception is enabled. Use the phone's current address if it changes. Code expiry does not remove a saved pairing. Stop receiving when finished; received clips remain playable offline.

Update both apps and pair again once when migrating from V4. Do not uninstall to remove old pairing data or fall back to plaintext transfer. The phone receiver does not remotely start Recorder or stream live cameras.

## 4. Upgrades and a new phone

| Situation | Action |
| --- | --- |
| Existing project Recorder or original OpenAVM phone app | Install a matching-signature update in place |
| Early standalone capability-check/assistant app | This has a different package identity; ask for its migration path rather than uninstalling |
| Signature mismatch or upgrade rejection | Keep the existing app and data; report the old version and new APK filename |
| New phone | Sign in again, set home and preferences, add widgets, and pair for recordings if needed |
| Keeping both phones | Pause departures, away guard and arrival deactivation on the old phone before enabling them on the new one |

An in-place update is not cross-phone data migration. An APK does not carry your account, home, widgets or received recordings. Back up important media separately.

## 5. Troubleshooting

| Symptom | Check |
| --- | --- |
| Refresh succeeds but the value/time is unchanged | It may be the vehicle's previous report. Check its time; use the separate temperature action when needed |
| Sentry does not change immediately | Enabled rules, home radius, fresh data, background permissions and operation history; arrival needs two confirmations |
| Tailgate does not lift | Treat the action as tailgate release/unlock; use the physical button if needed. Powered opening/closing is not promised |
| Only the Logo appears on return | Tap it for preview, or select full-window mode |
| Black preview or waiting after return | Allow camera readiness, then check permissions, factory camera use and whether the app is still running |
| No recording transfer | Shared network, receiving enabled, matching V5 versions and completed secure pairing; check network isolation |
| An old recording fails when jumping segments | Keep the original and report its source, exact error and both app versions |
| USB is inserted but recording uses internal storage | Check the displayed destination and whether USB is recognised and writable |

Normal segment continuity does not cover every interruption. A historical sleep test included a recording-writer failure whose cause remains under investigation; return-recovery changes do not establish that this separate failure is fixed. Report the time, mode and storage destination after an interruption, and check recordings needed for important uses.

Offline help is also available in the phone's beginner guide and the vehicle's tutorial/help entry. Report versions, vehicle/firmware, steps and exact messages. Hide personal data and pairing information; do not attach private footage to public issues.
