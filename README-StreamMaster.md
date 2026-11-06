# StreamMaster: how it works

This document explains the code added on top of the original Yasea project: what each
part does, how the GPU pipeline works, why some things behave the way they do, and
which parts are known to be unfinished or wrong. It is explanation only.

Branch: `streammaster-v2`. Language: Java everywhere. The only C/C++ in the project is
the original `libyuv` / `libenc` code, which the new pipeline no longer uses.

---

## 1. What the app does

- Captures several video sources at once: back camera, front camera, individual lenses
  (ultra-wide, telephoto), and screen share (USB cameras are not built yet).
- Composes them into one picture on the GPU: free-form layers you can move, resize,
  overlap, crop and fit, plus text, a scrolling ticker and logos.
- Encodes that picture to H.264 + AAC with the phone's hardware encoders.
- Sends it over RTMP or RTMPS, and/or records it to an MP4 file. The two outputs are
  independent: recording works with no network.
- Keeps working in the background and with the screen off (foreground service),
  supports picture-in-picture, a black-screen power-saver, and a locked-down kiosk mode.
- Saves your scene automatically and lets you keep named profiles.

---

## 2. Where things live

```
library/src/main/java/net/ossrs/yasea/
  engine/                  <- the new pipeline (all new code)
    EglCore.java             OpenGL ES context + output surfaces
    Compositor.java          the GPU drawing loop
    VideoSource.java         base class of every live input
    Camera2Source.java       one camera / one physical lens
    ScreenSource.java        screen capture (MediaProjection)
    Layer.java ...           scene model: SourceLayer, TextLayer, ImageLayer, BitmapLayer
    LayoutMode.java          preset arrangements (single, PiP, split, grid)
    MediaEncoders.java       H.264 (from a GPU surface) + AAC (from the mic)
    EncoderCaps.java         asks the phone what its encoders can really do
    StreamEngine.java        owns everything; routes encoded data to outputs
  SrsFlvMuxer / SrsMp4Muxer  original muxers, reused as-is
com/github/faucamp/simplertmp/  original RTMP client (now also speaks RTMPS)

app/src/main/java/net/ossrs/yasea/demo/
  studio/
    StudioActivity.java      the screen: preview, buttons, dialogs, kiosk, PiP
    StreamService.java       foreground service that owns the engine
    SceneEditorView.java     transparent overlay for touching layers on the preview
    LayerPanel.java          bottom sheet to add/order/edit layers
    SceneStore.java          saves/restores the scene as JSON
    ProfileStore.java        named profiles (settings + scene)
    Prefs.java               settings in SharedPreferences
    BootReceiver / AdminReceiver   kiosk helpers
  MovieSaver.java           moves finished recordings into Movies/StreamMaster
  MainActivity.java         OLD demo screen, no longer launched (can be deleted)
```

The old path (`SrsPublisher`, `SrsEncoder`, `SrsCameraView`, `libyuv`) still exists but is
not used by the new screen.

---

## 3. The big picture

```
 Camera 0 ─┐
 Camera 1 ─┼─> SurfaceTexture ─┐
 Screen   ─┘   (one per source)│
                               v
                      ┌─────────────────┐   draws all layers   ┌──────────────────┐
 Text / logo bitmaps ─>│   Compositor     │────────────────────>│ H.264 encoder     │
                      │ (GPU thread, 30fps)│   (a Surface)       │ (hardware, no CPU │
                      └─────────────────┘                      │  pixel copies)    │
                               │ same scene, letterboxed        └────────┬─────────┘
                               v                                         │ encoded frames
                        preview SurfaceView                     Mic ─> AAC encoder
                                                                          │
                                                       ┌──────────────────┴────────────┐
                                                       v                               v
                                                  FLV muxer ─> RTMP/RTMPS        MP4 muxer ─> file
```

`StreamEngine` is the hub. `StreamService` keeps one engine alive. `StudioActivity`
binds to the service and is only a window onto it: you can close the screen and the
stream continues.

---

## 4. How the GPU part works

### Why the GPU
The original app did this for every frame: camera -> GPU -> read pixels back to the CPU
(`glReadPixels`) -> convert colours on the CPU (libyuv) -> copy into the encoder. That is
slow and burns CPU and battery, and it cannot reach 4K.

The new path never lets the CPU touch pixels. Everything stays in GPU memory:

1. **Each source is a texture.** `VideoSource` creates an OpenGL texture of the special
   type `GL_TEXTURE_EXTERNAL_OES` and wraps it in a `SurfaceTexture`. A camera (or the
   screen capture) is told to render into that `SurfaceTexture`'s `Surface`. Frames arrive
   as GPU buffers and are bound as a texture with no copy.
2. **One OpenGL context for everything.** `EglCore` creates a single EGL context. Both the
   encoder and the preview are "window surfaces" created from that context, so the same
   textures can be drawn to either.
