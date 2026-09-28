# Release checklist

Before any build of the GCS leaves KFT (a customer, a partner, a public download). Internal test builds for KFT staff
don't need it, except the items marked **every build**. Each item: what's open, where it's recorded, and how to close
it. Tick it and add the date and who decided.

I'm not a lawyer: the licence and patent items record what the texts say and what the app does. KFT's legal contact
decides.

## Licences and permissions

- [ ] **GS-1: map tiles** (`docs/decisions/GS-1-offline-tiles.md`).
  - OpenFreeMap: written permission for operator-started area downloads (email info@openfreemap.org). If refused:
    host or import their MBTiles instead and point the Street style at it.
  - Esri satellite: stays online-only (no downloads), and the Esri key is never packaged (`local.properties` /
    `ESRI_API_KEY` at run time only). Check the key's own terms for commercial use.
  - Imported MBTiles: the operator's own data and licence; the Maps tab says so.
  - Closed: ____ (date, who)

- [ ] **Skydroid T12 protocol permission** (ADR-004, "Provenance"). The T12 framing came to us through the old KFT app,
  whose author worked it out by disassembling Skydroid's `uartVideo` .aar. Get KFT's decision, or better Skydroid's
  written permission or their protocol document. If the answer is no, remove `T12Protocol.kt`, `T12Link.kt`,
  `LinkConfig.T12`, the T12 video preset and the capture tool (ADR-004 lists them); RTSP is unaffected.
  - Closed: ____

- [ ] **FFmpeg is the LGPL build, not GPL** (**every build**). `FfmpegBuildTest` (in `gradlew check`) asks the
  library itself: licence "LGPL version 3 or later", no `--enable-gpl`, no `--enable-nonfree`. Also check the
  dependency is `org.bytedeco:ffmpeg`, never `ffmpeg-gpl` / `ffmpeg-platform-gpl`
  (`gradlew.bat :app:desktop:dependencies | findstr /i ffmpeg`).
  - Checked for this build: ____

- [ ] **FFmpeg LGPL notice and source.** `NOTICE` names FFmpeg 8.1.2, the build script and the licence;
  `licenses/LGPL-3.0.txt` and `GPL-3.0.txt` are in the repository. Still to do:
  - [ ] Ship `NOTICE` and `licenses/` inside the installer (Compose Desktop's `appResourcesRootDir`, or next to the
    MSI), and show them from About.
  - [ ] Decide how the source is offered (LGPLv3 §4 via GPLv3 §6): the FFmpeg source tarball and bytedeco's build
    script placed next to the installer download, or a written offer valid 3 years. Put the link or offer in
    `NOTICE`.
  - [ ] Keep FFmpeg replaceable: it must stay in its own DLLs (it is: bytedeco extracts them), and no licence term
    may forbid reverse-engineering the app for debugging those changes (LGPLv3 §4).
  - Closed: ____

- [ ] **No OpenH264 in the shipped app.** The current bytedeco FFmpeg build is configured `--enable-libopenh264`,
  so Cisco's OpenH264 is compiled into the DLLs we ship (checked 2026-09-28 with `avcodec_configuration()`). Cisco's
  patent licence covers only binaries downloaded from Cisco, not ones built from source, so shipping this build
  carries OpenH264's H.264 patent exposure without Cisco's cover. We only need FFmpeg's own decoder.
  - Options: a custom FFmpeg build without OpenH264 (and without the other encoders we don't use), repackaged as the
    natives jar; or a different decoder on Windows (Media Foundation, whose H.264/H.265 decoders are licensed with
    Windows).
  - Note: `T12VideoPipelineTest` and `RtspPlaybackTest` encode their test pattern with OpenH264. Without it, replace
    that with a small checked-in `.h264` fixture (test code only).
  - Closed: ____

- [ ] **H.264 / H.265 decoder patents.** H.264 (Via LA's AVC pool) and H.265 (Access Advance, Via LA) are patented,
  and distributing a software decoder can need a licence, depending on country and volume.
  - Android: the app uses the device's MediaCodec decoders, licensed by the device maker. Nothing to do there.
  - Desktop: FFmpeg's own software decoders ship with the app. Decide: accept (typical for small volumes and
    free-of-charge tools, but KFT's call), license through the pools, or decode with Windows' own Media Foundation
    decoders instead.
  - Closed: ____

- [ ] **Media3, JavaCPP, Material Symbols and the other Apache-2.0 parts:** `NOTICE` lists them. Ship `NOTICE` and
  `licenses/Apache-2.0.txt` in the installer (same step as the FFmpeg notice).

## Keys and security

- [ ] **GS-9: the KFT login key** (spec §GS-9, ADR-003). One fleet key, compiled into every APK and desktop build,
  extractable from either; one extraction unlocks every drone. Before wider release: one key per drone (firmware
  side), and the three firmware findings (committed app keys in `KFT_GCSAuth.h`; one login unlocks every link; any
  heartbeat keeps it unlocked).
  - Closed: ____
- [ ] **Every build:** `local.properties` (with `KFT_APP_SECRET`, the Esri key) is not in git and not in the package.
  Check `git status` and search the installer for the key text.

## Build

- [ ] **Every build:** `gradlew.bat check` green; `hardware-session-1.md` run on this version's hardware set-up, or
  the differences since the last run listed.
- [ ] Version set in `app/desktop/build.gradle.kts` (`packageVersion`) and `app/android` (`versionName`,
  `versionCode`); tag the commit.
- [ ] Android release build signed with KFT's release key (never committed); `applicationId` `com.kft.survey`
  (not `.dev`).
- [ ] Desktop: `gradlew.bat :app:desktop:packageMsi` on Windows x64 (the only desktop target with map and FFmpeg
  natives today).
