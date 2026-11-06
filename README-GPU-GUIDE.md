# GPU programming on Android: from zero to professional

A learning guide written for someone new to the GPU who wants to get properly good at it.
It uses the StreamMaster code as a working lab, so every idea can be tied to code you can
open, change and run on your own phones.

How to use it: read parts 1 to 4 in one sitting to get the mental model, then do the labs
in part 13 in order. Skim the rest and come back when a lab needs it. Do not try to read
this like a novel.

Honesty note: tool names, extension names and book/site titles below are from memory of the
Android, Khronos and vendor documentation. They are real, but menus and URLs move, so
search for the name if a link has changed. Where something depends on your phone, the guide
shows you how to check it instead of guessing.

---

## Contents

1. What a GPU is, in one page
2. The graphics pipeline
3. Coordinate systems (and why text came out upside-down)
4. Which API do you use on Android?
5. How you get access: EGL, step by step
6. Shaders and GLSL ES
7. Textures, buffers and zero-copy
8. Matrices: the maths you actually need
9. Timing, threads and synchronisation
10. Mobile GPU architecture and performance
11. Tools: measuring and debugging
12. Beyond drawing: compute, ML, HDR and Vulkan
13. Labs, using StreamMaster
14. A learning path and resources
15. Glossary

Appendix A: map of StreamMaster files to GPU concepts.
Appendix B: checklists (black screen, slow frames, wrong colours).

---

## 1. What a GPU is, in one page

**CPU vs GPU.** A CPU has a few very clever cores that run one stream of instructions fast
and can handle branches, I/O and anything. A GPU has hundreds to thousands of simple
arithmetic units that run the *same small program* on many pieces of data at once. It is bad
at "do this one clever thing", and excellent at "do this same small thing to 8 million
pixels, 30 times a second".

**Your video problem is a perfect GPU problem.** A 4K frame is 3840 x 2160 = 8.3 million
pixels. Colour-converting, scaling, rotating or blending each pixel is an identical tiny
calculation. Done on the CPU it is slow and hot. On the GPU it is cheap.

**Mobile GPUs are different from desktop GPUs.**
- They share memory with the CPU (one pool of RAM, no separate graphics memory). Moving data
  "to the GPU" is usually about *who owns the buffer*, not copying across a bus.
- **Memory bandwidth, not arithmetic, is usually the limit.** Reading and writing pixels
  costs battery and heat. Good mobile GPU code is mostly about touching less memory.
- They are usually **tile-based** (part 10): the screen is split into small tiles rendered
  one at a time in fast on-chip memory.
- They throttle when hot. A benchmark that runs fast for 10 seconds may run 40% slower after
  10 minutes. Live streaming is a 10-minute-plus workload.

**The vendors you will meet.** Qualcomm **Adreno** (Snapdragon phones, for example the
Snapdragon 8 Gen 2 in the OnePlus Open should be an Adreno 740), ARM **Mali** and
**Immortalis** (Exynos, MediaTek and Google Tensor), Imagination **PowerVR**, Samsung
**Xclipse** (AMD-based, in recent Exynos flagships). Each has its own quirks, drivers and
bugs. Test on more than one.

**Your phone, measured.** Your Galaxy A13 reports `Mali-G52, OpenGL ES 3.2`. That is the kind
of GPU most people own: entry-level, so it makes a good "worst case" test device. Find yours:

```
adb shell dumpsys SurfaceFlinger | grep -A2 GLES
```

---

## 2. The graphics pipeline

Everything you draw goes through the same stages. Learn this list until you can recite it:

```
 your data            programmable        fixed          programmable        fixed
 (vertices, ----> [Vertex shader] ---> [Rasteriser] ---> [Fragment shader] ---> [Blend/depth] ---> framebuffer
  textures,         runs per vertex      turns triangles   runs per pixel        combines with
  uniforms)         -> screen position    into pixels       -> colour             what is there
```

1. **Vertices.** You give the GPU points (positions, texture coordinates...). Triangles are
   the only thing it really draws. A rectangle is two triangles. StreamMaster draws a
   4-vertex `GL_TRIANGLE_STRIP` (a strip makes two triangles from four points).
2. **Vertex shader.** A small program run once per vertex. Its job: output the position in
   *clip space*, and pass values (like texture coordinates) onward.
3. **Rasteriser.** Fixed hardware: works out which pixels each triangle covers and
   *interpolates* the values passed from the vertex shader across them.
4. **Fragment shader.** A small program run once per covered pixel (a "fragment"). Its job:
   output a colour. This is where texture sampling, colour grading and filters happen.
5. **Per-fragment operations.** Scissor test, depth test, blending (how the new colour mixes
   with what is already there, used for transparency), then it is written to the
   **framebuffer**.

A **framebuffer** is where results go: the window's surface, an off-screen texture (an FBO),
or, in StreamMaster, the **video encoder's input surface**.

Key idea: **you do not draw "an image". You draw geometry, and the fragment shader decides
every pixel's colour.** A video frame is just a texture glued onto a rectangle.

