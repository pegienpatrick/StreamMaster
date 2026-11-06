package net.ossrs.yasea.engine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** A Camera2 camera (front, back, external or any other id) as a compositor source. */
public final class Camera2Source extends VideoSource {

    /** Description of a camera (or one physical lens of a multi-lens camera) for a picker. */
    public static final class Info {
        public final String id;          // logical camera id passed to openCamera
        public final String physicalId;  // non-null: stream from this physical lens of {@link #id}
        public final String label;
        public final int facing;         // CameraCharacteristics.LENS_FACING_*

        Info(String id, String physicalId, String label, int facing) {
            this.id = id;
            this.physicalId = physicalId;
            this.label = label;
            this.facing = facing;
        }

        /** Stable source id, e.g. "cam:0" or "cam:0/4". */
        public String key() {
            return "cam:" + id + (physicalId != null ? "/" + physicalId : "");
        }
    }

    private final Context context;
    private final String cameraId;
    private final String physicalId;
    private final int reqW, reqH, reqFps;
    private final String label;
    private final boolean front;
    private volatile int displayRotation;

    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private volatile boolean stopped;

    public Camera2Source(Context ctx, Info info, int w, int h, int fps, int displayRotationDeg) {
        super(info.key());
        this.context = ctx.getApplicationContext();
        this.cameraId = info.id;
        this.physicalId = info.physicalId;
        this.reqW = w;
        this.reqH = h;
        this.reqFps = fps;
        this.displayRotation = displayRotationDeg;
        try {
            CameraManager cm0 = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Integer so = cm0.getCameraCharacteristics(info.id).get(CameraCharacteristics.SENSOR_ORIENTATION);
            this.baseSwapped = so != null && (so % 180) == 90;
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        this.front = info.facing == CameraCharacteristics.LENS_FACING_FRONT;
        this.label = info.label;
        this.mirror = this.front;
        updateRotation();
    }

    private static String labelFor(String id, Integer facing) {
        if (facing == null) {
            return "Camera " + id;
        }
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                return "Front " + id;
            case CameraCharacteristics.LENS_FACING_BACK:
                return "Back " + id;
            default:
                return "External " + id;
        }
    }

