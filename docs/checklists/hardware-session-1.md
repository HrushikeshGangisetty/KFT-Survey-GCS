# Hardware session 1: everything that needs real hardware, in one sitting

Hrushikesh, bench, **props off**. This replaces `bench-session-1.md` and collects the manual steps from the pass log
(Passes 12–27). Order: the Skydroid T12 first (it answers questions that later passes depend on), then video,
telemetry, parameters, missions, the trigger, offline maps, and tablet-only checks.

For each step: do the **Action**, compare with **Expected**, write what you saw on the **Result** line (✓ is enough
when it matches). If a step fails, fill in "What to capture on failure" (last page) and go on to the next section.

**Safety:** the GCS never arms, changes mode, takes off or lands (spec S9), and nothing here asks it to. Arming
appears twice (1b.5, 4.6), on the RC, props off, to check that the GCS *refuses* things while armed.

---

## 0. Before you start (at the desk, ~20 min)

**0.1** Action: `git pull`, then `gradlew.bat check`.
Expected: BUILD SUCCESSFUL.
Result: ______________________________________________

**0.2** Action: in `local.properties`, `KFT_APP_SECRET=<the Android GCS's 64 hex characters>`. `git status`.
Expected: `local.properties` is not listed as a change.
Result: ______________________________________________

**0.3** Action: tablet on USB, `gradlew.bat :app:android:installDebug` (the key is compiled in, so after 0.2).
Expected: "KFT Survey (dev)" on the tablet.
Result: ______________________________________________

**0.4** Action: start the desktop app so its log is kept (PowerShell):
`gradlew.bat :app:desktop:run *> hw1-desktop.log`
Expected: the app opens on Fly. Close it before 1.1.
Result: ______________________________________________

**What you need on the bench:** KFT flight controller (ardupilotKFT) with GPS if possible · telemetry radio pair
(57600) and USB lead · USB-OTG adapter · RC transmitter with a spare 2-position switch · Skydroid T12 + its air unit ·
SIYI camera + MK15 air unit (or any RTSP camera) · the Android tablet · optional: HC-05/HC-06 Bluetooth module, LED
or servo tester for the trigger output.

---

## 1. Skydroid T12 on the laptop (first: answers ADR-004's open questions)

Set-up: T12 on the laptop's USB (its data port), T12 air unit powered and linked, flight controller powered on the
air unit's telemetry port. **Desktop GCS closed** for 1.1–1.3 (only one program can hold the T12's port).

**1.1 T12 at 4 Mbaud on Windows.** Action: Device Manager → Ports. Note the COM number and the driver
("Silicon Labs CP210x USB to UART Bridge", driver version under Properties → Driver).
Expected: one CP210x port.
Result: COM____ · driver version ____________________

**1.2 Record the T12 capture.** Action:
`powershell -ExecutionPolicy Bypass -File tools\t12\capture\capture.ps1 -Seconds 20`
Expected: "N KiB of video so far" rising every second, roughly 100 KiB/s.
If it prints "No Skydroid T12 on USB": the port wasn't found (note 1.1). If the KiB stay at 0 and it says "answers
but sends no video": the port opened at 4 Mbaud but no video came. If nothing is ever answered at all, Windows'
driver may be capping 4 Mbaud: note the driver version (1.1).
Result: ____ KiB after 20 s · message: ____________________

**1.3 Replay it.** Action: `gradlew.bat :ui:video:jvmTest --tests "*T12VideoPipelineTest*"`
Expected: passes, and its output (build/test-results) shows "`<file>`: N pictures, NAL types [...]" with 5, 7 and 8
among them. Commit the `.t12cap` file (video and telemetry only, no secrets).
Result: ____ pictures · NAL types __________________

**1.4 T12 video in the app.** Action: start the desktop app (0.4). Fly → Video (camera button, top right) →
**Skydroid T12 (USB)**.
Expected: "Connecting…", then a picture within ~3 s in the window at the bottom right. The chart button on the
window's bar shows "Live · ~15 fps · N ms".
Result: ____ fps · ____ ms · picture delay you see (wave a hand): ____

**1.5 Does the T12 carry MAVLink?** Action: Links → New profile → **Skydroid T12** → Save → Connect (video still on).
Expected, one of:
- a heartbeat ("ArduCopter · system 1…") and "KFT login: OK" within ~5 s: the T12 carries telemetry on its data
  channel;