---

## 3. Coordinate systems (and why text came out upside-down)

You will lose hours to this, so learn it once.

| Space | Range | Where (0,0) is | Y points |
|---|---|---|---|
| **Clip / NDC** (what the vertex shader outputs) | x, y, z from -1 to +1 | centre | up |
| **Window / viewport pixels** (`glViewport`, `glScissor`) | 0..width, 0..height | **bottom-left** | up |
| **Texture coordinates** (s,t or u,v) | 0 to 1 | the **first row of data you uploaded** | depends on how you uploaded |
| **Android Canvas / Bitmap / View** | pixels | **top-left** | **down** |

The trap: a `Bitmap`'s first row is its *top*. `GLUtils.texImage2D` uploads that first row as
texture row 0, which is texture coordinate `v = 0`. If your quad puts `v = 0` at its
*bottom*, the bitmap's top lands at the bottom of the screen and the image is upside-down.

That is exactly the bug StreamMaster had with text and logos. Camera frames did not suffer
because Android's `SurfaceTexture.getTransformMatrix()` already contains the vertical flip.
Bitmaps have no such matrix. The fix is to flip `v` yourself (`v' = 1 - v`): in the code that
is `translate(0, 1)` then `scale(1, -1)` in the bitmap's texture matrix.

Rules of thumb:
- Decide per source whether it is "top-left origin" (bitmaps, files, canvases) or
  "bottom-left origin" (GL render targets) and convert in **one** place.
- When something is upside-down, think "flip v". Mirrored left-to-right, think "flip u".
  Rotated a quarter turn, think "wrong rotation matrix or swapped width/height".
- When `glViewport` or `glScissor` is involved, remember the origin is bottom-left. The
  compositor converts the layer's top-left rectangle with
  `ly = areaBottom - layerTop - layerHeight`.

---

## 4. Which API do you use on Android?

| API | What it is | Use it when | In StreamMaster |
|---|---|---|---|
| **OpenGL ES 2.0 / 3.x** | The mature, simple graphics API. C-like state machine. | Almost every 2D/video/camera effect. Best place to learn. | **Yes.** ES 2.0 is used. |
| **Vulkan** | Modern, explicit, low-overhead API. You manage memory, synchronisation, command buffers yourself. | Games and engines, heavy compute, when ES's driver overhead is your bottleneck. | No. |
| **Canvas / `HardwareRenderer` / Skia** | Android's UI drawing. GPU-accelerated under the hood. | Normal app UI, text and shapes. | Used to draw text into a bitmap. |
| **`RenderEffect` / AGSL (`RuntimeShader`)** | Write fragment-style shaders to affect *views* (Android 12/13+). | UI effects such as blur or ripple on views. | No. |
| **OpenGL ES compute shaders** (ES 3.1+) | General-purpose parallel programs on the GPU. | Filters, histograms, image processing. | No (lab idea). |
| **Vulkan compute / ML delegates** | GPU maths for ML and image processing. | On-device AI (background blur, segmentation). | No (idea). |
| **RenderScript** | Old GPU/CPU compute framework. | **Deprecated. Avoid.** | No. |
| **NDK `AHardwareBuffer`** | A raw GPU-shareable image buffer. | Zero-copy between camera, GPU, codec and other processes. | Indirectly (that is what a `Surface` is under the hood). |

**Decision rule for you:** learn **OpenGL ES 3.x first** (it is small and everything
transfers), then Vulkan once you feel the limits. Most pro video/camera apps on Android still
run on GL ES today.

**Check your phone:**
```
adb shell dumpsys SurfaceFlinger | grep -i "GLES"        # GL ES version
adb shell pm list features | grep -i -E "vulkan|opengles"  # Vulkan / ES features
```
In code, request a context with `EGL_CONTEXT_CLIENT_VERSION` 2 or 3, and check the actual
version with `glGetString(GL_VERSION)`.

---

## 5. How you get access: EGL, step by step

OpenGL ES draws, but it does not know about windows or Android. **EGL** is the glue: it
creates the drawing context and connects it to a place to draw (a surface). If you ever write
OpenGL on Android without `GLSurfaceView`, you write EGL. StreamMaster's `EglCore.java` is a
compact example. The chain:

```
EGLDisplay  ->  EGLConfig  ->  EGLContext        (the "GPU session")
                    \
                     -> EGLSurface (from a Surface/window or a pbuffer)   (where pixels go)
eglMakeCurrent(display, drawSurface, readSurface, context)   // binds both to THIS thread
... GL calls ...
eglSwapBuffers(display, surface)                              // present
```

1. **`eglGetDisplay` + `eglInitialize`.** Connect to the system's graphics stack.
2. **`eglChooseConfig`.** Describe the pixel format you want (8 bits per channel, ES2 or ES3
   capable) and get a matching config. StreamMaster adds the Android-specific
   **`EGL_RECORDABLE_ANDROID`** flag: without it the video encoder's input surface may refuse
   to work.