    /**
     * Every camera the app can show: each logical camera plus, on multi-lens phones,
     * the individual physical lenses (ultra-wide, telephoto...) that Android hides
     * behind the logical back camera.
     */
    public static List<Info> listCameras(Context ctx) {
        List<Info> out = new ArrayList<>();
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = cm.getCameraIdList();
            java.util.Set<String> top = new java.util.HashSet<>(Arrays.asList(ids));
            for (String id : ids) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer f = cc.get(CameraCharacteristics.LENS_FACING);
                int facing = f == null ? -1 : f;
                out.add(new Info(id, null, labelFor(id, f), facing));
                logMax(cm, cc, id);
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    for (String pid : cc.getPhysicalCameraIds()) {
                        if (top.contains(pid)) {
                            continue; // already listed on its own
                        }
                        out.add(new Info(id, pid, lensName(cm, pid, f) + " (lens " + pid + ")", facing));
                    }
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            // no camera service: return what we have
        }
        return out;
    }

    /** Human summary of the biggest 16:9 picture each side's cameras can give, e.g. "Back 2576x1449, Front 3264x1836". */
    public static String describeLimits(Context ctx) {
        int[] bestBack = {0, 0}, bestFront = {0, 0};
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Integer f = cc.get(CameraCharacteristics.LENS_FACING);
                if (map == null || map.getOutputSizes(SurfaceTexture.class) == null) {
                    continue;
                }
                int[] dst = (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) ? bestFront : bestBack;
                for (Size z : map.getOutputSizes(SurfaceTexture.class)) {
                    int w = z.getWidth();
                    int h = Math.min(z.getHeight(), w * 9 / 16);   // 16:9 window inside this size
                    if ((long) w * h > (long) dst[0] * dst[1]) {
                        dst[0] = w;
                        dst[1] = h;
                    }
                }
            }
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        return "Back " + bestBack[0] + "x" + bestBack[1] + ", front " + bestFront[0] + "x" + bestFront[1];
    }

    /** Diagnostics: the largest video-capable size each camera reports. */
    private static void logMax(CameraManager cm, CameraCharacteristics cc, String id) {
        try {
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            if (map != null && map.getOutputSizes(SurfaceTexture.class) != null) {
                for (Size z : map.getOutputSizes(SurfaceTexture.class)) {
                    if (best == null || (long) z.getWidth() * z.getHeight() > (long) best.getWidth() * best.getHeight()) {
                        best = z;
                    }
                }
            }
            boolean p2160 = false;
            try {
                p2160 = android.media.CamcorderProfile.hasProfile(Integer.parseInt(id),
                    android.media.CamcorderProfile.QUALITY_2160P);
            } catch (NumberFormatException ignored) {
            }
            Size pix = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            android.util.Log.i("Camera2Source", "LIST cam " + id + " facing=" + facing + " sensor=" + pix
                + " maxVideoSurface=" + best + " camcorder2160p=" + p2160);
        } catch (RuntimeException e) {
            // diagnostics only
        }
    }

    private static String lensName(CameraManager cm, String physicalId, Integer facing) {
        String side = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "Front" : "Back";
        try {
            CameraCharacteristics pc = cm.getCameraCharacteristics(physicalId);
            float[] fl = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            android.util.SizeF sz = pc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (fl != null && fl.length > 0 && sz != null) {
                double diag = Math.hypot(sz.getWidth(), sz.getHeight());
                double eq = fl[0] * 43.27 / diag; // 35mm-equivalent focal length
                String kind = eq < 20 ? "Ultra-wide" : eq < 40 ? "Wide" : eq < 100 ? "Telephoto" : "Periscope";
                return side + " " + kind;
            }
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        return side + " lens";
    }

    /**
     * Can these cameras be open at the same time? Needs Android 11+ concurrent-camera
     * support; two sub-lenses of one logical camera can never be shown together.
     */
    public static boolean canRunTogether(Context ctx, List<Info> infos) {
        if (infos.size() <= 1) {
            return true;
        }
        java.util.Set<String> logical = new java.util.HashSet<>();
        for (Info i : infos) {
            if (!logical.add(i.id)) {
                return false;
            }
        }
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return false;
        }
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (java.util.Set<String> combo : cm.getConcurrentCameraIds()) {
                if (combo.containsAll(logical)) {
                    return true;
                }
            }
        } catch (CameraAccessException | RuntimeException ignored) {
        }
        return false;
    }

    private void updateRotation() {
        // The SurfaceTexture transform already turns the sensor buffer upright for the
        // device's natural orientation, so only the display/physical rotation remains.
        rotationDegrees = (360 - displayRotation) % 360;
        android.util.Log.i("Camera2Source", "cam " + cameraId + (physicalId != null ? "/" + physicalId : "")
            + " front=" + front + " rotation=" + displayRotation + " -> compensate " + rotationDegrees);
    }

    @Override
    public void onDisplayRotation(int degrees) {
        displayRotation = degrees;
        updateRotation();
    }

    @Override
    public String getLabel() {
        return label;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void start() {
        stopped = false;
        camThread = new HandlerThread("cam-" + cameraId);
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        final CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            Size size = chooseSize(cm);
            logCapabilities(cm, size);
            if ((long) size.getWidth() * size.getHeight() < (long) reqW * reqH * 0.8) {
                Listener l0 = listener;
                if (l0 != null) {
                    l0.onSourceInfo(this, label + " can only deliver " + size.getWidth() + "x" + size.getHeight()
                        + ", so it is scaled up to the stream size. Lower the quality to match.");
                }
            }
            width = size.getWidth();
            height = size.getHeight();
            surfaceTexture.setDefaultBufferSize(width, height);
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (stopped) {
                        camera.close();
                        return;
                    }
                    device = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    device = null;
                    fireError("Camera " + cameraId + " disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    device = null;
                    fireError("Camera " + cameraId + " error " + error
                        + (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ? " (in use)" :
                           error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                               ? " (this device cannot open that many cameras at once)" : ""));
                }
            }, camHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            fireError("Cannot open camera " + cameraId + ": " + e.getMessage());
        }
    }

    private void createSession() {
        try {
            final Surface target = surface;
            if (target == null || device == null) {
                return;
            }
            final CameraCaptureSession.StateCallback cb = new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        if (stopped || device == null) {
                            s.close();
                            return;
                        }
                        session = s;
                        try {
                            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                            b.addTarget(target);
                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                            disableRotateAndCrop(b);
                            Range<Integer> fps = chooseFps();
                            if (fps != null) {
                                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                            }
                            s.setRepeatingRequest(b.build(), null, camHandler);
                        } catch (CameraAccessException | IllegalStateException e) {
                            fireError("Camera " + cameraId + " preview failed: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        fireError("Camera " + cameraId + " could not be configured at "
                            + width + "x" + height + " (another camera may be using the hardware)");
                    }
                };
            if (physicalId != null && android.os.Build.VERSION.SDK_INT >= 28) {
                android.hardware.camera2.params.OutputConfiguration oc =
                    new android.hardware.camera2.params.OutputConfiguration(target);
                oc.setPhysicalCameraId(physicalId);
                device.createCaptureSession(new android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(oc),
                    new java.util.concurrent.Executor() {
                        @Override
                        public void execute(Runnable r) {
                            camHandler.post(r);
                        }
                    }, cb));
            } else {
                device.createCaptureSession(Collections.singletonList(target), cb, camHandler);
            }
        } catch (CameraAccessException | IllegalStateException e) {
            fireError("Camera " + cameraId + " session failed: " + e.getMessage());
        }
    }

    /**
     * Android 12+ may rotate/crop the stream for apps on foldables and tablets
     * (SCALER_ROTATE_AND_CROP). We do our own rotation in the compositor, so ask for
     * the raw sensor orientation to keep the math identical on every device.
     */
    private void disableRotateAndCrop(CaptureRequest.Builder b) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            int[] modes = cm.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES);
            if (modes != null) {
                for (int m : modes) {
                    if (m == CameraCharacteristics.SCALER_ROTATE_AND_CROP_NONE) {
                        b.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
                        return;
                    }
                }
            }
        } catch (CameraAccessException | RuntimeException ignored) {
        }
    }

    /** Diagnostics: what this camera can really deliver, so size problems can be understood. */
    private void logCapabilities(CameraManager cm, Size chosen) {
        try {
            CameraCharacteristics cc = characteristics(cm);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            StringBuilder sb = new StringBuilder();
            if (map != null) {
                Size[] st = map.getOutputSizes(SurfaceTexture.class);
                Size[] rec = map.getOutputSizes(android.media.MediaRecorder.class);
                sb.append("surfaceTexture sizes>=1080p: ").append(bigSizes(st));
                sb.append(" | recorder sizes>=1080p: ").append(bigSizes(rec));
                Size[] hr = map.getHighResolutionOutputSizes(android.graphics.ImageFormat.PRIVATE);
                sb.append(" | highRes(private): ").append(hr == null ? "none" : bigSizes(hr));
            }
            android.util.Size pixel = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            boolean p2160 = false;
            try {
                p2160 = android.media.CamcorderProfile.hasProfile(Integer.parseInt(cameraId),
                    android.media.CamcorderProfile.QUALITY_2160P);
            } catch (NumberFormatException ignored) {
            }
            android.util.Log.i("Camera2Source", "cam " + cameraId + (physicalId != null ? "/" + physicalId : "")
                + ": sensor " + pixel + ", requested " + reqW + "x" + reqH + " -> chose " + chosen
                + ", camcorder 2160p profile=" + p2160 + " :: " + sb);
        } catch (CameraAccessException | RuntimeException e) {
            android.util.Log.w("Camera2Source", "capability log failed", e);
        }
    }

    private static String bigSizes(Size[] sizes) {
        if (sizes == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Size z : sizes) {
            if (z.getWidth() >= 1920) {
                sb.append(z.getWidth()).append('x').append(z.getHeight()).append(' ');
            }
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    private CameraCharacteristics characteristics(CameraManager cm) throws CameraAccessException {
        if (physicalId != null) {
            try {
                return cm.getCameraCharacteristics(physicalId);
            } catch (IllegalArgumentException ignored) {
                // hidden physical id: fall back to the logical camera's capabilities
            }
        }
        return cm.getCameraCharacteristics(cameraId);
    }

    private Size chooseSize(CameraManager cm) throws CameraAccessException {
        CameraCharacteristics cc = characteristics(cm);
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return new Size(reqW, reqH);
        }
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return new Size(reqW, reqH);
        }
        // Prefer: exact match, else same aspect with the largest area <= request, else
        // closest aspect then closest area.
        final float want = (float) reqW / reqH;
        final long wantArea = (long) reqW * reqH;
        List<Size> list = new ArrayList<>(Arrays.asList(sizes));
        Collections.sort(list, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Double.compare(score(a), score(b));
            }

            private double score(Size s) {
                // Judge each mode by the largest picture of the REQUESTED shape it can give
                // (a 4:3 mode cropped to 16:9 still has far more pixels than a plain 1080p mode).
                double effW = Math.min(s.getWidth(), s.getHeight() * (double) want);
                double effH = effW / want;
                double effArea = effW * effH;
                double penalty = Math.abs(Math.log(effArea / wantArea));
                if (effArea < wantArea) {
                    penalty += 0.3;            // short of the request: worse than slightly over
                } else if (effArea > wantArea * 1.5) {
                    penalty += 0.3;            // far beyond the request wastes GPU and heat
                }
                // tiny nudge to prefer a mode that already has the right shape (no crop needed)
                penalty += Math.abs((double) s.getWidth() / s.getHeight() - want) * 0.02;
                return penalty;
            }
        });
        return list.get(0);
    }

    private Range<Integer> chooseFps() {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics cc = characteristics(cm);
            Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges == null || ranges.length == 0) {
                return null;
            }
            Range<Integer> best = null;
            int bestScore = Integer.MAX_VALUE;
            for (Range<Integer> r : ranges) {
                if (r.getUpper() < reqFps) {
                    continue;
                }
                // fixed range at the request is ideal; otherwise the tightest that contains it
                int score = (r.getUpper() - r.getLower()) + Math.abs(r.getUpper() - reqFps) * 4;
                if (r.getLower() <= reqFps && score < bestScore) {
                    best = r;
                    bestScore = score;
                }
            }
            if (best == null) {
                best = ranges[ranges.length - 1];
            }
            return best;
        } catch (CameraAccessException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void stop() {
        stopped = true;
        try {
            if (session != null) {
                session.close();
            }
        } catch (RuntimeException ignored) {
        }
        session = null;
        try {
            if (device != null) {
                device.close();
            }
        } catch (RuntimeException ignored) {
        }
        device = null;
        if (camThread != null) {
            camThread.quitSafely();
            camThread = null;
        }
    }

    private void fireError(String msg) {
        Listener l = listener;
        if (l != null) {
            l.onSourceError(this, msg);
        }
    }
}
