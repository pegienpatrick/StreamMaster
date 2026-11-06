package net.ossrs.yasea.engine;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hardware H.264 encoder fed straight from the GPU through an input Surface, plus an
 * AAC encoder fed from the microphone. Both stamp samples with microseconds since
 * {@code baseUs} so the muxers see a shared timeline.
 */
public final class MediaEncoders {
    private static final String TAG = "MediaEncoders";
    public static final String VCODEC = "video/avc";
    public static final String ACODEC = "audio/mp4a-latm";
    public static final int SAMPLE_RATE = 44100;
    public static final int AUDIO_BITRATE = 128 * 1024;

    /** Receives encoded samples on encoder threads. */
    public interface Sink {
        void onVideoFormat(MediaFormat format);

        void onAudioFormat(MediaFormat format);

        void onVideoSample(ByteBuffer data, MediaCodec.BufferInfo info);

        void onAudioSample(ByteBuffer data, MediaCodec.BufferInfo info);

        void onEncoderError(String message);
    }

    private final Sink sink;
    private final long baseUs;

    private MediaCodec vcodec;
    private Surface inputSurface;
    private Thread vthread;
    private final AtomicBoolean vrun = new AtomicBoolean();

    private MediaCodec acodec;
    private AudioRecord mic;
    private AutomaticGainControl agc;
    private Thread athread;
    private final AtomicBoolean arun = new AtomicBoolean();
    private volatile boolean muted;
    private boolean micMissing;
    private int audioChannels = 2;
    private volatile float micLevel; // 0..1 peak of the last buffer, for the UI meter

    public MediaEncoders(Sink sink, long baseUs) {
        this.sink = sink;
        this.baseUs = baseUs;
    }

    // ------------------------------------------------------------ video

    /** Configure + start the video encoder and return its GPU input surface, or null on failure. */
    public Surface startVideo(int width, int height, int fps, int bitrate) {
        try {
            vcodec = MediaCodec.createEncoderByType(VCODEC);
            MediaFormat f = MediaFormat.createVideoFormat(VCODEC, width, height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            try {
                vcodec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException firstTry) {
                // Some encoders reject High profile / CBR: retry with the plain defaults.
                Log.w(TAG, "configure with High/CBR failed, retrying baseline settings", firstTry);
                vcodec.release();
                vcodec = MediaCodec.createEncoderByType(VCODEC);
                f.removeKey(MediaFormat.KEY_PROFILE);
                f.removeKey(MediaFormat.KEY_BITRATE_MODE);
                vcodec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            inputSurface = vcodec.createInputSurface();
            vcodec.start();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "video encoder start failed", e);
            sink.onEncoderError("Video encoder failed for " + width + "x" + height + "@" + fps
                + ": " + e.getMessage());
            stopVideo();
            return null;
        }
        vrun.set(true);
        vthread = new Thread(new Runnable() {
            @Override
            public void run() {
                drainVideo();
            }
        }, "venc-drain");
        vthread.start();
        return inputSurface;
    }

    private void drainVideo() {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (vrun.get()) {
            int idx;
            try {
                idx = vcodec.dequeueOutputBuffer(bi, 100_000);
            } catch (RuntimeException e) {
                if (vrun.get()) {
                    sink.onEncoderError("Video encoder died: " + e.getMessage());
                }
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sink.onVideoFormat(vcodec.getOutputFormat());
            } else if (idx >= 0) {
                ByteBuffer bb = vcodec.getOutputBuffer(idx);
                if (bb != null && bi.size > 0) {
                    bb.position(bi.offset);
                    bb.limit(bi.offset + bi.size);
                    // rebase to a shared zero-based timeline (config buffers keep 0)
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        bi.presentationTimeUs = Math.max(0, bi.presentationTimeUs - baseUs);
                    }
                    sink.onVideoSample(bb, bi);
                }
                vcodec.releaseOutputBuffer(idx, false);
            }
        }
    }