3. **`eglCreateContext`.** The GL state machine: every texture, shader program and setting you
   create lives inside a context.
4. **`eglCreateWindowSurface`.** Make a surface from an Android `Surface`: a `SurfaceView`, or
   the encoder's `createInputSurface()`. Or `eglCreatePbufferSurface` for an invisible one
   (used when you just need *a* current surface to create textures).
5. **`eglMakeCurrent`.** **GL calls only work on the thread where a context is current.**
   One context, one thread at a time. This rule explains a huge share of beginner crashes.
6. **`eglSwapBuffers`.** Hands the finished frame to the consumer (screen compositor or
   encoder).
7. **`eglPresentationTimeANDROID`.** Stamp the frame with a time so the encoder gets correct
   timestamps (StreamMaster does this just before each swap to the encoder).

**Why StreamMaster uses one context for many surfaces.** Textures belong to a context.
Using one context with two window surfaces (encoder and preview) means both can sample the
same camera textures; `eglMakeCurrent` simply switches which surface is drawn to.

**Easier alternatives to hand-written EGL**, when you do not need this control:
- `GLSurfaceView`: creates the EGL context and a render thread for you; renders to the screen.
- `TextureView`: lets you render GL into a view in the normal view hierarchy.
- `SurfaceView`: a separate window layer; most efficient for video/camera display.

**Surfaces are the universal plug.** A `Surface` is a producer endpoint of a buffer queue.
Many things can *produce into* a Surface (a camera, a video decoder, a `VirtualDisplay`
screen capture, a GL context) and many things can *consume from* one (the screen compositor
`SurfaceFlinger`, a `SurfaceTexture`, a video encoder). Almost all zero-copy work on Android
is "connect a producer's Surface to a consumer".

**Key `StreamMaster` flows built from that idea:**
```
camera ----> Surface of a SurfaceTexture ----> becomes a GL texture (OES)  -> your shader
your GL --> EGLSurface wrapping encoder.createInputSurface() -----------> hardware encoder
```

---

## 6. Shaders and GLSL ES

Shaders are written in **GLSL ES** (a C-like language), compiled by the phone's driver at
runtime. Three kinds of variables:

- **`attribute`** (ES 2.0; `in` in ES 3.0): per-vertex input from your buffers.
- **`uniform`**: one value for the whole draw call (a matrix, a colour, a time).
- **`varying`** (`out`/`in` in ES 3.0): passed from vertex to fragment shader and interpolated.

StreamMaster's two shaders, annotated:

```glsl
// VERTEX: place the quad, and compute which part of the picture to sample
attribute vec4 aPos;            // corner of the quad, in clip space (-1..1)
attribute vec2 aTex;            // 0..1 position inside the picture
uniform   mat4 uTex;            // the "texture matrix" (crop, rotate, mirror, camera transform)
varying   vec2 vTex;            // handed to the fragment shader
void main() {
    gl_Position = aPos;                         // where to draw
    vTex = (uTex * vec4(aTex, 0.0, 1.0)).xy;    // where to read from
}

// FRAGMENT: pick the colour for each covered pixel
#extension GL_OES_EGL_image_external : require  // must be the first line for camera textures
precision mediump float;                        // fragment shaders need a default precision
varying vec2 vTex;
uniform samplerExternalOES uSampler;            // camera/screen frames (special texture type)
uniform vec4 uColor;                            // multiplied in: carries opacity
void main() {
    gl_FragColor = texture2D(uSampler, vTex) * uColor;
}
```

Things to understand deeply:

- **Precision qualifiers** (`lowp`, `mediump`, `highp`). On mobile, `mediump` (about 16-bit
  floats) is faster and uses less power but is not enough for large texture coordinates or
  precise maths. Use `highp` for coordinates, `mediump` for colours. Fragment-shader `highp`
  is optional in ES 2.0 on some very old GPUs.
- **`samplerExternalOES` vs `sampler2D`.** Camera and video frames are in YUV, in a
  driver-owned layout. The OES "external" texture type lets the driver convert YUV to RGB
  *during sampling*, for free. You cannot use ordinary 2D features (mipmaps, arbitrary
  wrap modes) on it, and you must declare the extension.
- **Branching is not free.** GPUs run groups of pixels in lock-step. An `if` whose result
  differs inside a group runs *both* sides. Prefer maths like `mix()`, `step()`, `clamp()`.
- **Texture fetches are the expensive part**, then memory writes. ALU maths is often cheap.
- **Compile and link can fail on one vendor and not another.** Always read
  `glGetShaderInfoLog` / `glGetProgramInfoLog` (the compositor throws them as exceptions).
- **Uniform location `-1`** means the name was optimised away or misspelled. Not an error,
  but a silent bug source.

**Tiny shader recipes** (labs in part 13 use these):

