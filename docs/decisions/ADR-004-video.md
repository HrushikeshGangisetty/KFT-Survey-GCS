# ADR-004 — Video: RTSP (SIYI) and Skydroid T12 serial video

**Status:** Accepted, with two open items (T12 MAVLink multiplexing, T12 protocol provenance)
**Date:** 2026-09-28 · **Pass:** 27 · **Spec:** feature spec "Video pane (RTSP)" (was P2, OD-13)

## Context

KFT flies two video systems:
- **SIYI camera behind an MK15 air unit:** RTSP at `rtsp://192.168.144.25:8554/main.264`.
- **Skydroid T12 controller:** no network video. Its single USB port (a Silicon Labs CP2102, VID 0x10C4 / PID 0xEA60)
  carries H.264 inside a polled vendor framing, on the same port that MAVLink uses.

The old KFT Android GCS (`AndroidStudioProjects/kftgcs`, package `videotracking`) plays both. This ADR records what
was taken from it, what changed, and why.

## Step 0: the T12 framing, and whether MAVLink can share it

From the old app (`T12SerialVideoSource.kt`, `VideoSource.kt`, `UsbPortOwnership.kt`,
`UsbSerialMavConnection.kt`):

| Direction | Type | Meaning |
|---|---|---|
| host → T12 | `0xA5` | AT command text (start/stop video, exposure); an empty one is the keep-alive poll |
| host → T12 | `0xA3` | data |
| host → T12 | `0xA7` | GPS |
| host → T12 | `0xA1` | debug |
| T12 → host | `0xA5` | H.264 Annex-B video bytes |
| T12 → host | `0xA3` | data |
| T12 → host | `0xA7` | GPS |
| T12 → host | `0xA1` | debug |

Poll: `FF 02 00 <ack> <len> <type> <payload≤250>`, ack `0x55` after a good read, `0x00` after a bad one. Answer: one
512-byte frame `FF <lenHi> <lenLo> <type> <payload[508]>`. 4 Mbaud 8N1, DTR and RTS raised. Packets ≥ 30 ms apart,
AT commands ≥ 180 ms apart, a 20 ms slot when the T12 doesn't answer.

**Can MAVLink travel inside the same polled link? Unknown.** What the old code tells us:
- `0xA3` "data" runs both ways, and the old app counted `0xA3` frames but never looked inside them.
- The old app's telemetry opened the same CP2102 **unpolled**, as a plain serial port at the operator's baud rate
  (115200 by default), and heartbeats arrived (confirmed on hardware 2026-09-22, per its KDoc). So in its idle mode
  the T12 passes raw MAVLink straight through.
- It never ran both at once: `UsbPortOwnership` refused video while telemetry held the port, and vice versa.

Nobody has checked what happens to that MAVLink once the host starts polling: it may move into `0xA3` frames, or it
may stop. **Design: `T12Link` carries both.** It is the only owner of the port; video subscribes to its `0xA5` bytes,
and a MAVLink link over the T12 (`LinkConfig.T12`, "Skydroid T12" on the Links screen) is a `MavTransport` over its
`0xA3` channel. **Fallback: exclusive use.** If the hardware session shows `0xA3` carries no MAVLink, a T12 link just
never hears a heartbeat, and telemetry goes over another link (UDP, a radio) while video keeps the T12. Nothing else
changes. The T12 capture (`tools/t12/capture`, recorded with the air unit sending telemetry) answers this: its replay
test prints the frame types seen.

## Provenance of the T12 protocol (open item)

- **What we used:** only the old KFT app's own source and comments: the byte layouts, type values, timings, and the
  start/stop AT command strings. The code in `T12Protocol.kt` and `T12Link.kt` was written fresh for this project;
  no Skydroid code was copied, and Skydroid's `uartVideo` .aar was never opened, decompiled or copied in this pass.
- **But:** the old app's KDoc says its author worked the protocol out *by disassembling Skydroid's
  `uartVideo-release.aar`* (package `com.shenyaocn.android.UartVideo`, bundled in the GitHub project
  `Vigiair/Skydroid-USB-Video-to-UDP`). So these facts reached us through a decompilation, one step removed.
- **Why we went ahead:** what we carry over is interface facts needed to talk to hardware KFT owns (frame layout,
  constants, command text), not Skydroid's code or its structure, which is the usual line for interoperability.
- **To close it:** KFT decides whether that's acceptable. Better still, ask Skydroid for the T12 serial video protocol
  (or a licence for their SDK), and check the licence of the `Vigiair` project. Until then, don't publish this code
  outside KFT. If the answer is no, `T12Protocol.kt`, `T12Link.kt`, `LinkConfig.T12` and the T12 video profile are
  the whole footprint to remove; RTSP is unaffected.

## Decisions

1. **Modules.**
   - `core:mavlink`: `T12Protocol.kt` (pure framing, `commonMain`) and `T12Link` (the port owner, `jvmCommonMain`,
     `expect`/`actual` so `ConnectionManager` can name it). It lives here because it opens a serial port and carries
     MAVLink: `SerialLink` and `MavTransport` are `internal`, so nothing outside can write to a port (CLAUDE.md §4).
   - `ui:video` (new): `VideoView` (status and reconnect, shared), the two source kinds, Annex-B splitting, and the
     platform players. It depends on Compose and `ui:design` only, not on `core:mavlink`: the T12's bytes arrive as a
     plain `Flow<ByteArray>`.
   - `feature:fly`: video profiles and `VideoViewModel`. `app:shared` composes the window, because it owns the map.
