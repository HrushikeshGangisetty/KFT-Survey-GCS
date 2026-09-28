# T12 capture

Records the Skydroid T12's raw serial traffic so the video path can be tested again and again without the hardware.

```
powershell -ExecutionPolicy Bypass -File tools\t12\capture\capture.ps1 -Seconds 20
```

- Runs `gradlew :core:mavlink:t12Capture`, which uses the app's own `T12Link`: same port search (USB IDs 10C4:EA60),
  same 4 Mbaud 8N1 with DTR and RTS, same polls and start-video command. It prints the video bytes received each
  second, and the reason if there are none ("No Skydroid T12 on USB", "answers but sends no video").
- Close the GCS first: only one program can hold the T12's port.
- The file goes to `tools/t12/captures/`. `T12VideoPipelineTest.recordedT12CapturesDecode` replays every `.t12cap`
  there on each `gradlew check`, and fails if a capture has no SPS, PPS and keyframe or decodes to no pictures.
- File format (`T12CaptureFile` in `core:mavlink`): the 8 bytes `T12CAP1\n`, then per poll a 2-byte big-endian length
  and the bytes the T12 answered (0 = no answer in the 20 ms slot, 512 = a good frame).
- A capture also answers ADR-004's Step 0 question: if it holds 0xA3 (data) frames while the air unit is sending
  telemetry, the T12 carries MAVLink on its data channel. `recordedT12CapturesDecode` prints the frame types seen.