```glsl
// grayscale
float g = dot(c.rgb, vec3(0.299, 0.587, 0.114));  gl_FragColor = vec4(vec3(g), c.a);

// brightness / contrast
c.rgb = (c.rgb - 0.5) * contrast + 0.5 + brightness;

// vignette
float d = distance(vTex, vec2(0.5));  c.rgb *= smoothstep(0.8, 0.3, d);

// rounded corners using a signed distance (uSize = box size in pixels, uRadius in pixels)
vec2 p = (vTex - 0.5) * uSize;  vec2 q = abs(p) - (uSize * 0.5 - uRadius);
float dist = length(max(q, 0.0)) - uRadius;  alpha = 1.0 - smoothstep(-1.0, 1.0, dist);
```

---

## 7. Textures, buffers and zero-copy

### Texture types you will use
- **`GL_TEXTURE_2D`**: ordinary images (your text and logo bitmaps).
- **`GL_TEXTURE_EXTERNAL_OES`**: camera / video / screen frames through a `SurfaceTexture`.
- **FBO textures**: a texture you *render into* (for multi-pass effects such as blur).

### Parameters that bite
- **Filtering:** `GL_LINEAR` is smooth, `GL_NEAREST` is blocky. Without mipmaps, shrinking a
  large image a lot with LINEAR gives shimmering; use mipmaps (not allowed on OES) or
  downscale in steps.
- **Wrap mode:** `GL_CLAMP_TO_EDGE` is required for **non-power-of-two (NPOT)** textures in ES 2.0
  (that is why the marquee is drawn as a moving quad clipped by a scissor instead of a
  repeating texture). ES 3.0 lifts the NPOT limits.
- **Premultiplied alpha.** Android bitmaps are premultiplied (colour already multiplied by
  alpha). Blend them with `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`. Using
  `GL_SRC_ALPHA` instead gives dark fringes around soft edges.

### `SurfaceTexture` lifecycle (important)
1. Create a GL texture of type OES. Wrap it: `new SurfaceTexture(texId)`.
2. `new Surface(surfaceTexture)` is what you give the camera.
3. When the producer has a new frame, `onFrameAvailable` fires, **on any thread**.
4. On the **GL thread**, call `updateTexImage()` to latch the newest frame, then
   `getTransformMatrix()` and draw. Frames you skip are dropped (good for latency).
5. Release the surface, the `SurfaceTexture` and delete the texture when done.

`updateTexImage()` needs the GL context current. The compositor calls it once per tick for
every source before drawing. Calling it from the wrong thread throws.

### Zero-copy: the reason this app is fast
Old approach (what the original project did):
```
camera -> GPU draw -> glReadPixels (GPU stalls, copies to CPU) -> CPU colour conversion
       -> copy into encoder buffer -> encode
```
`glReadPixels` forces the GPU to finish and copies megabytes per frame across memory; the CPU
then touches every pixel again. At 4K that is hopeless.

New approach:
```
camera -> (GPU buffer) -> your shader draws into -> encoder input Surface (GPU buffer) -> hardware encoder
```
At no point does the CPU read or write a pixel. The buffers are passed *by handle*
(`AHardwareBuffer` / gralloc) between camera, GPU and codec. **If you ever see
`glReadPixels` in a real-time path, treat it as a bug.** (It is fine in tests and for rare
screenshots; in ES 3.0 you can make it asynchronous with a pixel buffer object, PBO.)

### Uploading CPU data
`GLUtils.texImage2D(target, 0, bitmap, 0)` uploads a bitmap. Re-uploading every frame is
slow for large images; upload only when it changes (the compositor re-renders text bitmaps
only when the text, size or output resolution changes). For frequently updated regions use
`glTexSubImage2D`.

### Off-screen rendering (FBOs)
A **framebuffer object** lets you draw into a texture instead of the screen, which is how you
build multi-pass effects: draw the scene to texture A, blur horizontally into B, blur
vertically onto the screen. On tile-based GPUs each extra pass costs real bandwidth (part 10).

---

## 8. Matrices: the maths you actually need

You need only a little linear algebra, but you need it exactly.

- Transforms (move, scale, rotate) are **4x4 matrices**. `android.opengl.Matrix` stores them
  **column-major** (the opposite of how you write them on paper).
- `Matrix.multiplyMM(result, 0, lhs, 0, rhs, 0)` computes `lhs x rhs`. Applied to a column
  vector, **the right-most matrix acts first**.
- Rotating or scaling **about the centre** of a unit square: translate by `(+0.5, +0.5)`, do
  the operation, translate by `(-0.5, -0.5)`. In code with `translateM`/`scaleM`/`rotateM`
  you append operations *in the order they act on the vertex from the outside in*, which feels
  backwards at first. If a result looks wrong, print the matrix and test with a point.
- The compositor's **texture matrix** is a chain, outermost last:
  ```
  texMatrix = SurfaceTextureMatrix  x  Rotate  x  Region(crop)  x  Fill(scale)  x  Mirror
  ```
  which, for a texture coordinate, means: mirror it, then scale it for fill/fit, then
  select the cropped region, then rotate it upright, then let Android's own matrix map it
  into the real buffer layout.
