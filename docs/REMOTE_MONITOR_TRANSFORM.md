# Remote monitor display transforms

The opt-in patch adds a preview-only transform to the selected remote camera in Blackmagic Camera. Static inspection and APK patching cover **3.5.2.0025 (26111001), arm64-v8a**. The Galaxy S23 Ultra runs the locally signed test build. Installation and cold launch passed. See the device test results below for live validation.

## Inputs and repository audit

- Upstream [commit 792c6dd8013c8941ecd16101aa9032923d96d908](https://github.com/azain47/blackmagic-camera-patches/commit/792c6dd8013c8941ecd16101aa9032923d96d908). The checkout's shared compatibility declaration identifies **3.5.1.0040** as an upstream reference for its existing patches. The phone supplied the newer version above.
- Audited all upstream Kotlin sources and fingerprints, Gradle scripts, version catalog, wrapper and release configuration. The gallery code demonstrates matching a stable method shape and reading typed obfuscated references. The developer options patch demonstrates narrowly modifying an existing UI path. The bitrate patch targets the local encoder's bitrate formula. The bundled PairIP patch is separate. Patch discovery loads top-level bytecode patch definitions from an MPP. The new monitor definition is independent and off by default.
- ADB pulled the installed base and splits from a Galaxy S23 Ultra **SM-S918U1** running Android 16, API 36. Android Build Tools 36.1.0 verified a single RSA-4096 signer, APK Signature Scheme v3, and the source stamp on all three APKs. Signer certificate SHA-256 is `8a889ddc2cac452968c5f2c62d339dd2f7f9dab526864b48ab53ee0a3e140cd9`, matching the upstream compatibility declaration. Source-stamp certificate SHA-256 is `3257d599a49d2c961a471ca9843f59d341a405884583fc087df4237b733bbd6d`.
- JADX 1.5.7 traced the Java call chain. Apktool 3.0.3 and Android Build Tools confirmed DEX registers and references, resources, manifest and signer. The base contains three DEX files, the GLSL shaders and `res/raw/monitoring_control.yaml`. The arm64 split contains `libgstreamer_android_remote.so`, and the xxhdpi split contains density resources. The patch touches the base.

| Private device export | SHA-256 |
| --- | --- |
| `base.apk` | `3f92ebd71b9096aa3b69b803cadbf12c5ed1cd8ce89e1c295e3f5ed473373754` |
| `split_config.arm64_v8a.apk` | `80f888bf02884e89e28f5226a1fd5751342f14bb9ca66e652e35183164746dca` |
| `split_config.xxhdpi.apk` | `55fbeb9942e69261dfc9f73863031daba300972a6ee8671b0fa7b8451ce48cbb` |

## Renderer call graph

Arrows summarize the static call chain. The selected camera's UUID connects the UI surface to the receiver. Obfuscated names below are for this exact APK.

```text
Selected camera UI
  MainActivity → qfa.d (UUID-keyed camera preview)
    → gz7 / jk → MainActivity.G(context, UUID) → e80 (SurfaceView)
    → qfa / nfa holder callback → i30.J → pea.m(holder, UUID, false)
      → ej9.d → aj9 / bj9 → cj9.g(surface) → xga(mf1.C, surface, …)

  Alternate camera UI
  MainActivity → Compose camera UI → sx4.b → sx4.r(Size, q35, UUID, Composer, int)
    → AndroidView factory t7.b, case 10 → FrameLayout + e80 (SurfaceView)
    → d35.surfaceCreated → pea.m(holder, UUID, true)
      → cj9.d(surface) → yq7.run, case 10
        → replace or add xga(mf1.E, surface, …) → kb2 renderer output

Incoming preview
  libgstreamer_android_remote.so / NativeGstreamer
    → GsPipelineStreamingSink → a8a.onVideoPreroll / onVideoSample
    → ej9.f / ej9.g → cj9.h(AppSinkData) → MediaCodec AVC/HEVC decoder
      → wi9.a, case 2 → kb2.n() → decoder Surface wrapping renderer SurfaceTexture
      → zi9.onOutputBufferAvailable → MediaCodec.releaseOutputBuffer(index, render)
      → kb2.onFrameAvailable → jb2.handleMessage, case 4 → updateTexImage()
        → kb2.B / xga.d / ee9.q (stock texture matrix)
        → kb2.o → kb2.j → glDrawArrays → eglSwapBuffers on selected e80 Surface

Effects of leaving the screen
  d35.surfaceDestroyed → stock UUID-aware teardown → cj9.k / xi9.run
  d35.surfaceCreated on return → stock attach to the next Surface
```

| Evidence | Verified reference and role |
| --- | --- |
| Controller navigation and surface | The manifest names `com.blackmagicdesign.android.blackmagiccam.ui.MainActivity`. The observed controller uses `qfa.d`, `gz7` and `jk` to attach a UUID-keyed SurfaceView. An alternate Compose camera UI passes the selected UUID and `Size` into `Lsx4;->r(Landroid/util/Size;Lq35;Ljava/util/UUID;Lut4;I)V`. Factory case 10 in `Lt7;->b` constructs a `FrameLayout`, adds `Le80;`, and attaches `Ld35;` as the holder callback. Resources include `string/control_and_monitor` 0x7f1200f8 and `string/hide_remote_camera_video_feed` 0x7f1201da. |
| Remote versus local attachment | `Ld35;->surfaceCreated` calls `Lpea;->h(UUID)Z` to check whether the selected camera is local. Its remote-only branch calls `Lpea;->m(SurfaceHolder,UUID,Z)V` with `true`. The local branch attaches to `f25`. Calls through `i30.J` use `false` and attach through `ej9.d` and `aj9.run` to the main `mf1.C` renderer output. The alternate path adds or updates the external `mf1.E` target in `yq7.run` case 10 and `sh.a` case 10. |
| Transport and remote selection | `qx1` loads both GStreamer libraries and initializes `NativeGstreamer`. `a8a` implements `GsPipelineStreamingSink` and looks up the `ej9` worker by the incoming stream UUID. `ej9.f` stores preroll metadata and `ej9.g` initializes decoding on a keyframe. |
| Decoder target | `Lcj9;->h` reads the incoming codec and dimensions from `AppSinkData`, creates the Android decoder and configures it with the `Surface` from `kb2.n`. `jb2` creates that `SurfaceTexture` and `Surface` on the renderer side. The decoder output callback `zi9` releases frames for rendering. |
| Dimensions and existing orientation | `e80.a(II)` fixes the holder buffer size. `e80.onMeasure` uses its layout constraints. `kb2.e` queries the EGL surface for the actual buffer size stored in `xga.s`. The observed controller fixes the EGL output buffer at 1920×1080, then Compose can stretch it into a tall slot. The alternate controller supplies a preview aspect via `sx4.b`. `a8a.onVideoSample` derives stock orientation/flip state and `cj9.n` sends it to `kb2.B`, `xga.d` and `ee9.q`. |
| Final video geometry | `Lkb2;->j(II[FLxga;Lk90;)V` binds the texture and coordinates via `k90.a` and `k90.b`, then issues one `GLES20.glDrawArrays`. The stock `transform_vertex.glsl` derives texture UVs from the vertex position, so changing the quad positions also requires an inverse transform in its texture matrix. `kb2.o` makes that EGL output current, sets its viewport and swaps after drawing. |
| Controls and hit testing | `sx4.b` composes the preview and `r23.b` control overlay as siblings. The patch anchors a small button to the remote view in the activity window so the stock Compose overlays do not intercept its touches. It leaves the Compose layout and control bounds intact. The square-chart test confirmed that the native transform control and the separate stock Record button both respond. |
| Recreation and exclusion | `d35.surfaceDestroyed` detaches the selected surface according to the app's connection state. `cj9.j` can recreate the EGL renderer for colorimetry changes. `cj9.g` may also attach separate auxiliary outputs. The hook keys off the exact selected surface object, leaving auxiliary outputs outside the patch. The UUID-keyed controller surface factory is also used by MultiView. The tested single-feed controller and the alternate controller have scoped output bindings. Gallery and local preview have separate paths. |

## Implementation choices

The patch binds UUID-keyed remote preview holders and intercepts the final draw for those surfaces. This keeps the original EGL window, buffer queue, transport, decoder, draw count and camera-control paths. Rotation and scaling use the EGL video buffer dimensions as the source and the SurfaceView dimensions as the destination, after the stock orientation policy. This compensates when Compose stretches a landscape 1920×1080 buffer into a portrait frame. GL receives adjusted quad vertices and a compensated sampling matrix. Fit and crop can require a clear or scissor on the existing window. The extension reuses a small vertex buffer and scratch arrays and allocates no video frames.

The exact version and code gate complements structural checks on SurfaceHolder callbacks, GL calls, register counts and typed fields. A version or method-shape mismatch aborts the patch. Importing the patch keeps it opt-in. Reset calls the stock draw again and saves default settings.

- **Rotation** adds a clockwise 0°, 90°, 180° or 270° turn. The calculator exchanges source dimensions for 90° and 270°.
- **Aspect ratio** sets Original, 16:9, 9:16, 4:3 or 1:1 as the framing choice. Original keeps the stock source proportions.
- **Fit** contains the image in the selected frame. **Center crop** covers the largest selected frame that fits within the viewport. **Fill** covers a frame sized to fill the viewport and clips any overflow at the viewport. Original makes Fill and Center crop equivalent. **Default** uses the stock draw when rotation and aspect are also at their defaults, and otherwise uses Fit.
- Open the control with the rotation icon at the top end of the selected feed. The icon sheds its background after 3.5 seconds, and the same small area opens the menu again. The icon remains visible with touch exploration enabled. Transform choices are application-wide for the selected remote monitor and stored in a dedicated SharedPreferences entry.

This SurfaceView receives already-oriented frames from the stock renderer. Applying the change at the last GL draw avoids the layering and clipping side effects of rotating a SurfaceView and the lifecycle/compositing changes of replacing it with a TextureView. Compose remains responsible for its original camera UI. Any crop previously applied by Blackmagic still limits the content available for display.

## Remote focus coordinates

The controller includes a second coordinate path for touch focus. Stock `di1.v` normalizes a tap against the Compose preview bounds. `t8a.i1` applies Blackmagic's camera-orientation conversion before a point-of-interest request is sent. The focus patch first maps the normalized screen point back into the untransformed preview using the geometry of the current EGL draw. This includes the inverse turn, scale and crop. The stock orientation conversion still runs afterwards.

The shared tap handler drops taps in bars outside visible video. The UI's immediate focus marker uses the user's original tap. Remote point-of-interest updates follow the opposite mapping in `t8a.t1`, so the corresponding marker returns to the same displayed region. The view is selected by camera UUID and the geometry comes from the registered remote output. Default rendering and local-camera tap handling retain their original coordinate behavior.

## Build and integrity verification

Install JDK 21, Android SDK platform 33 and Build Tools 36.1.0. Set `JAVA_HOME`, `ANDROID_HOME`, and the GitHub Packages credentials described by upstream. Use an authenticated account with `read:packages`.

```powershell
.\gradlew.bat :patches:test :patches:buildAndroid --no-daemon
```

The primary output is `patches/build/libs/patches-<version>.mpp`. Here, the wrapper downloaded Gradle 9.8.1 and failed to resolve the authenticated `app.morphe.patches:2.0.0` plugin. I built and tested a development MPP from the patch sources with JDK 21, the Android SDK and Kotlin 2.4.10 from the downloaded Gradle distribution against Morphe Desktop 2.3.0. An isolated Gradle project also compiled the extension, packaged its DEX resource and passed its thirteen tests. The alternative MPP contains JVM patch classes, Android patch DEX and the separate extension DEX. Desktop application was verified on that bundle. On-device bundle loading remains unverified.

```powershell
$bundle = ./scripts/build-monitor-local.ps1 -MorpheDesktop $desktopJar -GradleLib $gradleLibDir
./scripts/verify-monitor-apk.ps1 -StockBaseApk $stockBaseApk -PatchBundle $bundle -MorpheDesktop $desktopJar -Apktool $apktoolJar
```

`$gradleLibDir` is the distribution's `gradle-9.8.1/lib` directory. The verifier enables only the transform patch, validates fingerprint uniqueness, patches a private copy and checks the resulting DEX with Apktool. Test outputs stay in ignored build directories. Android Build Tools also verified the patched base and both original split payloads after alignment and re-signing with a single private test key. Repackaging all three original modules with Desktop 2.3.0 also patched successfully, and Desktop emitted a merged unsigned APK regardless of the supplied `.apks` output suffix. This establishes archive ingestion only. For split-device validation, use a signing/install workflow that keeps the three split APKs as a matched set.

## Device validation

1. Verify the three original package files and preserve the stock app, data, presets, LUTs and installation. First run the comparison captures below with the stock app, using the same iPhone, network, camera settings and monitor mode intended for the patched run.
2. Use a separate test installation that can accept the patched package and pass Blackmagic's existing app checks. Android requires a compatible signing identity for an in-place update and a matching certificate across all APKs of a split set. The copied APKs use the Play certificate. A locally signed set cannot update the installed package in place. Upstream also documents a PairIP launch failure on re-signed builds. The monitor patch has no dependency on the separate upstream license patch, and the offline test excludes it. [Android app signing](https://developer.android.com/studio/publish/app-signing)
3. Launch the authorized test build and link the iPhone using the stock remote-control flow. Use a test chart with readable corner labels. With the Android orientation fixed, switch through all rotations and confirm only the video turns. Exercise every ratio and scale mode using both a portrait and landscape iPhone feed. Check for distortion, stale bands, clipping and controls that overlap the menu target.
4. Test the stock camera controls, focus/exposure interaction as applicable, start/stop recording on the iPhone, switch cameras, leave and reopen the screen, background and foreground, rotate the S23 Ultra, interrupt the network and reconnect. Verify the settings survive force-stop/reopen and verify Reset against the baseline. Inspect the iPhone file's orientation, resolution, frame rate and content for each controller rotation. Check local preview, gallery and MultiView independently for regressions.
5. For latency, record an LED and the S23 Ultra preview together with a third camera at 240 fps or faster. Use at least 30 transitions per mode and compare median and p95 glass-to-glass latency for stock, patched default, 90° Fit and Fill. For rendering, collect at least three 60-second Perfetto traces per mode on the same network/settings. Record presented-frame intervals, missed frame deadlines, GPU/CPU usage and graphics memory. Include orientation changes and reconnect in separate traces. Report the device, software versions, network, codec, resolution, fps and sample counts alongside results.

## Device test results

- The stock Android app displayed the iPhone feed before replacement, as confirmed by the operator.
- With the operator's approval, a private test build was prepared from the verified stock files with **Remote monitor display transforms** and upstream's existing **Disable license check** enabled. All other upstream patches were excluded. Each APK in the three-file split set passed Android signature verification under one local key.
- The SM-S918U1 installed all three APKs with ADB. The patched activity completed cold launch and reached the camera UI after granting camera and microphone access. Optional photo library and location access were left off.
- The patched controller discovered the iPhone 17 Pro Max and connected after the operator entered its password directly on the S23.

- The first device build still showed the stock sideways, stretched feed. It had only hooked the alternate controller surface path. The revision added hooks for the actual `qfa.d` / `gz7` / `jk` path, kept the original 1920×1080 EGL buffer and used its dimensions to size the rotated video.
- On the revised build, 90° with Original and Fit displayed upright text and restored a square reference chart to square proportions. The outer chart outline measured approximately 464 × 463 pixels in the S23 screenshot. The Android HUD remained upright. The 1:1 Fit and Center crop choices produced distinct framing while preserving the chart proportions.
- With the rotated preview active, the controller started and stopped a short iPhone recording. The iPhone tally turned red and returned to idle, and the controller continued to report 4K, 16:9, 24 fps. The recorded file itself was not inspected.

- A force-stop and cold launch returned to the local-camera screen. After reselecting the iPhone from the stock remote-camera menu, the live feed returned and the Rotation menu still showed 90° checked. Reset restored the stock stretched presentation. The phone was left on the live iPhone feed with 90°, Original, and Fit enabled.

- The touch-focus revision installed in place on the S23, and the same iPhone pairing and 90° setting were available after reopening. Taps at (330, 341) and (330, 788) centered the S23's AE/AF marker on the corresponding top and bottom edges of the reference square. The operator confirmed the focus behavior on the iPhone. JVM coverage includes all four rotations, Fit bars, Center crop and Fill.

### Preview frame timing

SurfaceFlinger actual-present timestamps were sampled from the active remote preview's BLAST surface with the iPhone reporting 4K, 16:9, 24 fps. Default uses the stock draw in the installed test build. Each row is a roughly three-second sample.

| Mode | Presented frames | Effective fps | Median frame interval | p95 frame interval |
| --- | ---: | ---: | ---: | ---: |
| Default | 80 | 23.65 | 41.63 ms | 41.69 ms |
| 90° / Original / Fit | 74 | 23.69 | 41.63 ms | 41.68 ms |

These samples measure Android presentation cadence. Glass-to-glass latency, sustained frame timing, GPU utilization and memory were not measured.

## Verification matrix

| Function | Implementation | Evidence |
| --- | --- | --- |
| Upstream audit and exact target APK identity | Complete | Cloned and reviewed the pinned upstream commit. Verified device export hashes, manifest and all signatures. |
| Development patch bundle | Built | Java extension, D8, Kotlin compiler and MPP package succeeded. Authenticated Gradle build remains unverified. |
| Exact APK fingerprints and bytecode | Applied | Morphe Desktop ran with uniqueness validation and enabled only the monitor patch. Apktool verification covers the extension and untouched decoder, transport and license classes. APK Signature Scheme verification passed for the aligned and locally signed three-APK test set. |
| Rotation, aspect, Fit/Fill/Center crop, invalid input and reset | Partially device-tested | Thirteen JVM tests passed. The S23 displayed the square chart upright at 90° Original Fit. Live 1:1 Fit and Center crop produced distinct framing. |
| Remote touch focus | Device-tested | Opposite-edge taps tracked at 90° on the S23, and the operator confirmed iPhone focus behavior. Coordinate tests cover bars and crop. |
| Preferences and control | Device-tested | The 90° choice persisted across process restart and camera reselection. Stock Record and the transform menu both responded. |
| Surface recreation, device rotation and reconnect | Partially device-tested | The restarted app recreated the remote preview after reselecting the iPhone. Physical Android display rotation was not exercised. |
| Device launch, live connection and iPhone recording | Partially tested | Signed split set installed and launched. Live iPhone video and remote Record start/stop passed. The original recorded file still needs independent inspection. |
| Latency and rendering performance | Partially measured | Both brief device samples had a 41.63 ms median frame interval. End-to-end latency and sustained rendering load remain unmeasured. |

## Changelog

- Added an opt-in remote monitor transform for the selected feed in version 3.5.2.0025.
- Added scoped surface lifecycle tracking, final GL presentation geometry, persistent preview controls, JVM tests and a private-APK verifier. Added UUID-keyed controller surface hooks and corrected the source aspect to use buffer dimensions after the initial device test.
- Matched remote touch-focus request and response coordinates to the displayed preview, including letterboxing and crop.