2. **Two source kinds behind one type.** `PlayerSource(url)`: a player opens it itself (RTSP). `ElementaryStreamSource`:
   our code produces H.264 bytes, which are split into NAL units (`AnnexBSplitter`: 3- and 4-byte start codes, split
   anywhere across chunks), held back until SPS, PPS and a keyframe (`NalGate`), and queued (`NalQueue`, 64 units;
   on overflow the backlog is dropped and decoding restarts at the next keyframe).
3. **Android: Media3 for RTSP, MediaCodec for the T12.** As the old app: `RtspMediaSource` with
   `setForceUseRtpTcp(true)`, no custom `SocketFactory`, near-zero buffering, and a 1.1× catch-up instead of seeking
   (a seek makes Media3 send PAUSE/PLAY and restarts the camera's stream). **H.264 only:** Media3's RTSP reader can't
   unpack H.265 aggregation packets, so the error says "set the camera to H.264". MediaCodec gets `KEY_LOW_LATENCY`
   (Android 11+), with input and output in two loops.
   - **Deviation from the brief:** a **TextureView** (`AndroidEmbeddedExternalSurface`), not a SurfaceView. A
     SurfaceView is a separate layer behind the window, so it doesn't follow Compose's z-order, which the window and
     the swap with the map depend on; the old app found ExoPlayer's picture vanishing under overlays with one. Cost:
     about one frame of latency. If that matters, `AndroidExternalSurface` (a SurfaceView) is a one-line change for
     the full-screen case.
4. **Desktop: FFmpeg through bytedeco's presets (`org.bytedeco:ffmpeg`), not JavaCV, GStreamer or vlcj.**
   - One decoder for both sources: RTSP is demuxed by `avformat` (RTP over TCP, `fflags nobuffer`, `low_delay`,
     5 s timeout, an interrupt callback so closing the view ends a blocked read); the T12's NAL units go straight to
     `avcodec` with `AV_CODEC_FLAG2_CHUNKS`. Desktop also plays H.265 over RTSP.
   - Not JavaCV: its `FFmpegFrameGrabber` would only save a few lines, and it pulls in a dozen other presets.
     Not GStreamer: a separate runtime install on every laptop. Not vlcj: GPL.
   - Pictures are converted to BGRA and drawn by Compose, so the video is an ordinary composable (z-order, swap).
5. **Licences.**
   - Media3: Apache-2.0.
   - JavaCPP and the presets: Apache-2.0 (or GPLv2 with Classpath exception); used under Apache-2.0.
   - FFmpeg: bytedeco's plain `ffmpeg` artifact is built `--enable-version3` **without** `--enable-gpl`, so it is
     **LGPLv3** (no x264/x265; H.264 decoding is FFmpeg's own). Never add `ffmpeg-gpl` / `ffmpeg-platform-gpl`.
     LGPL terms we meet: FFmpeg is dynamically linked (separate DLLs the user can replace), the notice and licence
     texts ship in `NOTICE` and `licenses/`, and the source is FFmpeg 8.1.2 plus bytedeco's build script, both
     public. **Still to settle before any release outside KFT:** how the installer offers that source (a written
     offer, or the source archive next to the installer; LGPLv3 §4 via GPLv3 §6). Bundled libraries in that build (OpenH264 BSD-2, OpenSSL Apache-2.0, libvpx
     BSD-3, and others) are listed in the jar's own licence files.
   - The test-only dev tools (MediaMTX MIT, FFmpeg CLI builds that may be GPL) are never shipped.
   - Size: the Windows natives add about 70 MB (unpacked DLLs) to the desktop app. Android is unaffected (FFmpeg is `jvmMain` only).
6. **Reconnect.** `VideoView` owns it on both platforms: a failure, 3 s without a picture, or 10 s of connecting
   makes it "lost", then it reopens after 1, 2, then 3 s. RTSP reconnects by URL. The T12 is found again by USB IDs
   and the serial number of the device that last answered (Android renames the device on every replug). Status:
   connecting (with the reason, e.g. "No Skydroid T12 on USB"), live, lost; the statistics button adds fps and
   latency (arrival in the app → decoded on desktop and for the T12 on Android; for RTSP on Android, how far playback
   is behind what has arrived).
7. **Picture-in-picture, and the map stays composed (ADR-001 F10).** `App()` keeps the one `MapView` where it is and
   only changes its modifier (full size ↔ the window's bounds) and `zIndex`; the video does the same. Tap the window
   to swap, drag its bar to move it, drag the corner to resize it, ✕ to hide it (choosing the source again shows
   it). Verified on the desktop: swap, swap back, drag, resize, and Fly↔Links three times with the map small, with
   no crash.
8. **Profiles.** Two fixed presets ("SIYI (RTSP 192.168.144.25:8554)", "Skydroid T12 (USB)") plus the operator's
   own RTSP URLs, saved in `video-profiles.json` next to the connection profiles, with the last choice.

## Consequences

- The T12's poll frames and AT commands are written by `T12Link`, not `MavTxGateway`: they are link-layer framing,
  like a UDP header. Every MAVLink byte inside them still comes from the gateway, through the T12 transport.
- A plain **Serial** profile and the T12 link can't hold the T12's port at the same time: both claim it in
  `PortClaims` first, and the second is refused with "COM9 is in use by Skydroid T12" (or "by a Serial link"); the
  Links screen shows the port as in use (Pass 28). Use the "Skydroid T12" link type for MAVLink over the T12.