- **A texture matrix moves the *sampling* position, not the picture.** That is why to rotate
  the image clockwise you rotate the sampling coordinates counter-clockwise, and to zoom in
  you make the sampled area *smaller*. This inversion confuses everyone at first.

Debug trick: draw a test pattern (a gradient, or a texture with the letter "F") and see
exactly how your matrix transforms it. An "F" shows rotation and mirroring unambiguously.

---

## 9. Timing, threads and synchronisation

### The frame loop
StreamMaster does not render on demand. A timer on the GL thread fires every `1/fps`
seconds and renders one frame, so an encoded stream has steady timestamps even for static
content. The loop corrects for drift (`nextFrame += interval`) and resyncs instead of
firing a burst if it falls far behind.

### Vsync, Choreographer and the display
Android displays are refreshed at fixed rates (for example 60/90/120 Hz; your OnePlus lists
modes up to 144 Hz). UI apps should draw in step with vsync using `Choreographer`.
A streaming compositor feeding an *encoder* is not bound to the display: it follows the
stream's frame rate. The preview is a by-product.

### Threads (the rule that explains most bugs)
- GL calls only on the thread that has the context current.
- Camera callbacks, `onFrameAvailable`, UI events arrive on other threads. They must not
  call GL. They set a flag or post a task to the GL thread (the code uses a `Handler` on a
  `HandlerThread`).
- Share state between threads with `volatile` fields or posted messages; StreamMaster's
  layers use `volatile` so the UI thread can edit while the GPU thread draws.

### GPU is asynchronous
`glDrawArrays` returns immediately; the GPU runs the work later. So:
- Timing a draw call on the CPU measures nothing useful. Use GPU timer queries
  (`EXT_disjoint_timer_query`) or a profiler (part 11).
- `glFinish()` blocks until everything is done: it destroys parallelism, use it only to
  measure. `glFlush()` just submits work.
- Anything that reads results back (`glReadPixels`, `glGetError` in a loop) can stall the
  pipeline.

### Sync primitives
- **`eglPresentationTimeANDROID`**: attach a timestamp to a frame going to an encoder.
- **Fences** (`EGLSync`, `glFenceSync`): wait until the GPU has finished a given piece of
  work. Needed when two processes or APIs share a buffer. Camera, codec and `SurfaceFlinger`
  do this for you through buffer queues; you meet fences when you work with
  `AHardwareBuffer` directly or Vulkan.

### Latency vs smoothness
The more buffers in a queue, the smoother but laggier. Streaming tolerates a little latency
but hates irregular timestamps. That is why the app drops *old* camera frames (always draws
the newest) and keeps a fixed output rate.

---

## 10. Mobile GPU architecture and performance

### Tile-based rendering (TBR) in plain words
Most phone GPUs (Adreno has a flexible variant, Mali and PowerVR are tile-based) do not
draw the whole screen at once. They sort all triangles into small screen **tiles** (for
example 16x16 to 64x64 pixels), then render one tile at a time into **fast on-chip memory**,
and write the finished tile to main memory **once**.

Why you care:
- **Overdraw inside a tile is cheap** (on-chip), but **loading and storing the tile to main
  memory is expensive**.
- Each time you switch render target (an extra pass, an FBO) the GPU may have to flush and
  later reload tiles. Many small passes are costly. Merge passes where you can.
- **Do not read back the framebuffer.** It forces a flush of everything.
- **Tell the GPU what you do not need.** `glClear` at the start of a pass lets it skip
  loading old contents. In ES 3.0, `glInvalidateFramebuffer` says "discard this, I won't
  use it". StreamMaster clears the output first for this reason.
- Transparent layers blended on top of each other cost bandwidth-per-pixel for each layer.
  Four full-screen 4K layers at 30 fps is 4 x 8.3 M x 30 = about 1 billion pixel-writes a
  second before the encoder even starts. Compositor cost scales with **pixels x layers x fps**.

### Doing the arithmetic (do this before you add a feature)
```
cost (pixel-draws per second) = outputWidth x outputHeight x fps x (number of layers covering each pixel)
```
4K, 30 fps, 2 full layers = 3840 x 2160 x 30 x 2 = about 500 million pixel-draws per second.
If your GPU does around a few billion simple pixel operations per second at sustainable
clocks, you are using a big share before shaders get heavy. That is why the app also draws
the *preview* at screen size, not output size, and detaches it when the screen is off.

### Practical performance rules
1. **Do less work per pixel.** Shorter fragment shaders, fewer texture reads, `mediump`.
2. **Touch fewer pixels.** Scissor regions, draw small layers as small quads (the
   compositor uses a scissor rectangle per layer), avoid full-screen passes you don't need.
3. **Avoid state changes and many tiny draw calls** (one draw per layer is fine; thousands
   per frame is not).
