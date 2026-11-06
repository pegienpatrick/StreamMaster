package net.ossrs.yasea.demo;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.seu.magicfilter.utils.MagicFilterType;

import net.ossrs.yasea.SrsCameraView;
import net.ossrs.yasea.SrsEncodeHandler;
import net.ossrs.yasea.SrsPublisher;
import net.ossrs.yasea.SrsRecordHandler;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements RtmpHandler.RtmpListener,
                        SrsRecordHandler.SrsRecordListener, SrsEncodeHandler.SrsEncodeListener {

    private static final String TAG = "Yasea";
    public final static int RC_CAMERA = 100;

    private ImageButton btnPublish;
    private ImageButton btnSwitchCamera;
    private ImageButton btnRecord;
    private ImageButton btnPause;

    // Live recording readout.
    private View recordingChip;
    private ImageView recordingDot;
    private TextView recordingTimer;
    private TextView recordingSize;
    private TextView recordingFormat;
    private TextView status;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private long recordingStartMs;
    private long recordingElapsedMs;   // accumulated across pauses
    private long lastResumeMs;

    private SharedPreferences sp;
    private String rtmpUrl = "rtmp://ossrs.net/" + getRandomAlphaString(3) + '/' + getRandomAlphaDigitString(5);
    // Recorded to the app-specific external dir (no storage permission needed,
    // and direct writes to /sdcard are blocked by scoped storage on API 29+).
    private String recPath;
    private String recFileName;
    private File recDir;

    private SrsPublisher mPublisher;
    private SrsCameraView mCameraView;

    // Requested capture size. This is a *preference*: SrsPublisher clamps it to the
    // largest size the device's hardware encoder can actually encode, so asking for
    // 4K on a device with a 1080p encoder degrades gracefully instead of failing.
    private int mWidth = 3840;
    private int mHeight = 2160;
    private boolean isPermissionGranted = false;

    private boolean isPublishing = false;
    private boolean isRecording = false;
    private boolean isRecordingPaused = false;

    // Restarts the readout while a recording is live.
    private final Runnable readoutTick = new Runnable() {
        @Override
        public void run() {
            updateRecordingReadout();
            if (isRecording && !isRecordingPaused) {
                uiHandler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // response screen rotation event
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);

        requestPermission();
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            List<String> needed = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
            // Only needed to write into Movies/Yasea below API 29; 29+ uses MediaStore.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!needed.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                        needed.toArray(new String[0]), RC_CAMERA);
                return;
            }
        }
        isPermissionGranted = true;
        init();
    }

    //3. 接收申请成功或者失败回调
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_CAMERA) {
            // Require the capture permissions; storage is a nice-to-have, because a
            // denial only means the file stays in the app folder.
            boolean cameraGranted = true;
            boolean audioGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                boolean granted = i < grantResults.length
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED;
                if (Manifest.permission.CAMERA.equals(permissions[i])) {
                    cameraGranted = granted;
                } else if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                    audioGranted = granted;
                }
            }
            if (cameraGranted && audioGranted) {
                isPermissionGranted = true;
                init();
            } else {
                finish();
            }
        }
    }

    /**
     * Builds a fresh, date-stamped mp4 path inside the temporary recording folder.
     * The file lives here only while recording; once finished it is published to
     * Movies/Yasea (see {@link MovieSaver}).
     */
    private String newRecordPath() {
        File dir = recDir != null ? recDir : getFilesDir();
        recFileName = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date()) + ".mp4";
        return new File(dir, recFileName).getAbsolutePath();
    }

    private void init() {
        // restore data.
        sp = getSharedPreferences("Yasea", MODE_PRIVATE);
        rtmpUrl = sp.getString("rtmpUrl", rtmpUrl);

        // Staging area while recording. The finished file is moved into
        // Movies/Yasea by MovieSaver, so nothing is meant to stay here.
        recDir = getExternalCacheDir();
        if (recDir == null) {
            recDir = getCacheDir();
        }
        recPath = newRecordPath();

        // initialize url.
        final EditText efu = (EditText) findViewById(R.id.url);
        efu.setText(rtmpUrl);

        btnPublish = (ImageButton) findViewById(R.id.publish);
        btnSwitchCamera = (ImageButton) findViewById(R.id.swCam);
        btnRecord = (ImageButton) findViewById(R.id.record);
        btnPause = (ImageButton) findViewById(R.id.pause);
        btnPause.setEnabled(false);

        recordingChip = findViewById(R.id.recording_chip);
        recordingDot = (ImageView) findViewById(R.id.recording_dot);
        recordingTimer = (TextView) findViewById(R.id.recording_timer);
        recordingSize = (TextView) findViewById(R.id.recording_size);
        recordingFormat = (TextView) findViewById(R.id.recording_format);
        status = (TextView) findViewById(R.id.status);

        mCameraView = (SrsCameraView) findViewById(R.id.glsurfaceview_camera);

        mPublisher = new SrsPublisher(mCameraView);
        mPublisher.setEncodeHandler(new SrsEncodeHandler(this));
        mPublisher.setRtmpHandler(new RtmpHandler(this));
        mPublisher.setRecordHandler(new SrsRecordHandler(this));
        mPublisher.setVideoHDMode();

        // Set the capture resolution first (clamped to what the encoder supports),
        // then request the frame rate. Order matters: the HD mode helper resets the
        // bitrate, and the bitrate is then scaled to the final resolution.
        mPublisher.setPreviewResolution(mWidth, mHeight);
        mPublisher.setOutputResolution(mWidth, mHeight);
        mPublisher.setVideoFps(30);
        mPublisher.startCamera();

        mCameraView.setCameraCallbacksHandler(new SrsCameraView.CameraCallbacksHandler(){
            @Override
            public void onCameraParameters(Camera.Parameters params) {
                // Legacy callback: only fires on the Camera1 path.
                params.getPictureSize();
            }
        });

        refreshStatus();

        btnPublish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isPublishing) {
                    rtmpUrl = efu.getText().toString();
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putString("rtmpUrl", rtmpUrl);
                    editor.apply();

                    mPublisher.startPublish(rtmpUrl);
                    mPublisher.startCamera();

                    isPublishing = true;
                    btnPublish.setImageResource(R.drawable.ic_publish_stop);
                    btnPause.setEnabled(true);
                } else {
                    mPublisher.stopPublish();
                    // A publish stop must not silently kill a recording: stop both
                    // and reflect that in the UI.
                    if (isRecording) {
                        stopRecording();
                    }
                    isPublishing = false;
                    btnPublish.setImageResource(R.drawable.ic_publish);
                    btnPause.setEnabled(false);
                }
            }
        });

        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (btnPause.getTag() == null || !(Boolean) btnPause.getTag()) {
                    mPublisher.pausePublish();
                    btnPause.setTag(Boolean.TRUE);
                    btnPause.setImageResource(R.drawable.ic_record); // resume glyph slot
                } else {
                    mPublisher.resumePublish();
                    btnPause.setTag(Boolean.FALSE);
                    btnPause.setImageResource(R.drawable.ic_pause);
                }
            }
        });

        btnSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int count = Camera.getNumberOfCameras();
                if (count <= 1) {
                    return;
                }
                int next = (mPublisher.getCameraId() + 1) % count;
                mPublisher.switchCameraFace(next);
                refreshStatus();
            }
        });

        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isRecording) {
                    startRecording();
                } else {
                    stopRecording();
                }
            }
        });
    }

    private void startRecording() {
        // New file per recording, named with the date/time, in the app-specific
        // folder (no storage permission required).
        recPath = newRecordPath();
        if (!mPublisher.startRecord(recPath)) {
            Toast.makeText(getApplicationContext(),
                    "Failed to start recording", Toast.LENGTH_LONG).show();
            return;
        }
        isRecording = true;
        isRecordingPaused = false;
        recordingStartMs = System.currentTimeMillis();
        lastResumeMs = recordingStartMs;
        recordingElapsedMs = 0;

        // Animated state change: the record button pops and turns into a stop square.
        btnRecord.setImageResource(R.drawable.ic_record_stop);
        btnRecord.startAnimation(AnimationUtils.loadAnimation(this, R.anim.record_pop));

        recordingChip.setVisibility(View.VISIBLE);
        refreshStatus();
        uiHandler.post(readoutTick);
    }

    private void stopRecording() {
        mPublisher.stopRecord();
        isRecording = false;
        isRecordingPaused = false;
        uiHandler.removeCallbacks(readoutTick);
        recordingChip.setVisibility(View.GONE);
        btnRecord.setImageResource(R.drawable.ic_record);
        btnRecord.startAnimation(AnimationUtils.loadAnimation(this, R.anim.record_pop));
        refreshStatus();
    }

    /** Refreshes timer, file size and format while a recording is live. */
    private void updateRecordingReadout() {
        long elapsed;
        if (isRecordingPaused) {
            elapsed = recordingElapsedMs;
        } else {
            elapsed = recordingElapsedMs + (System.currentTimeMillis() - lastResumeMs);
        }

        long seconds = elapsed / 1000;
        recordingTimer.setText(String.format(Locale.US, "%02d:%02d",
                seconds / 60, seconds % 60));

        long bytes = mPublisher.getRecordedBytes();
        recordingSize.setText(String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)));

        int w = mPublisher.getCaptureWidth();
        int h = mPublisher.getCaptureHeight();
        int fps = mPublisher.getCaptureFps();
        if (w > 0 && h > 0 && fps > 0) {
            recordingFormat.setText(String.format(Locale.US, "%dx%d %dfps", w, h, fps));
        } else {
            recordingFormat.setText("");
        }

        if (isRecordingPaused) {
            recordingDot.clearAnimation();
            recordingDot.setAlpha(0.3f);
        } else {
            recordingDot.setAlpha(1.0f);
            if (recordingDot.getAnimation() == null) {
                recordingDot.startAnimation(
                        AnimationUtils.loadAnimation(this, R.anim.blink));
            }
        }
    }

    /** Small always-on readout of capture size, fps and encoder mode. */
    private void refreshStatus() {
        if (mPublisher == null) {
            return;
        }
        int w = mPublisher.getCaptureWidth();
        int h = mPublisher.getCaptureHeight();
        int fps = mPublisher.getCaptureFps();
        StringBuilder sb = new StringBuilder();
        if (w > 0 && h > 0) {
            sb.append(w).append('x').append(h);
        }
        if (fps > 0) {
            sb.append(' ').append(fps).append("fps");
        }
        if (w <= 0 && h <= 0) {
            sb.append("starting camera...");
        }
        status.setText(sb.toString());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        MagicFilterType type = null;
        switch (id) {
            case R.id.cool_filter:      type = MagicFilterType.COOL; break;
            case R.id.beauty_filter:    type = MagicFilterType.BEAUTY; break;
            case R.id.early_bird_filter: type = MagicFilterType.EARLYBIRD; break;
            case R.id.evergreen_filter: type = MagicFilterType.EVERGREEN; break;
            case R.id.n1977_filter:     type = MagicFilterType.N1977; break;
            case R.id.nostalgia_filter: type = MagicFilterType.NOSTALGIA; break;
            case R.id.romance_filter:   type = MagicFilterType.ROMANCE; break;
            case R.id.sunrise_filter:   type = MagicFilterType.SUNRISE; break;
            case R.id.sunset_filter:    type = MagicFilterType.SUNSET; break;
            case R.id.tender_filter:    type = MagicFilterType.TENDER; break;
            case R.id.toast_filter:     type = MagicFilterType.TOASTER2; break;
            case R.id.valencia_filter:  type = MagicFilterType.VALENCIA; break;
            case R.id.walden_filter:    type = MagicFilterType.WALDEN; break;
            case R.id.warm_filter:      type = MagicFilterType.WARM; break;
            case R.id.original_filter:  type = MagicFilterType.NONE; break;
            default:
                return super.onOptionsItemSelected(item);
        }

        // The legacy camera path applies filters live, without a restart.
        mPublisher.switchCameraFilter(type);
        setTitle(item.getTitle());
        refreshStatus();

        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(mPublisher != null && mPublisher.getCamera() == null && isPermissionGranted){
            //if the camera was busy and available again
            mPublisher.startCamera();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPublisher == null) {
            return;
        }
        btnPublish.setEnabled(true);
        mPublisher.resumeRecord();
        if (isRecording) {
            isRecordingPaused = false;
            lastResumeMs = System.currentTimeMillis();
            uiHandler.removeCallbacks(readoutTick);
            uiHandler.post(readoutTick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mPublisher == null) {
            return;
        }
        // Pausing the activity must not lose the recording: accumulate elapsed time
        // and stop the readout until we resume.
        mPublisher.pauseRecord();
        if (isRecording) {
            recordingElapsedMs += System.currentTimeMillis() - lastResumeMs;
            isRecordingPaused = true;
        }
        uiHandler.removeCallbacks(readoutTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(readoutTick);
        if (mPublisher != null) {
            mPublisher.stopPublish();
            mPublisher.stopRecord();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPublisher.stopEncode();
        mPublisher.stopRecord();
        isRecording = false;
        isRecordingPaused = false;
        recordingChip.setVisibility(View.GONE);
        btnRecord.setImageResource(R.drawable.ic_record);
        mPublisher.setScreenOrientation(newConfig.orientation);
        mPublisher.startCamera();
        if (isPublishing) {
            mPublisher.startEncode();
        }
    }

    private static String getRandomAlphaString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    private static String getRandomAlphaDigitString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    private void handleException(Exception e) {
        try {
            Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            mPublisher.stopPublish();
            mPublisher.stopRecord();
            isPublishing = false;
            isRecording = false;
            isRecordingPaused = false;
            uiHandler.removeCallbacks(readoutTick);
            recordingChip.setVisibility(View.GONE);
            btnPublish.setImageResource(R.drawable.ic_publish);
            btnRecord.setImageResource(R.drawable.ic_record);
            btnPause.setEnabled(false);
            refreshStatus();
        } catch (Exception e1) {
            //
        }
    }

    // Implementation of SrsRtmpListener.

    @Override
    public void onRtmpConnecting(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpConnected(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpVideoStreaming() {
    }

    @Override
    public void onRtmpAudioStreaming() {
    }

    @Override
    public void onRtmpStopped() {
        Toast.makeText(getApplicationContext(), "Stopped", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpDisconnected() {
        Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRtmpVideoFpsChanged(double fps) {
        Log.i(TAG, String.format("Output Fps: %f", fps));
    }

    @Override
    public void onRtmpVideoBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("Video bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("Video bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpAudioBitrateChanged(double bitrate) {
        int rate = (int) bitrate;
        if (rate / 1000 > 0) {
            Log.i(TAG, String.format("Audio bitrate: %f kbps", bitrate / 1000));
        } else {
            Log.i(TAG, String.format("Audio bitrate: %d bps", rate));
        }
    }

    @Override
    public void onRtmpSocketException(SocketException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    @Override
    public void onRtmpIllegalStateException(IllegalStateException e) {
        handleException(e);
    }

    // Implementation of SrsRecordHandler.

    @Override
    public void onRecordPause() {
        // The activity drives this itself; no toast to avoid noise on every pause.
    }

    @Override
    public void onRecordResume() {
    }

    @Override
    public void onRecordStarted(String msg) {
        Log.i(TAG, "Recording to " + msg);
    }

    @Override
    public void onRecordFinished(String msg) {
        // The muxer has closed the file, so it is complete and playable now. Move it
        // from the temp folder into Movies/Yasea so it is easy to find and survives
        // uninstalling the app. Copying a large file takes time, so keep it off the
        // UI thread.
        final File temp = new File(msg);
        final String displayName = recFileName != null ? recFileName : temp.getName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String shown = MovieSaver.publish(
                        getApplicationContext(), temp, displayName);
                final long bytes = temp.exists() ? temp.length() : 0;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (shown != null) {
                            Toast.makeText(getApplicationContext(),
                                    "Saved to " + shown, Toast.LENGTH_LONG).show();
                        } else {
                            // Publishing failed, but the recording itself is intact in
                            // the temp folder: say where, instead of claiming success.
                            String size = String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
                            Toast.makeText(getApplicationContext(),
                                    "Saved to " + temp.getAbsolutePath() + " (" + size
                                            + "); could not move to Movies/Yasea",
                                    Toast.LENGTH_LONG).show();
                        }
                        refreshStatus();
                    }
                });
            }
        }, "MovieSaver").start();
    }

    @Override
    public void onRecordIOException(IOException e) {
        handleException(e);
    }

    @Override
    public void onRecordIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

    // Implementation of SrsEncodeHandler.

    @Override
    public void onNetworkWeak() {
        Toast.makeText(getApplicationContext(), "Network weak", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onNetworkResume() {
        Toast.makeText(getApplicationContext(), "Network resume", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEncodeIllegalArgumentException(IllegalArgumentException e) {
        handleException(e);
    }

}