- "Link open on Skydroid T12 (USB) · Waiting for a vehicle heartbeat" forever: it doesn't; telemetry has to use UDP
  or a radio when T12 video is on.
Result: carries MAVLink: yes / no · ____________________

**1.6 Port conflict (Pass 28).** Action: Links → New profile → Serial → Refresh.
Expected: the T12's COM port reads "… · in use by Skydroid T12" and can't be picked. Then Fly → Video → Off, Links →
Refresh: the port can be picked again.
Result: ______________________________________________

**1.7 Replug.** Action: video on again; unplug the T12 for 10 s, plug it back in.
Expected: "Video lost…", "Reconnecting… No Skydroid T12 on USB…", then the picture returns by itself (the COM number
may change).
Result: ______________________________________________

---

## 2. Skydroid T12 on the tablet

**2.1** Action: T12 on the tablet through USB-OTG. Fly → Video → Skydroid T12 (USB).
Expected: Android asks "Allow KFT Survey (dev) to access CP2102…" → Allow → picture within a few seconds.
Result: ____ fps · ____ ms

**2.2** Action: tap the video window, then tap the small map; drag the window's bar; drag its corner; Fly → Links →
Fly three times while the video is big.
Expected: video and map swap places; move and resize work; no crash; the map still pans afterwards.
Result: ______________________________________________

**2.3** Action: unplug the T12 for 10 s, plug it back in.
Expected: lost → reconnecting → picture again, without touching the app (Android may ask Allow again).
Result: ______________________________________________

**2.4** Action: if 1.5 said "yes": Links → Skydroid T12 → Connect on the tablet too.
Expected: the same heartbeat and "KFT login: OK".
Result: ______________________________________________

---

## 3. SIYI camera over RTSP

Set-up: SIYI camera on the MK15 air unit; laptop and tablet on the air unit's network (camera at 192.168.144.25).

**3.1** Action (desktop): Fly → Video → **SIYI (RTSP 192.168.144.25:8554)**.
Expected: picture within ~3 s; the chart button shows fps near the camera's setting and latency under ~50 ms.
Result: ____ fps · ____ ms · picture delay you see: ____

**3.2** Action: unplug the network (or switch the air unit off) for 10 s, then back.
Expected: "Video lost…", "Reconnecting…", picture back on its own.
Result: ______________________________________________

**3.3** Action: tablet, same profile; swap, drag, resize, reconnect as in 2.2–2.3.
Expected: as on the desktop.
Result: ______________________________________________

**3.4** Action: set the camera to H.265 (SIYI app or web page). Desktop, then tablet.
Expected: the desktop still plays it. The tablet says "The camera sends H.265, which the tablet can't play over
RTSP: set it to H.264". Set the camera back to H.264.
Result: desktop ____ · tablet ____

---

## 4. KFT login and parameters (desktop, FC on USB)

**4.1** Action: Links → Serial → the FC's COM port, 115200 → Save → Connect.
Expected: "KFT login: in progress…" → **"KFT login: OK"** → msg/s > 0. The Fly message strip shows "KFT:
KFT_GCS_Android authenticated".
Result: ______________________________________________

**4.2** Action: unplug the USB for more than 6 s, plug it back in.
Expected: "Reconnecting…", then "KFT login: in progress…", then "OK".
Result: ______________________________________________

**4.3 Golden vector** (Pass 12). Action: record one login from the Android GCS: the challenge (KFTCH1 + KFTCH2,
64 hex characters) and the six floats as raw bits (`Integer.toHexString(Float.floatToRawIntBits(f))`). Paste them
into `KftAndroidReferenceTest.goldenVectorFromTheAndroidApp` (`challengeHex`, `expectedBitsHex`), delete `@Ignore`,
run `gradlew.bat :core:vehicle:jvmTest --tests "*KftAndroidReferenceTest*"`. Remove the logging from the Android app.
Expected: 2 tests passed. Commit the test (never `local.properties`).
Result: ______________________________________________

**4.4** Action: Params → **Download**, then **Save file…** → `hw1-before.param` (your backup).
Expected: "N parameters · 0 modified" (about 1300–1450 on Copter), and a "Descriptions: …" line.
Result: N = ______