4. **Never stall the pipeline**: no `glReadPixels`, no `glFinish`, no CPU waiting on the GPU.
5. **Upload once, reuse.** Text bitmap only re-rendered when it changes.
6. **Heat is a feature limit.** Sustained 4K compositing may throttle after a few minutes.
   Test long runs. Android exposes thermal status (`PowerManager` thermal APIs) so an app can
   lower resolution or frame rate proactively. A good future feature for StreamMaster.
7. **Pick the right resolution.** Rendering at 4K when the camera only provides 2576x1449
   (your A13) costs GPU time and gains no detail. Match the output to the source.

### Why 4K can fail even if the encoder says it supports it
The encoder, the camera, and the GPU each have separate limits, and the weakest wins. The
app asks `MediaCodec` for encoder limits (`EncoderCaps`) and the camera for its stream sizes
(`Camera2Source`). The GPU rarely limits you, but memory bandwidth and heat can.

---

## 11. Tools: measuring and debugging

Always measure. Intuition about GPU performance is wrong more often than right.

- **Android GPU Inspector (AGI)**: Google's GPU profiler/frame debugger for Android. Captures
  GPU counters and frame traces; see what each draw call costs. Works with supported devices
  and drivers (check its device-support page for your phones).
- **Perfetto / System Tracing**: system-wide timeline (CPU, GPU, SurfaceFlinger, thread
  scheduling). Excellent for finding dropped frames and who stalled whom.
- **`adb shell dumpsys gfxinfo <package>`**: frame-time statistics for UI rendering.
- **`adb shell dumpsys SurfaceFlinger`**: lists layers, formats, GPU info (used above).
- **Vendor tools**: Qualcomm Snapdragon Profiler (Adreno), Arm Mobile Studio (Streamline
  and Performance Advisor, for Mali), Imagination PVRTune (PowerVR).
- **RenderDoc**: the best frame debugger for desktop GL/Vulkan; it also supports Android
  for Vulkan and GL ES in some configurations. Setup is fiddly, worth learning.
- **Developer options**: "Profile GPU rendering" (you have this toggle in quick settings),
  "GPU debug layers", "Show surface updates".

### In-code debugging
- Call `GLES20.glGetError()` after suspicious calls while debugging (not in hot loops).
- Log `glGetString(GL_RENDERER / GL_VERSION / GL_EXTENSIONS)` once at startup.
- **Solid colour test**: replace your fragment shader output with `vec4(1,0,0,1)`. If you see
  red, geometry and viewport are right and the problem is texturing/uniforms.
- **UV test**: output `vec4(vTex, 0, 1)`; you should see a red/green gradient. Wrong gradient
  direction tells you instantly which way your coordinates are flipped or rotated.
- **One thing at a time**: disable layers, draw one quad, add features back.
- Put an on-screen counter (frame number or time) in the output to see stutter and dropped
  frames when you play a recording.

---

## 12. Beyond drawing: compute, ML, HDR and Vulkan

### Compute shaders (OpenGL ES 3.1)
Run a GPU program that is not tied to drawing: a grid of "work groups" reads and writes
textures and buffers. Great for blurs, histograms, scaling, colour-space conversion. Needs
ES 3.1 (your A13 reports 3.2, so yes). Learn `image2D` load/store, work group size, and
memory barriers.

### On-device AI for video
Background blur or replacement needs a segmentation model. Practical route: **MediaPipe**
(Google) or **LiteRT** (the successor to TensorFlow Lite) with its **GPU delegate**, which
runs the model on the GPU. Pipeline idea: camera frame -> model -> mask texture -> your
shader blends sharp foreground over blurred/replaced background. This is a natural next
feature for StreamMaster.

### Colour, HDR and bit depth
- Cameras and codecs can do 10-bit and HDR (HLG/HDR10) on supported devices. That needs a
  10-bit surface/format, the right colour-space metadata (`EGL_EXT_gl_colorspace` family and
  `MediaFormat` colour keys), HEVC rather than H.264, and an HDR-aware path end to end.
- Know **linear vs gamma (sRGB)** space: blending and filtering are only physically right in
  linear space; most video pipelines cheat. Know the difference between BT.601, BT.709 and
  BT.2020 YUV matrices; the wrong one makes colours look washed out or too saturated.

### Vulkan, when you are ready
Vulkan trades convenience for control: you explicitly create devices, queues, command
buffers, pipelines, render passes and synchronisation. It can lower CPU overhead and gives
access to modern GPU features, but it is much more code (a "hello triangle" is hundreds of
lines). Learn it **after** you are comfortable with GL ES, using a good tutorial (part 14).
Most modern phones support at least Vulkan 1.1; check with `pm list features` and
`vulkaninfo`-style apps. For a 2D video compositor like this one, GL ES is plenty. Vulkan
pays off for games, heavy compute, and very high object counts.

### Multi-camera and system limits
Opening two cameras at once is restricted by **camera hardware and the HAL**, not by GL. That
is why the app asks `getConcurrentCameraIds()` (Android 11+) before allowing two cameras.
Not everything is a GPU problem. Learn to tell which subsystem is saying no.