3. **The encoder has a Surface as its input.** `MediaCodec.createInputSurface()` gives a
   `Surface`; whatever is drawn into it is encoded. `Compositor` makes an EGL window surface
   from it and draws into it. This is the zero-copy step: GPU draws -> hardware encoder
   reads it directly. The frame timestamp is set with `eglPresentationTimeANDROID`.
4. **A fixed-rate loop.** `Compositor` runs its own thread. Every 1/fps seconds it:
   - latches the newest frame of each source (`updateTexImage`),
   - draws the whole scene into the encoder surface,
   - draws the same scene into the preview surface (letterboxed to the preview shape).
   Frames are produced at a steady rate even when a source is static (for example a still
   screen), because streaming servers dislike irregular timestamps.
5. **No preview needed.** If the screen is off or the activity is gone, the preview
   surface is simply detached and only the encoder surface is drawn. That is why background
   streaming and the black-screen mode save GPU work.

### What is drawn for each layer
Layers are drawn from bottom to top of the list, so higher layers cover lower ones.
For every layer the compositor sets a scissor rectangle (so nothing draws outside its
box or outside the frame) and draws one quad (two triangles) with a small shader:

- **Video layer** uses a shader with `samplerExternalOES`. A 4x4 "texture matrix" decides
  which part of the source image lands on the quad. It is built from, in order:
  1. mirror (selfie view or the user's Mirror toggle),
  2. fill/fit scaling,
  3. the user's crop (the four crop sliders),
  4. a rotation to make the picture upright for how the phone is held,
  5. the `SurfaceTexture` transform that Android supplies.
  Matrices are multiplied so the last item applies first to the texture coordinates.
- **Fit modes:** *Crop to fill* shrinks the texture area shown so the picture covers the
  box (the overflow is cut). *Fit whole image* instead shrinks the viewport so the entire
  picture is visible with bars. *Stretch* ignores the picture's aspect ratio.
- **Opacity** is applied by multiplying the colour; blending is on with premultiplied alpha.
- **Text, ticker and logo layers** are bitmaps. A second shader (`sampler2D`) draws them.
  See section 6.

### The ticker (marquee)
A ticker is a thin bitmap of the text on one line. Each frame the compositor computes how
far it should have travelled (time x speed), moves the quad to that x position, and the
scissor rectangle clips it to the ticker's box, so text slides in from the right and out
on the left. A solid bar behind it is a 1x1 white texture tinted with the bar colour.

---

## 5. Rotation, mirroring and why cameras looked sideways

Camera sensors are mounted rotated. Android supplies a transform matrix with every frame
(`SurfaceTexture.getTransformMatrix`) that already turns the picture to the phone's
natural (portrait) orientation. So the app only has to add the rotation for how the phone
is actually being held now.

Early on the code also added the sensor's own rotation, which turned the image a second
time and left it a quarter-turn off. The fix was to compensate only for the display
rotation (taken from the orientation sensor, so a stream stays upright however the phone
is held), plus telling the compositor the "base" size of the picture after Android's
transform (portrait frames from a landscape buffer), so that crop and fit use the right
aspect ratio. Without that the picture looked zoomed and stretched.

On Android 12+ foldables and tablets the system can also silently rotate and crop the
camera stream itself (`SCALER_ROTATE_AND_CROP`). The code turns that off so the maths is
the same on every phone.

---

## 6. Why text can appear inverted (a known bug)

This is a real bug in the code, not a device quirk.

Text, tickers and logos are drawn into an Android `Bitmap` with a normal canvas, then
uploaded to the GPU with `GLUtils.texImage2D`. A bitmap's first row is its **top**. When
uploaded, that first row becomes row 0 of the texture, which is texture coordinate `v = 0`.

The quad that the compositor draws uses `v = 0` at the **bottom** of the quad (the
convention that suits camera textures, whose transform matrix flips them for you). Camera
frames work because the `SurfaceTexture` matrix contains that vertical flip. Bitmaps have
no such matrix: the compositor passes an identity matrix for them. So the top of the
bitmap lands at the bottom of the screen, and **text and logos come out upside-down
(flipped vertically)**. A ticker would scroll correctly sideways but also be upside-down.

The fix is small and local: for bitmap layers, flip the vertical texture coordinate once
(for example, use a texture matrix that maps `v` to `1 - v` instead of the identity
matrix, or draw the quad with flipped coordinates). It has not been applied because this
document was requested as explanation only. Logos are affected the same way as text.

---

## 7. Encoding, muxing and network

- **Video:** `MediaEncoders` configures H.264 (High profile, constant bitrate, 2-second
  keyframes). If the phone rejects High profile or CBR it retries with plain defaults.
  Bitrate can change live with `PARAMETER_KEY_VIDEO_BITRATE`.
- **Audio:** microphone at 44.1 kHz, AAC-LC at 128 kbps. If the mic is unavailable it
  sends silence so the file/stream still has an audio track. Timestamps come from the
  sample clock, not from `read()` timing, so they cannot drift.
- **Shared timeline:** both streams are stamped in microseconds since the pipeline started.
- **Muxers:** the original `SrsFlvMuxer` (for RTMP) and `SrsMp4Muxer` (for files) are reused.
  They parse the H.264 configuration from the stream itself, so `StreamEngine` caches the
  codec configuration and replays it when an output starts late (for example, recording
  starts while you are already live) and asks for a keyframe so the new output can begin.
- **RTMPS:** `RtmpConnection` now accepts `rtmps://`, wraps the socket in TLS with
  certificate and hostname checks, and defaults to port 443.
- **Reconnect:** if the connection drops, the engine retries with 2, 4, 8 and 16 seconds
  of backoff, and re-attaches the stream.
- **Adaptive bitrate:** once a second the engine reads how many frames are waiting to be
  sent. More than about a second of backlog lowers the bitrate by 25% (down to a quarter
  of your target). Five calm seconds raise it by 15% back toward the target. If the backlog
  grows past four seconds, frames are dropped until the next keyframe rather than letting
  delay and memory grow without limit.
- **Resolution list:** `EncoderCaps` asks the phone's `MediaCodec` for its real limits, so
  the Quality list can mark what the encoder supports. Streaming and recording are H.264
  only; 8K needs HEVC (see section 10).

---

## 8. Background, power and the rest of the app

- **`StreamService`** is a foreground service (camera, microphone and, while sharing the
  screen, media-projection types) with a notification that has a Stop button. It holds a
  wake lock and a low-latency Wi-Fi lock while a stream or recording runs, and shuts itself
  down when both end and no screen is attached.
- **Screen share** needs the service to already be in the foreground, so the service is
  promoted first, then the projection token is turned into a `ScreenSource`.
- **Picture-in-picture:** the activity is marked PiP-capable. On Android 12+ it enters
  automatically when you leave; older versions use `onUserLeaveHint`. The window uses the
  output's aspect ratio (limited to what Android allows) and hides the controls.
- **Black-screen mode:** the preview is detached, a black overlay is shown and the brightness
  set to its minimum. Three quick taps wake it.
- **Kiosk mode:** immersive full screen, controls hidden, Android screen pinning
  (`startLockTask`), Back blocked, and a padlock button that asks for the PIN to exit.
  Options: start streaming, start recording, darken the screen, start on boot. If the app is
  set up as device owner (see `AdminReceiver`) the pinning is silent and complete. "Start on
  boot" uses a boot receiver plus an optional Home-app alias, because newer Android blocks
  apps from starting activities in the background.
- **Scene editor:** `SceneEditorView` sits above the preview and maps your finger to the
  same letterboxed rectangle the GPU used, so taps land on the right layer. Drag moves, a
  pinch or corner drag resizes, double-tap opens the panel. A floating toolbar offers
  To front, Forward, Backward, To back, Edit and Delete.
- **Persistence:** `SceneStore` writes the layers (position, size, order, crop, fit, text
  style, logo files) to JSON in SharedPreferences shortly after any change. `ProfileStore`
  bundles that JSON with the settings under a name, with import/export as text (the stream
  URL is left out when sharing because it holds your key).

---

## 9. Things the code cannot do, and why

- **Two cameras at once** only works on phones that support it (Android 11+ lists the
  allowed pairs). Two lenses of the same back camera cannot be shown together. Otherwise the
  app shows a clear message instead of a black tile.
- **Screen share is not restored** after a restart, because Android asks for permission each
  time.
- **8K and 16K:** hardware H.264 encoders do not go that high. 16K does not exist on any
  phone camera or encoder.

---

## 10. Known issues and not-yet-done

- **Text, ticker and logo layers are upside-down** (section 6). Not yet fixed.
- Streaming to a real server and recording a long 4K file were not yet tested end to end.
- A history screen for finished recordings, USB camera support, HEVC/8K recording, and
  automatic file splitting for long kiosk recordings are not built.
- Many behaviours (flip camera, the lens list, layers, PiP, kiosk) were only compiled and
  installed, not exercised on the device, so expect bugs.
- The old demo `MainActivity` and the unused libyuv code can be deleted.

---

## 11. Building and installing

```
sh gradlew assembleDebug          # gradlew is executable now, plain ./gradlew also works
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
```

If the phone answers `INSTALL_FAILED_VERIFICATION_FAILURE`, its package verifier blocked the
install. Approve the prompt on the phone or relax the check in the phone's own settings.

Optional locked-down kiosk (device must have no accounts set up):

```
adb shell dpm set-device-owner net.ossrs.yasea.demo/.studio.AdminReceiver
```

Test streaming without a server using ffmpeg on a computer on the same network:

```
ffmpeg -listen 1 -i rtmp://0.0.0.0:1935/live/test -c copy out.flv
```

then use `rtmp://<computer-ip>:1935/live/test` as the stream URL in Settings.
