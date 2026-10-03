# OpenAVM Recorder 5.1.0-drive6

Private head-unit test build. Install over the existing Recorder without uninstalling. The phone remains on 5.1.0-drive4; transfer formats have not changed. This build includes the drive5 stop-to-preview and playback presentation changes.

## Installation and update setup

On first launch after installation or an update, review two pages:

1. **When you return:** keep the Logo, restore the full preview, or explicitly enable recording on return. Automatic recording requires a fresh confirmation after an update.
2. **Recording & playback:** choose optional cabin and infrared cameras, vehicle data for the HUD, GPS routes, and whether to open the map panel during playback.

Cabin and infrared are independent additions to **normal surround recording**. They use internal storage; surround keeps the existing storage choice. Enabling these options does not start a recording. They also apply to a later recording started by the return behavior. Camera mappings and stored channel identifiers are unchanged.

HUD data records available speed, gear, indicators and pedal readings alongside video. Showing or hiding the HUD is a separate display choice; these values are not burned into the exported video. Pedal depth is still an unverified raw reading, not a calibrated percentage.

GPS routes require location permission and a usable position source. Vehicle HUD data works independently of GPS. Opening the map panel does not turn on GPS recording or automatically load street tiles; the existing online-map choice remains separate. Older recordings without route data cannot gain a route retrospectively.

Fresh installs leave optional recording/data collection off. Updates show your saved channel/data choices for review. Saving applies the choices together and does not start recording or data collection; the normal home preview remains available. Stop an active recording before changing what it captures. You can change these choices later in Settings.

## Quick stationary check

- Install this APK, open OpenAVM, and check both setup pages. Confirm the cabin and IR names are clear.
- Select your desired channels, HUD data and GPS route; save. No recording should start merely because you saved.
- Check that Settings reflects the saved selections, then start a normal surround recording when ready. Confirm the selected channels are writing.
- Stop and start recording three times to recheck the home preview recovery from drive5.
- Open a recording with route data: the map panel should start open or closed according to your choice. You can still toggle it for that playback session.

No road test is required to check the setup choices. Actual GPS availability, three-camera recording and the stop/restart camera behavior still need the head unit. Build and test results are recorded separately in `validation.json`; no on-vehicle run of this binary is claimed.