---

## 13. Labs, using StreamMaster

Do these in order. Each builds a real skill, and each is small enough to finish in a
session. Use `git` branches. Test on both a strong phone and the weaker A13.

**Lab 1: Read the code (2 hours).**
Open `Compositor.java`. Without running it, answer: which thread calls `drawScene`? Where is
the encoder surface current? Why is there a scissor per layer? Where is the viewport set for
the preview and why is it different from the encoder's? Write your answers down, then check
the log or add `Log.d` lines to confirm.

**Lab 2: The UV debug view.**
Temporarily change the OES fragment shader to output `vec4(vTex, 0.0, 1.0)`. Rotate the phone
and flip cameras; learn what each matrix step does to the gradient. Put the original shader
back.

**Lab 3: A per-layer filter.**
Add a "grayscale" or "warm" toggle to a source layer. You will: add a `uniform` to the
fragment shader, add a field to `SourceLayer`, set the uniform in `drawSource`, add a switch
in `LayerPanel`. This touches model, GL and UI: the full loop.

**Lab 4: Rounded corners and borders for layers.**
Use the signed-distance recipe from part 6 to round the corners of picture-in-picture
windows and draw a coloured border. You need the layer's pixel size as a uniform.

**Lab 5: Chroma key (green screen).**
Compute how "green" a pixel is, and output transparency accordingly. Learn about edge
softening and spill suppression. Great visible result.

**Lab 6: LUT colour grading.**
Add a 3D look-up-table filter via a 2D "strip" texture (ES 2.0) or a real 3D texture (ES
3.0). Learn texture sampling as a data table.

**Lab 7: Blur (multi-pass, FBO).**
Implement a two-pass Gaussian blur into an FBO for a "blurred background behind a
picture-in-picture" effect. Learn FBOs and measure what the extra pass costs with AGI.

**Lab 8: Measure, then optimise.**
Run a 4K, 3-layer scene for 10 minutes. Record frame times and temperature. Try:
smaller preview, `mediump`, fewer layers. Write down what each change bought.

**Lab 9: Migrate to GL ES 3.0.**
Request a 3.0 context, convert shaders to `in`/`out`, then use a PBO for asynchronous
thumbnail capture (the recordings history screen needs thumbnails).

**Lab 10: Compute.**
Reimplement a filter as an ES 3.1 compute shader. Compare speed and code.

**Lab 11: Background blur with ML.**
Integrate a segmentation model with the GPU delegate; use its mask in a shader.

**Lab 12 (stretch): a Vulkan renderer.**
Port the compositor to Vulkan, or write a small Vulkan app that draws a camera frame. Expect
this to take weeks; it is where "pro" really starts.

---

## 14. A learning path and resources

These are widely recommended and real; search the titles if a URL has moved.

**Start here (concepts)**
- **LearnOpenGL** (learnopengl.com): the best free step-by-step. It targets desktop GL 3.3,
  but the ideas (shaders, textures, transforms, framebuffers) are identical; adjust syntax
  for ES.
- **The Book of Shaders** (thebookofshaders.com): builds intuition for fragment shaders with
  interactive examples.
- **Android developer docs**: "OpenGL ES" under the graphics section, plus the Android
  graphics architecture pages on source.android.com (buffer queues, SurfaceFlinger, HWC).

**Android-specific, video and camera**
- **Grafika** (Google sample app on GitHub: `google/grafika`): the canonical examples of EGL,
  `SurfaceTexture`, `MediaCodec` input surfaces and recording. Read its `EglCore`,
  `GlUtil` and "record GL app" code; StreamMaster's structure is a descendant of those ideas.
- **Android `MediaCodec` and `Camera2` docs**, and the CTS (compatibility test suite) tests
  in AOSP, which show correct usage.

**Reference and books**
- **OpenGL ES 3.0 Programming Guide** (Ginsburg, Purnomo, Shreiner, Munshi).
- **Khronos OpenGL ES specifications and reference pages** (docs.gl is a convenient index).
- **Real-Time Rendering** (Akenine-Moller et al.): the book for understanding how GPUs and
  graphics techniques work.
- **GPU Gems** series (free online): classic techniques (blur, tone mapping, colour).

**Vulkan**
- **Vulkan Tutorial** (vulkan-tutorial.com) and **Vulkan Guide** (vkguide.dev).
- Android's own Vulkan documentation and the Khronos **Vulkan samples** repository.

**Mobile performance**
- **Android GPU Inspector** documentation.
- **Arm developer** pages on Mali architecture, tile-based rendering and best practices,
  and **Qualcomm Adreno** developer guides. Vendor docs are where tile-based behaviour is
  explained honestly.

**Habits that make you a pro**
1. **Read driver logs and shader info logs** every time something fails.
2. **Build tiny test harnesses** (one quad, one texture) before touching the full app.
3. **Test on at least two GPU vendors**; many "bugs" are driver differences.
4. **Measure before and after** every performance change.
5. **Learn to read a capture** in a frame debugger; it is the GPU equivalent of a debugger.
6. **Keep a notebook** of every gotcha (like the upside-down text). Pros are people who made
   each mistake once and wrote it down.