    /** Change the target bitrate live (adaptive bitrate). */
    public void setVideoBitrate(int bitrate) {
        if (vcodec != null) {
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
                vcodec.setParameters(b);
            } catch (RuntimeException e) {
                Log.w(TAG, "setVideoBitrate", e);
            }
        }
    }

    /** Ask for an IDR as soon as possible (new viewer / late-joining output). */
    public void requestKeyFrame() {
        if (vcodec != null) {
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                vcodec.setParameters(b);
            } catch (RuntimeException e) {
                Log.w(TAG, "requestKeyFrame", e);
            }
        }
    }

    public void stopVideo() {
        vrun.set(false);
        if (vthread != null) {
            try {
                vthread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            vthread = null;
        }
        if (vcodec != null) {
            try {
                vcodec.stop();
            } catch (RuntimeException ignored) {
            }
            vcodec.release();
            vcodec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
    }

    // ------------------------------------------------------------ audio

    @SuppressLint("MissingPermission")
    public boolean startAudio() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 4, 16384);
            mic = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            audioChannels = 2;
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                mic.release();
                mic = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                audioChannels = 1;
                if (mic.getState() != AudioRecord.STATE_INITIALIZED) {
                    mic.release();
                    mic = null;
                    // Keep a (silent) audio track so players and the mp4 muxer stay happy.
                    audioChannels = 1;
                    micMissing = true;
                    sink.onEncoderError("Microphone unavailable (permission denied or in use): streaming silent audio");
                }
            }
            if (mic != null && AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(mic.getAudioSessionId());
                if (agc != null) {
                    agc.setEnabled(true);
                }
            }
            acodec = MediaCodec.createEncoderByType(ACODEC);
            MediaFormat af = MediaFormat.createAudioFormat(ACODEC, SAMPLE_RATE, audioChannels);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            acodec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            acodec.start();
            if (mic != null) {
                mic.startRecording();
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "audio start failed", e);
            sink.onEncoderError("Audio encoder failed: " + e.getMessage());
            stopAudio();
            return false;
        }
        arun.set(true);
        athread = new Thread(new Runnable() {
            @Override
            public void run() {
                audioLoop();
            }
        }, "aenc");
        athread.setPriority(Thread.MAX_PRIORITY);
        athread.start();
        return true;
    }

    private void audioLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        byte[] pcm = new byte[4096];
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long samplesSent = 0;
        long firstPts = -1;
        while (arun.get()) {
            int n;
            if (mic == null) {
                // paced silence: 4096 bytes of mono 16-bit = 2048 samples
                n = pcm.length;
                java.util.Arrays.fill(pcm, (byte) 0);
                try {
                    Thread.sleep(n / (2 * audioChannels) * 1000L / SAMPLE_RATE);
                } catch (InterruptedException e) {
                    return;
                }
            } else {
                n = mic.read(pcm, 0, pcm.length);
            }
            if (n <= 0) {
                continue;
            }
            if (muted) {
                java.util.Arrays.fill(pcm, 0, n, (byte) 0);
            }
            updateLevel(pcm, n);
            int in = acodec.dequeueInputBuffer(20_000);
            if (in >= 0) {
                ByteBuffer ib = acodec.getInputBuffer(in);
                ib.clear();
                ib.put(pcm, 0, n);
                if (firstPts < 0) {
                    firstPts = System.nanoTime() / 1000 - baseUs;
                }
                // derive pts from the sample clock: immune to read() jitter
                long pts = firstPts + samplesSent * 1_000_000L / SAMPLE_RATE;
                acodec.queueInputBuffer(in, 0, n, pts, 0);
                samplesSent += n / (2 * audioChannels);
            }
            for (; ; ) {
                int out = acodec.dequeueOutputBuffer(bi, 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.onAudioFormat(acodec.getOutputFormat());
                } else if (out >= 0) {
                    ByteBuffer ob = acodec.getOutputBuffer(out);
                    if (ob != null && bi.size > 0) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        sink.onAudioSample(ob, bi);
                    }
                    acodec.releaseOutputBuffer(out, false);
                } else {
                    break;
                }
            }
        }
    }

    private void updateLevel(byte[] pcm, int n) {
        int peak = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs(v));
        }
        micLevel = peak / 32768f;
    }

    public void setMuted(boolean m) {
        muted = m;
    }

    public boolean isMuted() {
        return muted;
    }

    public float getMicLevel() {
        return micLevel;
    }

    public int getAudioChannels() {
        return audioChannels;
    }

    public void stopAudio() {
        arun.set(false);
        if (athread != null) {
            try {
                athread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            athread = null;
        }
        if (mic != null) {
            try {
                mic.stop();
            } catch (RuntimeException ignored) {
            }
            mic.release();
            mic = null;
        }
        if (agc != null) {
            agc.release();
            agc = null;
        }
        if (acodec != null) {
            try {
                acodec.stop();
            } catch (RuntimeException ignored) {
            }
            acodec.release();
            acodec = null;
        }
    }

    public void stopAll() {
        stopAudio();
        stopVideo();
    }
}
