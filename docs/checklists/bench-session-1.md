# Bench session 1: real flight controller, radio and camera trigger

For Hrushikesh to run on the bench, **props off**. Each step has what to do and what you should see. Tick the box when
it matches. If it doesn't, note what you saw next to it and stop that section. Everything here was checked in SITL
(Passes 10–16) except the parts that need real hardware.

**Safety:** props off for the whole session. The GCS never arms, changes mode or takes off (spec S9). Nothing in this
list asks it to. The trigger tests use the RC switch or the mission, not a GCS button.

## 0. What you need
- [ ] A KFT flight controller (ardupilotKFT firmware) on USB, with GPS if you have one (home and positions).
- [ ] A telemetry radio pair (SiK or similar, 57600 baud) plus its USB lead. Also a USB-OTG adapter for the tablet.
- [ ] Your RC transmitter bound to the FC, with a spare 2-position switch.
- [ ] Optional: a camera, or just an LED or servo tester on the trigger output, so you can see the pulse.
- [ ] Mission Planner (or KFT Configurator) for setting parameters. The GCS has no parameter editor yet.
- [ ] This repo at the latest commit, and `gradlew.bat check` green.

## 1. KFT login (spec S12)
1. [ ] In `local.properties` (git-ignored), add `KFT_APP_SECRET=<the Android GCS's 64 hex characters>`.
   Check with `git status`: `local.properties` must not appear as a change to commit.
2. [ ] `gradlew.bat :app:desktop:run` → Links → add **Serial**, the FC's COM port, 115200 → Save → Connect.
   - Expected on the Links card: "KFT login: in progress…", then **"KFT login: OK"**, then telemetry (msg/s > 0).
   - Expected in the Fly message strip: ArduPilot's own "KFT: KFT_GCS_Android authenticated".
3. [ ] Unplug the USB cable for more than 6 s, then plug it back in.
   - Expected: "Reconnecting…", then "KFT login: in progress…", then "OK" again.