---

## 15. Glossary

- **Attribute / uniform / varying:** per-vertex input / per-draw constant / vertex-to-fragment
  interpolated value.
- **Blending:** combining a new colour with the framebuffer colour (transparency).
- **Clip space / NDC:** the -1..1 coordinate cube the vertex shader outputs into.
- **Compositor:** program that combines several layers into one image (here: `Compositor`;
  on Android also `SurfaceFlinger`).
- **EGL:** the API that connects OpenGL ES to windows/surfaces and creates contexts.
- **Fragment:** a candidate pixel produced by the rasteriser; the fragment shader colours it.
- **Framebuffer (FBO):** a render target (screen surface or a texture).
- **GLSL ES:** the shader language.
- **Gralloc / `AHardwareBuffer`:** Android's shareable GPU memory buffers.
- **HAL:** hardware abstraction layer (camera, graphics drivers).
- **Mipmap:** pre-shrunk copies of a texture for clean minification.
- **NPOT:** non-power-of-two texture size (restricted in ES 2.0).
- **OES external texture:** texture type for camera/video frames, with built-in YUV to RGB.
- **PBO:** pixel buffer object, for asynchronous pixel transfers (ES 3.0).
- **Premultiplied alpha:** colour channels already multiplied by alpha.
- **Rasterisation:** converting triangles into covered pixels.
- **SurfaceFlinger:** Android's system compositor that puts all windows on the display.
- **SurfaceTexture:** consumer that turns frames sent to a `Surface` into a GL texture.
- **TBR / TBDR:** tile-based (deferred) rendering, the usual mobile GPU design.
- **Texture matrix:** matrix applied to texture coordinates (crop, rotate, mirror).
- **Uniform location:** handle used to set a uniform from code.
- **Viewport / scissor:** where on the surface drawing happens / where it is allowed to.
- **Vsync:** the display's refresh signal.

---

## Appendix A: where each concept lives in StreamMaster

| Concept | File |
|---|---|
| EGL display, config, context, surfaces; `EGL_RECORDABLE_ANDROID`; presentation time | `library/.../engine/EglCore.java` |
| Shaders, programs, quad, per-layer scissor/viewport, texture matrix chain, blending, fixed-rate loop | `engine/Compositor.java` |
| OES texture + `SurfaceTexture` lifecycle, `updateTexImage`, transform matrix | `engine/VideoSource.java` |
| Camera producing into a Surface, sizes, rotation, rotate-and-crop | `engine/Camera2Source.java` |
| Screen capture producing into a Surface | `engine/ScreenSource.java` |
| Encoder input Surface (zero-copy), bitrate, keyframes | `engine/MediaEncoders.java` |
| Text bitmap rendering, marquee | `engine/TextLayer.java`, `BitmapLayer.java` |
| Texture upload, 2D shader, v-flip fix | `engine/Compositor.java` (`uploadBitmap`, `drawBitmapLayer`) |
| What the hardware can really do | `engine/EncoderCaps.java`, `Camera2Source.describeLimits` |
| Mapping touch to the same letterboxed rectangle the GPU used | `app/.../studio/SceneEditorView.java` |

## Appendix B: checklists

**Black screen**
1. Is a context current on this thread? (`eglMakeCurrent`)
2. Is a surface attached, and is the viewport non-zero?
3. Did shaders compile and link? Read the info logs.
4. Did you call `updateTexImage()` after `onFrameAvailable`?
5. Is the texture complete (filtering set, wrap `CLAMP_TO_EDGE` for NPOT)?
6. Output a solid colour from the fragment shader to isolate geometry from texturing.
7. Is the *source* really producing frames? (Camera dark, permission denied, wrong id.)
   Check this before blaming GL: a dark scene looks identical to a bug.

**Upside-down / mirrored / sideways**
1. Which origin does this data use (top-left vs bottom-left)?
2. Is there a transform matrix being applied, or missing, for this texture type?
3. Print the UV gradient to see the real orientation.
4. Check width/height swaps when rotation is 90/270.

**Slow or hot**
1. Is anything calling `glReadPixels` or `glFinish`?
2. Compute pixels x layers x fps. Is the target realistic for this GPU?
3. Are you rendering the preview at a higher resolution than needed?
4. Capture with AGI or Perfetto; look for GPU time vs CPU time per frame.
5. Run for 10 minutes; check for thermal throttling.

**Wrong colours**
1. Premultiplied alpha blend function?
2. YUV matrix (601 vs 709) or full vs limited range mismatch?
3. Linear vs gamma blending?
4. `mediump` precision causing banding?

---

If you do labs 2 to 7 properly, you will have shaders, textures, framebuffers, matrices,
EGL, and profiling in your hands, which is the real foundation. Everything after that
(Vulkan, compute, ML, HDR) builds on the same mental model.
