package net.ossrs.yasea;

import android.hardware.Camera;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.util.Log;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.seu.magicfilter.utils.MagicFilterType;

import java.io.File;

/**
 * Created by Leo Ma on 2016/7/25.
 */
public class SrsPublisher {

    private static final String TAG = "SrsPublisher";

    private static AudioRecord mic;
    private static AcousticEchoCanceler aec;
    private static AutomaticGainControl agc;
    private byte[] mPcmBuffer = new byte[4096];
    private Thread aworker;

    private SrsCameraView mCameraView;

    private boolean sendVideoOnly = false;
    private boolean sendAudioOnly = false;
    private int videoFrameCount;
    private long lastTimeMillis;
    private double mSamplingFps;

    private SrsFlvMuxer mFlvMuxer;
    private SrsMp4Muxer mMp4Muxer;
    private SrsEncoder mEncoder;

    // Whether the encoder/microphone are currently running, and whether an RTMP
    // publish is active. Recording needs the first but not the second.
    private volatile boolean isEncoding = false;
    private volatile boolean isPublishing = false;

    public SrsPublisher(SrsCameraView view) {
        mCameraView = view;
        mCameraView.setPreviewCallback(new SrsCameraView.PreviewCallback() {
            @Override
            public void onGetRgbaFrame(byte[] data, int width, int height) {
                calcSamplingFps();
                if (!sendAudioOnly) {
                    mEncoder.onGetRgbaFrame(data, width, height);
                }
            }
        });
    }

    private void calcSamplingFps() {
        // Calculate sampling FPS
        if (videoFrameCount == 0) {
            lastTimeMillis = System.nanoTime() / 1000000;
            videoFrameCount++;
        } else {
            if (++videoFrameCount >= SrsEncoder.vGopSize) {
                long diffTimeMillis = System.nanoTime() / 1000000 - lastTimeMillis;
                mSamplingFps = (double) videoFrameCount * 1000 / diffTimeMillis;
                videoFrameCount = 0;
            }
        }
    }

    public void startCamera() {
        mCameraView.startCamera();
    }

    public void stopCamera() {
        mCameraView.stopCamera();
    }