4. [ ] **Golden vector** (makes the NaN-safe float packing a real-device test, Pass 12):
   1. [ ] Record one login from the Android GCS: the challenge (KFTCH1 + KFTCH2 text, 64 hex characters) and the six
      floats it sent as raw bits (`Integer.toHexString(Float.floatToRawIntBits(f))`, 8 hex digits each). Remove the
      logging from the Android app again afterwards.
   2. [ ] Paste them into `core/vehicle/src/jvmTest/kotlin/com/kft/gcs/core/vehicle/KftAndroidReferenceTest.kt`,
      `goldenVectorFromTheAndroidApp`: `challengeHex` and `expectedBitsHex`. Delete the `@Ignore` line.
   3. [ ] `gradlew.bat :core:vehicle:jvmTest --tests "*KftAndroidReferenceTest*"`
      - Expected: 2 tests, both passed. The golden one runs because your key is configured (on CI it returns early).
   4. [ ] Commit the test change (a challenge/response pair doesn't reveal the key). Never commit `local.properties`.

## 2. Telemetry radio: desktop serial
1. [ ] Air radio on the FC's TELEM1 (SERIAL1, 57600 baud: `SERIAL1_BAUD 57`). Ground radio on the laptop's USB.
2. [ ] Links → Serial → **Refresh** → pick the radio's COM port → 57600 → Save → Connect.
   - Expected: "ArduCopter · system 1 · disarmed" (or ArduPlane), loss < 5 % on the bench, "KFT login: OK".
3. [ ] Unplug the ground radio for 10 s, then plug it back in.
   - Expected: "Reconnecting…", then connected and logged in again, with no app restart.

## 3. Telemetry radio: tablet USB-OTG
1. [ ] Install: `gradlew.bat :app:android:installDebug` with the tablet on USB (app "KFT Survey (dev)").
2. [ ] Ground radio on the tablet through the OTG adapter. Links → Serial → Refresh → the radio appears (FTDI /
   CP210x / CH34x / CDC) → 57600 → Save → Connect.
   - Expected: "Waiting for USB permission: tap Allow". Tap **Allow**, and within 5 s it's connected with "KFT login: OK".
   - Note: the tablet build needs the same `KFT_APP_SECRET` at build time (it's compiled in, Pass 12).
3. [ ] Unplug the radio, then plug it back in. Expected: "Reconnecting…", then connected again.

## 4. Missions (desktop, then repeat 4.1–4.3 on the tablet)
1. [ ] **Upload:** Plan → + Survey → click 4 corners near the bench's GPS position → Upload.
   - Expected: the preview reads "Upload N items?" with "0 Home (the vehicle's own, sent as seq 0)" first. Upload →
     "Uploading k / N…" → "Uploaded N items…" → the toolbar shows **"On vehicle"** (green).
   - If the FC has no GPS fix: the preview warns "Home isn't known yet", and the upload fails with that message.
     That's correct behaviour. Get a fix (outdoors or by a window), then retry.
2. [ ] **Read:** Plan → Read.
   - Expected: a "From vehicle" group with the same items (the speed folded into a waypoint row), still **"On vehicle"**.
3. [ ] **Plan ≠ vehicle:** undo back to the survey (Ctrl+Z, or the Undo button), then drag one corner.
   - Expected: Plan shows **"Not uploaded"** (orange), and Fly shows **"Vehicle mission ≠ plan"** under the HUD title.
4. [ ] **Changed elsewhere:** upload again ("On vehicle"). Then, from Mission Planner on a second link (for example USB
   while the GCS uses the radio), write a different mission with another item count.
   - Expected: after the next MISSION_CURRENT, Plan shows **"Vehicle mission changed elsewhere"** and Fly shows
     "Vehicle mission ≠ plan".
5. [ ] **Clear:** Plan → Clear → confirm.
   - Expected: "Mission cleared on the vehicle.", the editor has an empty Waypoints group, and Read returns 0 items.
6. [ ] **Armed warning (props off!):** arm on the RC. On a bench the pre-arm checks may refuse (no GPS, no compass
   calibration). If so, skip this step: SITL covered it in Pass 11. Otherwise, Plan → Upload.
   - Expected: the preview's first warning is "Vehicle is ARMED in <MODE>: uploading replaces the mission it is
     flying." Cancel, then disarm.

## 5. Camera trigger → CAMERA_FEEDBACK in the GCS
Parameters are set in Mission Planner (or KFT Configurator), then the FC is **rebooted**: the camera driver only reads
`CAM1_TYPE` at boot. Checked against ArduPilot master 2026-09: AP_Camera_Params.cpp, AP_Relay_Params.cpp,
RC_Channel.cpp and SRV_Channel.h.

**The RC switch**, used by both variants:
1. [ ] `RC7_OPTION 9` (Camera Trigger) on your spare switch's channel (7 here; use yours). Switch high means take one
   photo (`RC_Channel::do_aux_function_camera_trigger` → `take_picture`).

**Variant A: servo trigger** (a camera with a PWM shutter input, or a servo tester or LED to see the pulse):
2. [ ] `CAM1_TYPE 1` (Servo). `SERVO9_FUNCTION 10` (CameraTrigger), on an output with a free connector (AUX1 on a
   board with an IOMCU). `CAM1_SERVO_ON 1900`, `CAM1_SERVO_OFF 1100`, `CAM1_DURATION 0.1`. Reboot.
3. [ ] GCS connected, on Fly → Clear track.
4. [ ] Flip the switch high, then low. Do it 5 times.
   - Expected: the output pulses (the LED blinks or the servo twitches), the Fly HUD shows **"Photos 5"**, and 5 green dots
     appear on the map at the vehicle's position. With no GPS fix, the count still rises but dots appear only once
     the FC has a position (CAMERA_FEEDBACK with lat/lon 0,0 is ignored).

**Variant B: relay trigger** (a camera with a contact-closure shutter input):
5. [ ] `CAM1_TYPE 2` (Relay). `RELAY1_FUNCTION 4` (Camera). `RELAY1_PIN` = the output wired to the camera
   (50–55 = AUXOUT1–6, 101–108 = MainOut1–8). `CAM1_DURATION 0.1`. Reboot.
6. [ ] Repeat steps 3–4. Expected: the relay clicks (or the camera fires), plus the same count and dots as variant A.

**In a mission** (props off, or only after the bench items pass):
7. [ ] With variant A or B set up, upload a small survey. Put the pilot's RC in AUTO while disarmed: nothing moves
   and nothing fires. The survey's camera commands only run as the vehicle reaches the waypoints in flight. A bench
   check of the mission trigger therefore waits for a field test. For today, confirm in Read that the survey items
   include "Camera: a photo every … m" and "Camera: stop".

## 6. Wrap-up
- [ ] Note the FC board, firmware version (under the Fly HUD title) and radio model here: ______________________
- [ ] Anything that didn't match: which step, and what you saw instead.
- [ ] Send me the golden vector (section 1.4) if you didn't commit it yourself.