**4.5** Action: `SERIAL1_BAUD` → Set without changing it. Then `LOG_BITMASK` → a new value → Set → Confirm → Download
again → set it back.
Expected: "That's the current value" and nothing sent; then "LOG_BITMASK set to …", bold and **modified**, still
there after Download; back to the old value.
Result: ______________________________________________

**4.6 Locked parameter.** Action: change a parameter you know the KFT firmware locks.
Expected: one try, then "Locked or rejected by the vehicle: it kept …" in red; value unchanged, no retries. If it
says "No answer from the vehicle after 3 tries" instead, the lock is silent: note which parameter.
Result: parameter ________ · message ____________________

**4.7** Action: arm on the RC (props off), then click a parameter. Disarm.
Expected: rows can't be edited; "Disarm to change parameters".
Result: ______________________________________________

**4.8 (optional) KFT parameter descriptions** (Pass 20). Action: in the ardupilotKFT checkout at the aircraft's tag,
`python3 Tools/autotest/param_metadata/param_parse.py --vehicle ArduCopter --format json`; Params → "Import parameter
descriptions (apm.pdef.json / .xml)" → `apm.pdef.json`. Search a `KFT_` parameter.
Expected: the KFT_ row has a display name and description.
Result: ______________________________________________

---

## 5. Telemetry radio and Bluetooth

**5.1** Action: air radio on TELEM1 (`SERIAL1_BAUD 57`), ground radio on the laptop. Links → Serial → Refresh → its
COM port → 57600 → Save → Connect.
Expected: "ArduCopter · system 1 · disarmed", loss < 5 %, "KFT login: OK".
Result: loss ____ %

**5.2** Action: unplug the ground radio for 10 s, then back.
Expected: reconnects and logs in again, no restart.
Result: ______________________________________________

**5.3** Action: tablet: ground radio through OTG. Links → Serial → Refresh → the radio → 57600 → Save → Connect.
Expected: "Waiting for USB permission: tap Allow" → Allow → connected within 5 s, "KFT login: OK". Unplug/replug:
reconnects.
Result: ______________________________________________