    public void startAudio() {
        mic = mEncoder.chooseAudioRecord();
        if (mic == null) {
            return;
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(mic.getAudioSessionId());
            if (aec != null) {
                //aec.setEnabled(true); //android 11 issue low volume
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(mic.getAudioSessionId());
            if (agc != null) {
                agc.setEnabled(true);
            }
        }

        aworker = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                mic.startRecording();
                while (!Thread.interrupted()) {
                    if (sendVideoOnly) {
                        mEncoder.onGetPcmFrame(mPcmBuffer, mPcmBuffer.length);
                        try {
                            // This is trivial...
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            break;
                        }
                    } else {
                        int size = mic.read(mPcmBuffer, 0, mPcmBuffer.length);
                        if (size > 0) {
                            mEncoder.onGetPcmFrame(mPcmBuffer, size);
                        }
                    }
                }
            }
        });
        aworker.start();
    }

    public void stopAudio() {
        if (aworker != null) {
            aworker.interrupt();
            try {
                aworker.join();
            } catch (InterruptedException e) {
                aworker.interrupt();
            }
            aworker = null;
        }

        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.stop();
            mic.release();
            mic = null;
        }

        if (aec != null) {
            aec.setEnabled(false);
            aec.release();
            aec = null;
        }

        if (agc != null) {
            agc.setEnabled(false);
            agc.release();
            agc = null;
        }
    }

    public void startEncode() {
        if (!mEncoder.start()) {
            return;
        }
        isEncoding = true;

        mCameraView.enableEncoding();

        startAudio();
    }

    public void stopEncode() {
        stopEncodeInternal();
    }
    public void pauseEncode(){
        stopAudio();
        mCameraView.disableEncoding();
        mCameraView.stopTorch();
    }
    private void resumeEncode() {
        startAudio();
        mCameraView.enableEncoding();
    }

    public void startPublish(String rtmpUrl) {
        if (mFlvMuxer != null) {
            isPublishing = true;
            mFlvMuxer.start(rtmpUrl);
            mFlvMuxer.setVideoResolution(mEncoder.getOutputWidth(), mEncoder.getOutputHeight());
            // The encoder may already be running for a standalone recording.
            if (!isEncoding) {
                startEncode();
            }
        }
    }
    public void resumePublish(){
        if(mFlvMuxer != null) {
            mEncoder.resume();
            resumeEncode();
        }
    }

    public void stopPublish() {
        if (mFlvMuxer != null) {
            // A recording must survive the publish ending: only tear the encoder
            // down when nothing else still needs it.
            if (!isRecording()) {
                stopEncodeInternal();
            } else {
                stopAudio();
            }
            isPublishing = false;
            mFlvMuxer.stop();
        }
    }

    public void pausePublish(){
        if (mFlvMuxer != null) {
            mEncoder.pause();
            pauseEncode();
        }
    }
    public boolean startRecord(String recPath) {
        if (mMp4Muxer == null) {
            return false;
        }
        // Make sure the directory exists, otherwise the open fails and we report it.
        File recFile = new File(recPath);
        File parent = recFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }

        // Recording does not require publishing: start the encoder (and therefore
        // the camera feed) on its own when nothing is already running.
        boolean startedEncoder = false;
        if (!isEncoding) {
            if (!mEncoder.start()) {
                return false;
            }
            isEncoding = true;
            startedEncoder = true;

            mCameraView.enableEncoding();
            startAudio();
            mCameraView.startCamera();
        }

        boolean started = mMp4Muxer.record(recFile);
        if (started && mEncoder != null) {
            mEncoder.setRecording(true);
        } else if (!started && startedEncoder) {
            // Undo the standalone start so a failed record does not leave the
            // encoder and microphone running.
            stopEncodeInternal();
        }
        return started;
    }

    public void stopRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.stop();
        }
        if (mEncoder != null) {
            mEncoder.setRecording(false);
        }
        // If the encoder was only running because of this recording, shut it down.
        if (isEncoding && !isPublishing) {
            stopEncodeInternal();
        }
    }

    /** Tears the encoder, camera and microphone down and clears the running flag. */
    private void stopEncodeInternal() {
        stopAudio();
        stopCamera();
        if (mEncoder != null) {
            mEncoder.stop();
        }
        isEncoding = false;
    }

    /** Bytes written by the mp4 muxer so far, for the live recording readout. */
    public long getRecordedBytes() {
        return mMp4Muxer != null ? mMp4Muxer.getRecordedBytes() : 0;
    }

    public boolean isRecording() {
        return mMp4Muxer != null && mMp4Muxer.isRecording();
    }

    public boolean isRecordingPaused() {
        return mMp4Muxer != null && mMp4Muxer.isPaused();
    }

    /** Whether the encoder/microphone are currently running. */
    public boolean isEncoding() {
        return isEncoding;
    }

    /** Capture resolution the encoder is configured for. */
    public int getCaptureWidth() {
        return mEncoder != null ? mEncoder.getOutputWidth() : 0;
    }

    public int getCaptureHeight() {
        return mEncoder != null ? mEncoder.getOutputHeight() : 0;
    }

    public int getCaptureFps() {
        return mEncoder != null ? mEncoder.getVideoFps() : 0;
    }

    public void pauseRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.pause();
        }
    }

    public void resumeRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.resume();
        }
    }

    public boolean isAllFramesUploaded(){
        return mFlvMuxer.getVideoFrameCacheNumber().get() == 0;
    }

    public int getVideoFrameCacheCount(){
        if(mFlvMuxer != null) {
            return mFlvMuxer.getVideoFrameCacheNumber().get();
        }
        return 0;
    }

    public void switchToSoftEncoder() {
        mEncoder.switchToSoftEncoder();
    }

    public void switchToHardEncoder() {
        mEncoder.switchToHardEncoder();
    }

    public boolean isSoftEncoder() {
        return mEncoder.isSoftEncoder();
    }

    public int getPreviewWidth() {
        return mEncoder.getPreviewWidth();
    }

    public int getPreviewHeight() {
        return mEncoder.getPreviewHeight();
    }

    public double getmSamplingFps() {
        return mSamplingFps;
    }

    public int getCameraId() {
        return mCameraView.getCameraId();
    }
    
    public Camera getCamera() {
        return mCameraView.getCamera();
    }     

    public void setPreviewResolution(int width, int height) {
        // Clamp to a size the hardware encoder can actually encode. Requesting the
        // camera's resolution blindly makes MediaCodec.configure() throw on devices
        // whose encoder is smaller (e.g. an 8K camera with a 4K encoder).
        int[] out = chooseEncodableSize(width, height);
        width = out[0];
        height = out[1];

        int resolution[] = mCameraView.setPreviewResolution(width, height);
        mEncoder.setPreviewResolution(resolution[0], resolution[1]);
    }

    /**
     * Returns the requested size, or the largest encoder-supported size that is no
     * larger than it. Falls back to the request when capabilities cannot be read.
     */
    private int[] chooseEncodableSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return new int[] { width, height };
        }
        int landscapeW = Math.max(width, height);
        int landscapeH = Math.min(width, height);
        if (SrsEncoder.canEncodeSize(landscapeW, landscapeH)) {
            return new int[] { width, height };
        }

        int[] maxSize = SrsEncoder.getMaxEncodingSize();
        if (maxSize[0] <= 0 || maxSize[1] <= 0) {
            return new int[] { width, height };
        }
        Log.i(TAG, String.format("encoder cannot do %dx%d, using %dx%d",
                landscapeW, landscapeH, maxSize[0], maxSize[1]));
        // preserve the requested orientation
        return width <= height
                ? new int[] { maxSize[1], maxSize[0] }
                : new int[] { maxSize[0], maxSize[1] };
    }

    /**
     * Sets the capture frame rate for the encoder. Call before startPublish/startEncode.
     */
    public void setVideoFps(int fps) {
        mEncoder.setVideoFps(fps);
    }

    public int getVideoFps() {
        return mEncoder.getVideoFps();
    }

    public void setOutputResolution(int width, int height) {
        if (width <= height) {
            mEncoder.setPortraitResolution(width, height);
        } else {
            mEncoder.setLandscapeResolution(width, height);
        }
    }

    public void setScreenOrientation(int orientation) {
        mCameraView.setPreviewOrientation(orientation);
        mEncoder.setScreenOrientation(orientation);
    }

    public void setVideoHDMode() {
        mEncoder.setVideoHDMode();
    }

    public void setVideoSmoothMode() {
        mEncoder.setVideoSmoothMode();
    }

    public void setSendVideoOnly(boolean flag) {
        if (mic != null) {
            if (flag) {
                mic.stop();
                mPcmBuffer = new byte[4096];
            } else {
                mic.startRecording();
            }
        }
        sendVideoOnly = flag;
    }

    public void setSendAudioOnly(boolean flag) {
        sendAudioOnly = flag;
    }

    public boolean switchCameraFilter(MagicFilterType type) {
        return mCameraView.setFilter(type);
    }

    public void switchCameraFace(int id) {
        
        if (mEncoder != null && mEncoder.isEnabled()) {
            mEncoder.pause();
        }        
        
        mCameraView.stopCamera();
        mCameraView.setCameraId(id);
        if (id == 0) {
            mEncoder.setCameraBackFace();
        } else {
            mEncoder.setCameraFrontFace();
        }
        if (mEncoder != null && mEncoder.isEnabled()) {
            mCameraView.enableEncoding();
        }
        mCameraView.startCamera();
        
        if (mEncoder != null && mEncoder.isEnabled()) {
            mEncoder.resume();
        }        
        
    }

    public void setRtmpHandler(RtmpHandler handler) {
        mFlvMuxer = new SrsFlvMuxer(handler);
        if (mEncoder != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
    }

    public void setRecordHandler(SrsRecordHandler handler) {
        mMp4Muxer = new SrsMp4Muxer(handler);
        if (mEncoder != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }

    public void setEncodeHandler(SrsEncodeHandler handler) {
        mEncoder = new SrsEncoder(handler);
        if (mFlvMuxer != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
        if (mMp4Muxer != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }
}