**5.4 (optional) Bluetooth on the tablet** (Pass 26). Action: pair the HC-05/HC-06 in Android settings (PIN 1234 or
0000; its UART must match the FC port's SERIALn_BAUD). Links → Serial → Refresh.
Expected: the "Nearby devices" prompt (Android 12+) → Allow → Refresh → "bt:… · Bluetooth · HC-05" with a note
instead of baud rates. Save → Connect: heartbeat within a few seconds. Module off: reconnecting; on: reconnects.
Result: ______________________________________________

**5.5 (optional) Bluetooth on Windows.** Action: pair the module in Windows; note the **Outgoing** COM port
(Settings → Bluetooth & devices → Devices → More Bluetooth settings → COM Ports). Links → Serial → that port → Connect.
Expected: the same heartbeat. (The Incoming port just fails.)
Result: COM____ · ______________________________________

---

## 6. Missions (desktop, FC with a GPS fix; then 6.1–6.3 on the tablet)

**6.1** Action: Plan → + Survey → 4 corners near the bench → Upload.
Expected: the preview starts with "0 Home (the vehicle's own, sent as seq 0)"; "Uploading k / N…" → "Uploaded N
items…" → **On vehicle** (green). With no fix: "Home isn't known yet", and the upload fails with that message.
Result: ______________________________________________

**6.2** Action: Plan → Read.
Expected: a "From vehicle" group with the same items; still **On vehicle**.
Result: ______________________________________________

**6.3** Action: undo back to the survey, drag one corner.
Expected: Plan **Not uploaded** (orange); Fly **Vehicle mission ≠ plan**.
Result: ______________________________________________

**6.4** Action: upload again; then write a different mission from Mission Planner on a second link.
Expected: Plan **Vehicle mission changed elsewhere**; Fly **Vehicle mission ≠ plan**.
Result: ______________________________________________

**6.5** Action: Plan → Clear → confirm.
Expected: "Mission cleared on the vehicle."; Read returns 0 items.
Result: ______________________________________________

**6.6** Action (props off): arm on the RC; Plan → Upload. Cancel, disarm. Skip if pre-arm checks refuse to arm.
Expected: the first warning is "Vehicle is ARMED in <MODE>: uploading replaces the mission it is flying."
Result: ______________________________________________

---

## 7. Camera trigger → CAMERA_FEEDBACK

Set with Params (or Load file…), then **power-cycle** the FC (the camera driver reads `CAM1_TYPE` at boot) and
Download again to check the values stuck.

**7.1** Action: `RC7_OPTION 9` on your spare switch's channel (7 here).
Expected: set.
Result: ______________________________________________

**7.2 Servo variant.** Action: `CAM1_TYPE 1`, power-cycle, Download; `SERVO9_FUNCTION 10`, `CAM1_SERVO_ON 1900`,
`CAM1_SERVO_OFF 1100`, `CAM1_DURATION 0.1`, power-cycle. Fly → Clear track. Flip the switch high/low 5 times.
Expected: the output pulses each time; Fly shows **Photos 5** and 5 green dots at the vehicle (dots only with a fix).
Result: ______________________________________________

**7.3 Relay variant.** Action: `CAM1_TYPE 2`, `RELAY1_FUNCTION 4`, `RELAY1_PIN` = the wired output (50–55 AUXOUT1–6,
101–108 MainOut1–8), `CAM1_DURATION 0.1`, power-cycle; repeat 7.2's flips.
Expected: the relay clicks; same count and dots.
Result: ______________________________________________

**7.4** Action: upload a small survey, Plan → Read.
Expected: items include "Camera: a photo every … m" and "Camera: stop". (Firing in a mission needs a flight.)
Result: ______________________________________________

---

## 8. Offline maps (Pass 23)

**8.1** Action (desktop): Maps → zoom to a small area near you → Download → wait for "Done". Maps → Clear browsing
cache. Close the app, **Wi-Fi off**, start the app, go to that area.
Expected: the street map draws inside the downloaded area at z10–16; blank elsewhere.
Result: ______________________________________________

**8.2** Action (tablet): the same with **airplane mode**.
Expected: the same.
Result: ______________________________________________

**8.3** Action (tablet): Maps → **Import MBTiles…** → pick a raster `.mbtiles` through Android's file picker → choose it as
the basemap on Fly.
Expected: listed with its size; its tiles draw.
Result: ______________________________________________

---

## 9. Tablet-only checks

**9.1** Action: long-press a map button on Fly and a toolbar button on Plan.
Expected: its tooltip appears (desktop shows them on hover).
Result: ______________________________________________

**9.2** Action: Plan → **Import a file** → a shapefile **zipped** with its .shp/.shx/.dbf/.prj (Android's picker gives one file).
Expected: "It holds N area(s)…" → Survey areas → the area on the map. A bare .shp gives "coordinates aren't
latitude/longitude" (a known gap).
Result: ______________________________________________

---

## 10. Wrap-up

**10.1** Params → Save file… → `hw1-after.param`; keep it with `hw1-before.param`.
Result: ______________________________________________

**10.2** Note the FC board, firmware version (under the Fly HUD title), radio model, T12 firmware, SIYI camera model:
________________________________________________________________

**10.3** Send me: the T12 capture (or commit it), the answers to 1.1, 1.2, 1.5 and 4.6, and every Result line that
didn't match.

**Not in this session (needs flying):** tuning `NAVL1_PERIOD` on the real airframe; Plane crosshatch/corridor runs;
the survey's camera commands firing in flight.

---

## What to capture on failure

Write the step number, the time (clock on the laptop), and what you saw. Then as many of these as apply:

1. **App log.**
   - Desktop: the `hw1-desktop.log` file from 0.4 (keep the app running from that command all session).
   - Tablet: with the tablet on USB, `adb logcat -d > hw1-tablet.txt` right after the failure (the log only goes back
     a few minutes).
2. **Screenshot** of the whole screen: desktop Win+Shift+S, tablet power + volume-down.
3. **Telemetry log.** The GCS doesn't record a tlog yet (a gap: worth its own pass). Instead, note the time, and
   after the session copy the FC's dataflash log (`.BIN`, SD card or MAVFTP) for that time.
4. **T12 capture** for anything T12-related: close the GCS and run
   `powershell -ExecutionPolicy Bypass -File tools\t12\capture\capture.ps1 -Seconds 20` with the same set-up.
5. **Parameters** when a vehicle-side setting may be involved: Params → Save file….

| Step | Time | What you saw | Files |
|---|---|---|---|
| | | | |
| | | | |
| | | | |
| | | | |
