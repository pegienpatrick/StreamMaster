package net.ossrs.yasea.engine;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.github.faucamp.simplertmp.RtmpHandler;

import net.ossrs.yasea.SrsFlvMuxer;
import net.ossrs.yasea.SrsMp4Muxer;
import net.ossrs.yasea.SrsRecordHandler;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the whole capture -> composite -> encode -> (RTMP | MP4) pipeline.
 * Independent of any Activity: it is meant to live inside a foreground service so
 * streaming and recording continue in the background and with the screen off.
 *
 * Create and call it from the main thread (the muxer handlers bind to that Looper).
 */
public final class StreamEngine implements MediaEncoders.Sink, VideoSource.Listener,
        Compositor.ErrorListener, RtmpHandler.RtmpListener, SrsRecordHandler.SrsRecordListener {

    private static final String TAG = "StreamEngine";

    public enum StreamState { IDLE, CONNECTING, LIVE, RECONNECTING, ERROR }

    public interface Listener {
        void onStreamState(StreamState state, String message);

        void onRecordState(boolean recording, String path, String message);

        void onEngineMessage(String message);

        void onSourcesChanged();
    }

    public static final class Stats {
        public int renderFps;
        public int videoBitrateKbps;      // measured, last second
        public int targetBitrateKbps;     // current encoder target
        public int cachedFrames;          // frames waiting to be sent (network backlog)
        public long droppedFrames;
        public long streamElapsedMs;
        public long recordElapsedMs;
        public long recordBytes;
        public float micLevel;
        public boolean micMuted;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Compositor compositor = new Compositor();
    private final List<VideoSource> sources = new ArrayList<>();
    private final RtmpHandler rtmpHandler = new RtmpHandler(this);
    private final SrsRecordHandler recordHandler = new SrsRecordHandler(this);
    private final SrsFlvMuxer flv = new SrsFlvMuxer(rtmpHandler);
    private final SrsMp4Muxer mp4 = new SrsMp4Muxer(recordHandler);

    // ---- config ----
    private int outW = 1280, outH = 720, fps = 30, targetBitrate = 3_000_000;
    private boolean adaptiveBitrate = true;

    // ---- pipeline ----
    private final Object pipeLock = new Object();
    private MediaEncoders encoders;
    private long baseUs;
    private int currentBitrate;
    private MediaFormat videoFormat, audioFormat;
    private ByteBuffer videoConfig, audioConfig;
    private MediaCodec.BufferInfo videoConfigInfo, audioConfigInfo;

    // flv output
    private String streamUrl;
    private boolean wantStream;
    private boolean flvAttached;
    private int flvVideoTrack, flvAudioTrack;
    private long streamStartMs;
    private int reconnectAttempt;
    private boolean waitingForKeyFrame;
    private StreamState streamState = StreamState.IDLE;

    // mp4 output
    private File recordFile;
    private boolean wantRecord;
    private boolean mp4Attached;
    private int mp4VideoTrack, mp4AudioTrack;
    private long recordStartMs;

    // stats
    private volatile long droppedFrames;
    private long bytesThisWindow;
    private long windowStartMs;
    private volatile int measuredKbps;
    private int calmWindows;
    private boolean released;

    public StreamEngine(Context ctx) {
        this.context = ctx.getApplicationContext();
        compositor.setErrorListener(this);
        compositor.setOutputSize(outW, outH);
        compositor.setFps(fps);
        compositor.start();
        main.postDelayed(statsTick, 1000);
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ================================================== configuration

    public void setVideoConfig(int width, int height, int fps, int bitrate) {
        if (isPipelineRunning()) {
            return; // cannot change while encoding
        }
        this.outW = width & ~1;
        this.outH = height & ~1;
        this.fps = fps;
        this.targetBitrate = bitrate;
        compositor.setOutputSize(outW, outH);
        compositor.setFps(fps);
        for (Layer l : layers) {
            if (l instanceof TextLayer) {
                ((TextLayer) l).recompute(outW, outH);
            } else if (l instanceof BitmapLayer) {
                ((BitmapLayer) l).onOutputSize(outW, outH);
            }
        }
    }

    public void setAdaptiveBitrate(boolean on) {
        adaptiveBitrate = on;
    }

    public int getOutputWidth() {
        return outW;
    }

    public int getOutputHeight() {
        return outH;
    }

    // ================================================== sources / layout

    public Compositor getCompositor() {
        return compositor;
    }

    // ---- scene: ordered layers, bottom to top ----
    private final java.util.concurrent.CopyOnWriteArrayList<Layer> layers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean autoArrange = true;

    public synchronized void addSource(VideoSource s) {
        if (sources.contains(s)) {
            return;
        }
        s.setListener(this);
        sources.add(s);
        compositor.addSource(s);
        layers.add(new SourceLayer(s));
        if (autoArrange) {
            arrangeAuto();
        }
        commitScene();
    }

    public synchronized void removeSource(VideoSource s) {
        if (sources.remove(s)) {
            compositor.removeSource(s);
            for (Layer l : layers) {
                if (l instanceof SourceLayer && ((SourceLayer) l).source == s) {
                    layers.remove(l);
                }
            }
            if (autoArrange) {
                arrangeAuto();
            }
            commitScene();
        }
    }

    /**
     * Swap one source for another in the same place and with the same geometry, closing
     * the old one first so devices that cannot open two cameras at once still switch.
     */
    public void replaceSource(VideoSource old, VideoSource replacement) {
        SourceLayer oldLayer = null;
        int sourceIndex;
        synchronized (this) {
            sourceIndex = sources.indexOf(old);
            if (sourceIndex < 0) {
                addSource(replacement);
                return;
            }
            sources.remove(old);
            for (Layer l : layers) {
                if (l instanceof SourceLayer && ((SourceLayer) l).source == old) {
                    oldLayer = (SourceLayer) l;
                }
            }
        }
        compositor.removeSource(old);
        compositor.flush();   // old camera is released once this returns
        synchronized (this) {
            replacement.setListener(this);
            sources.add(Math.min(sourceIndex, sources.size()), replacement);
            SourceLayer nl = new SourceLayer(replacement);
            if (oldLayer != null) {
                nl.setRect(oldLayer.x, oldLayer.y, oldLayer.w, oldLayer.h);
                nl.fit = oldLayer.fit;
                nl.cropL = oldLayer.cropL;
                nl.cropT = oldLayer.cropT;
                nl.cropR = oldLayer.cropR;
                nl.cropB = oldLayer.cropB;
                nl.opacity = oldLayer.opacity;
                nl.visible = oldLayer.visible;
                int at = layers.indexOf(oldLayer);
                layers.set(at, nl);
            } else {
                layers.add(nl);
            }
        }
        compositor.addSource(replacement);
        commitScene();
    }

    public synchronized List<VideoSource> getSources() {
        return new ArrayList<>(sources);
    }

    public synchronized VideoSource findSource(String id) {
        for (VideoSource s : sources) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    /** The scene layer that shows this source, or null. */
    public SourceLayer layerFor(VideoSource src) {
        for (Layer l : layers) {
            if (l instanceof SourceLayer && ((SourceLayer) l).source == src) {
                return (SourceLayer) l;
            }
        }
        return null;
    }

    /** Re-order the scene (bottom to top). Layers not listed keep their relative order on top. */
    public void setLayerOrder(List<Layer> ordered) {
        List<Layer> rest = new ArrayList<>();
        for (Layer l : layers) {
            if (!ordered.contains(l)) {
                rest.add(l);
            }
        }
        layers.clear();
        for (Layer l : ordered) {
            if (!layers.contains(l)) {
                layers.add(l);
            }
        }
        layers.addAll(rest);
        commitScene();
    }

    /** Remove every source and layer (used when switching profiles). */
    public void clearScene() {
        for (VideoSource v : getSources()) {
            removeSource(v);
        }
        layers.clear();
        autoArrange = true;
        commitScene();
    }

    public boolean isManualLayout() {
        return !autoArrange;
    }

    /** Layers bottom to top. */
    public List<Layer> getLayers() {
        return new ArrayList<>(layers);
    }

    public void addLayer(Layer l) {
        if (l instanceof BitmapLayer) {
            if (l instanceof TextLayer) {
                ((TextLayer) l).recompute(outW, outH);
            } else {
                ((BitmapLayer) l).onOutputSize(outW, outH);
            }
        }
        layers.add(l);
        commitScene();
    }

    public void removeLayer(Layer l) {
        if (l instanceof SourceLayer) {
            removeSource(((SourceLayer) l).source);
            return;
        }
        layers.remove(l);
        commitScene();
    }

    public void moveLayer(Layer l, int delta) {
        int i = layers.indexOf(l);
        if (i < 0) {
            return;
        }
        int j = Math.max(0, Math.min(layers.size() - 1, i + delta));
        if (j != i) {
            layers.remove(i);
            layers.add(j, l);
            commitScene();
        }
    }

    public void bringToFront(Layer l) {
        moveLayer(l, layers.size());
    }

    public void sendToBack(Layer l) {
        moveLayer(l, -layers.size());
    }

    /** The user placed something by hand: stop re-arranging sources automatically. */
    public void markManualLayout() {
        autoArrange = false;
    }

    /** Layer content/geometry edited: push the (re-ordered) list and refresh listeners. */
    public void commitScene() {
        compositor.setLayers(layers);
        notifySources();
    }

    private List<SourceLayer> sourceLayers() {
        List<SourceLayer> out = new ArrayList<>();
        for (Layer l : layers) {
            if (l instanceof SourceLayer) {
                out.add((SourceLayer) l);
            }
        }
        return out;
    }

    private void arrangeAuto() {
        int n = sourceLayers().size();
        arrange(n <= 1 ? LayoutMode.SINGLE : n == 2 ? LayoutMode.PIP : LayoutMode.GRID_2X2);
    }

    /** Place the source layers, in order, into a preset arrangement. */
    public void arrange(LayoutMode mode) {
        float[][] r = mode.rects();
        List<SourceLayer> sl = sourceLayers();
        for (int i = 0; i < sl.size(); i++) {
            SourceLayer l = sl.get(i);
            if (i < r.length) {
                l.setRect(r[i][0], r[i][1], r[i][2], r[i][3]);
                l.visible = true;
                l.fit = l.source.isContain() ? SourceLayer.FIT : SourceLayer.FILL;
                l.resetCrop();
            } else {
                l.visible = false;
            }
        }
        // sources sit under overlays; slot 0 is the bottom, later slots draw on top (PIP inset)
        List<Layer> others = new ArrayList<>();
        for (Layer l : layers) {
            if (!(l instanceof SourceLayer)) {
                others.add(l);
            }
        }
        layers.clear();
        layers.addAll(sl);
        layers.addAll(others);
        commitScene();
    }

    public void setLayout(LayoutMode mode) {
        autoArrange = false;
        arrange(mode);
    }

    /** Exchange the places of the first two source layers (main <-> inset). */
    public void swapMain() {
        List<SourceLayer> sl = sourceLayers();
        if (sl.size() < 2) {
            return;
        }
        autoArrange = false;
        SourceLayer a = sl.get(0), b = sl.get(1);
        float ax = a.x, ay = a.y, aw = a.w, ah = a.h;
        a.setRect(b.x, b.y, b.w, b.h);
        b.setRect(ax, ay, aw, ah);
        // the one now covering more of the frame goes underneath
        if (a.w * a.h < b.w * b.h) {
            moveLayer(b, -layers.size());
        } else {
            moveLayer(a, -layers.size());
        }
        commitScene();
    }

    /** Tell sources the display rotation changed (0/90/180/270). */
    public synchronized void onDisplayRotation(int degrees) {
        for (VideoSource s : sources) {
            s.onDisplayRotation(degrees);
        }
    }

    public void attachPreview(Surface surface) {
        compositor.setPreviewSurface(surface);
    }

    public void detachPreview() {
        compositor.clearPreviewSurfaceBlocking();
    }

    @Override
    public void onSourceError(final VideoSource source, String message) {
        postMessage(message);
        // A source that failed to open or died is useless (it would just be a black tile):
        // drop it so the layout and the source picker reflect reality.
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!released) {
                    removeSource(source);
                }
            }
        });
    }

    @Override
    public void onCompositorError(Throwable t) {
        postMessage("Graphics error: " + t.getMessage());
    }

    private void notifySources() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onSourcesChanged();
                }
            }
        });
    }

    // ================================================== pipeline

    public boolean isPipelineRunning() {
        synchronized (pipeLock) {
            return encoders != null;
        }
    }

    private boolean ensurePipeline() {
        synchronized (pipeLock) {
            if (encoders != null) {
                return true;
            }
            baseUs = System.nanoTime() / 1000;
            videoFormat = audioFormat = null;
            videoConfig = audioConfig = null;
            MediaEncoders e = new MediaEncoders(this, baseUs);
            currentBitrate = targetBitrate;
            Surface in = e.startVideo(outW, outH, fps, currentBitrate);
            if (in == null) {
                return false;
            }
            e.startAudio();
            e.setMuted(pendingMute);
            encoders = e;
            compositor.setEncoderSurface(in);
            return true;
        }
    }

    private void maybeStopPipeline() {
        MediaEncoders e;
        synchronized (pipeLock) {
            if (wantStream || wantRecord || encoders == null) {
                return;
            }
            e = encoders;
            encoders = null;
        }
        compositor.setEncoderSurface(null);
        compositor.flush();   // GL thread no longer touches the encoder surface
        e.stopAll();
        synchronized (pipeLock) {
            videoFormat = audioFormat = null;
            videoConfig = audioConfig = null;
        }
    }

    // ================================================== streaming

    public void startStream(String url) {
        if (wantStream) {
            return;
        }
        if (!ensurePipeline()) {
            setStreamState(StreamState.ERROR, "Could not start the video encoder");
            return;
        }
        streamUrl = url;
        wantStream = true;
        reconnectAttempt = 0;
        streamStartMs = SystemClock.uptimeMillis();
        setStreamState(StreamState.CONNECTING, "Connecting...");
        connectFlv();
    }

    private void connectFlv() {
        flv.start(streamUrl);
        flv.setVideoResolution(outW, outH);
        synchronized (pipeLock) {
            flvAttached = false;
            attachOutputsLocked();
        }
    }

    public void stopStream() {
        if (!wantStream) {
            return;
        }
        wantStream = false;
        main.removeCallbacks(reconnectRunnable);
        synchronized (pipeLock) {
            flvAttached = false;
        }
        flv.stop();
        setStreamState(StreamState.IDLE, "Stream stopped");
        maybeStopPipeline();
    }

    public boolean isStreaming() {
        return wantStream;
    }

    public StreamState getStreamState() {
        return streamState;
    }

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!wantStream || released) {
                return;
            }
            setStreamState(StreamState.CONNECTING, "Reconnecting (attempt " + reconnectAttempt + ")...");
            connectFlv();
        }
    };

    private void scheduleReconnect(String why) {
        if (!wantStream) {
            return;
        }
        synchronized (pipeLock) {
            if (!flvAttached && streamState == StreamState.RECONNECTING) {
                return; // already waiting
            }
            flvAttached = false;
        }
        flv.stop();
        reconnectAttempt++;
        long delay = Math.min(15000, 1000L << Math.min(reconnectAttempt, 4));
        setStreamState(StreamState.RECONNECTING, why + " - retrying in " + (delay / 1000) + "s");
        main.removeCallbacks(reconnectRunnable);
        main.postDelayed(reconnectRunnable, delay);
    }

    // ================================================== recording

    public boolean startRecord(File file) {
        if (wantRecord) {
            return false;
        }
        if (!ensurePipeline()) {
            return false;
        }
        recordFile = file;
        wantRecord = true;
        recordStartMs = SystemClock.uptimeMillis();
        synchronized (pipeLock) {
            mp4Attached = false;
            attachOutputsLocked();
        }
        return true;
    }

    public void stopRecord() {
        if (!wantRecord) {
            return;
        }
        wantRecord = false;
        synchronized (pipeLock) {
            mp4Attached = false;
        }
        mp4.stop();
        maybeStopPipeline();
    }

    public boolean isRecording() {
        return wantRecord;
    }

    // ================================================== output attach / sample routing

    /** Caller holds pipeLock. Wires up any wanted output whose codec formats are known. */
    private void attachOutputsLocked() {
        if (videoFormat == null || audioFormat == null) {
            return; // retried from onVideoFormat/onAudioFormat
        }
        boolean needKey = false;
        if (wantStream && !flvAttached) {
            flvVideoTrack = flv.addTrack(videoFormat);
            flvAudioTrack = flv.addTrack(audioFormat);
            replayConfigLocked(true);
            flvAttached = true;
            waitingForKeyFrame = true;
            needKey = true;
        }
        if (wantRecord && !mp4Attached) {
            mp4VideoTrack = mp4.addTrack(videoFormat);
            mp4AudioTrack = mp4.addTrack(audioFormat);
            if (mp4.record(recordFile)) {
                replayConfigLocked(false);
                mp4Attached = true;
                needKey = true;
                final String path = recordFile.getPath();
                // record started notification comes from SrsRecordListener
            } else {
                wantRecord = false;
                final String msg = "Cannot open the recording file";
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Listener l : listeners) {
                            l.onRecordState(false, null, msg);
                        }
                    }
                });
            }
        }
        if (needKey && encoders != null) {
            encoders.requestKeyFrame();
        }
    }

    private void replayConfigLocked(boolean toFlv) {
        if (videoConfig != null) {
            writeTo(toFlv, true, videoConfig.duplicate(), copyInfo(videoConfigInfo));
        }
        if (audioConfig != null) {
            writeTo(toFlv, false, audioConfig.duplicate(), copyInfo(audioConfigInfo));
        }
    }

    private void writeTo(boolean toFlv, boolean video, ByteBuffer data, MediaCodec.BufferInfo bi) {
        if (toFlv) {
            flv.writeSampleData(video ? flvVideoTrack : flvAudioTrack, data, bi);
        } else {
            mp4.writeSampleData(video ? mp4VideoTrack : mp4AudioTrack, data, bi);
        }
    }

    private static MediaCodec.BufferInfo copyInfo(MediaCodec.BufferInfo src) {
        MediaCodec.BufferInfo b = new MediaCodec.BufferInfo();
        b.set(0, src.size, src.presentationTimeUs, src.flags);
        return b;
    }

    private static ByteBuffer copyBytes(ByteBuffer src) {
        ByteBuffer d = ByteBuffer.allocate(src.remaining());
        d.put(src.duplicate());
        d.flip();
        return d;
    }

    @Override
    public void onVideoFormat(MediaFormat format) {
        synchronized (pipeLock) {
            videoFormat = format;
            attachOutputsLocked();
        }
    }

    @Override
    public void onAudioFormat(MediaFormat format) {
        synchronized (pipeLock) {
            audioFormat = format;
            attachOutputsLocked();
        }
    }

    @Override
    public void onVideoSample(ByteBuffer data, MediaCodec.BufferInfo info) {
        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        synchronized (pipeLock) {
            if (config) {
                videoConfig = copyBytes(data);
                videoConfigInfo = copyInfo(info);
            }
            bytesThisWindow += info.size;
            if (mp4Attached) {
                mp4.writeSampleData(mp4VideoTrack, data.duplicate(), copyInfo(info));
            }
            if (flvAttached) {
                java.util.concurrent.atomic.AtomicInteger cache = flv.getVideoFrameCacheNumber();
                int backlog = cache != null ? cache.get() : 0;
                if (key || config) {
                    waitingForKeyFrame = false;
                }
                if (backlog > fps * 4 && !key && !config) {
                    // network is hopelessly behind: drop until the next IDR instead of
                    // letting latency (and memory) grow without bound
                    droppedFrames++;
                    waitingForKeyFrame = true;
                    if (encoders != null && (droppedFrames % fps) == 1) {
                        encoders.requestKeyFrame();
                    }
                } else if (!waitingForKeyFrame || key || config) {
                    flv.writeSampleData(flvVideoTrack, data.duplicate(), copyInfo(info));
                } else {
                    droppedFrames++;
                }
            }
        }
    }

    @Override
    public void onAudioSample(ByteBuffer data, MediaCodec.BufferInfo info) {
        boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        synchronized (pipeLock) {
            if (config) {
                audioConfig = copyBytes(data);
                audioConfigInfo = copyInfo(info);
            }
            bytesThisWindow += info.size;
            if (mp4Attached) {
                mp4.writeSampleData(mp4AudioTrack, data.duplicate(), copyInfo(info));
            }
            if (flvAttached) {
                flv.writeSampleData(flvAudioTrack, data.duplicate(), copyInfo(info));
            }
        }
    }

    @Override
    public void onEncoderError(String message) {
        postMessage(message);
    }

    // ================================================== audio control / stats

    public void setMuted(boolean muted) {
        synchronized (pipeLock) {
            if (encoders != null) {
                encoders.setMuted(muted);
            }
            pendingMute = muted;
        }
    }

    private boolean pendingMute;

    public Stats getStats() {
        Stats s = new Stats();
        s.renderFps = compositor.getMeasuredFps();
        s.videoBitrateKbps = measuredKbps;
        s.targetBitrateKbps = currentBitrate / 1000;
        java.util.concurrent.atomic.AtomicInteger c = flv.getVideoFrameCacheNumber();
        s.cachedFrames = c != null ? c.get() : 0;
        s.droppedFrames = droppedFrames;
        long now = SystemClock.uptimeMillis();
        s.streamElapsedMs = wantStream ? now - streamStartMs : 0;
        s.recordElapsedMs = wantRecord ? now - recordStartMs : 0;
        s.recordBytes = mp4.getRecordedBytes();
        synchronized (pipeLock) {
            s.micLevel = encoders != null ? encoders.getMicLevel() : 0f;
            s.micMuted = pendingMute;
        }
        return s;
    }

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            if (released) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            long elapsed = Math.max(1, now - windowStartMs);
            synchronized (pipeLock) {
                measuredKbps = (int) (bytesThisWindow * 8 / elapsed);
                bytesThisWindow = 0;
            }
            windowStartMs = now;
            if (wantStream && flvAttached && adaptiveBitrate) {
                adaptBitrate();
            }
            main.postDelayed(this, 1000);
        }
    };

    /** Simple AIMD controller driven by the RTMP send backlog. */
    private void adaptBitrate() {
        java.util.concurrent.atomic.AtomicInteger c = flv.getVideoFrameCacheNumber();
        int backlog = c != null ? c.get() : 0;
        MediaEncoders e = encoders;
        if (e == null) {
            return;
        }
        if (backlog > fps) {                // > 1 second behind: back off
            calmWindows = 0;
            int next = Math.max(targetBitrate / 4, (int) (currentBitrate * 0.75f));
            if (next < currentBitrate) {
                currentBitrate = next;
                e.setVideoBitrate(currentBitrate);
            }
        } else if (backlog <= 2) {
            if (++calmWindows >= 5 && currentBitrate < targetBitrate) {
                calmWindows = 0;
                currentBitrate = Math.min(targetBitrate, (int) (currentBitrate * 1.15f));
                e.setVideoBitrate(currentBitrate);
            }
        } else {
            calmWindows = 0;
        }
    }

    // ================================================== lifecycle

    public void release() {
        released = true;
        main.removeCallbacksAndMessages(null);
        wantStream = false;
        wantRecord = false;
        try {
            flv.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            mp4.stop();
        } catch (RuntimeException ignored) {
        }
        MediaEncoders e;
        synchronized (pipeLock) {
            e = encoders;
            encoders = null;
            flvAttached = mp4Attached = false;
        }
        compositor.setEncoderSurface(null);
        compositor.flush();
        if (e != null) {
            e.stopAll();
        }
        compositor.release();
        synchronized (this) {
            sources.clear();
        }
    }

    // ================================================== events

    private void setStreamState(final StreamState st, final String msg) {
        streamState = st;
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onStreamState(st, msg);
                }
            }
        });
    }

    private void postMessage(final String msg) {
        Log.w(TAG, msg);
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onEngineMessage(msg);
                }
            }
        });
    }

    // ---- RtmpHandler.RtmpListener (main thread) ----
    @Override
    public void onRtmpConnecting(String msg) {
    }

    @Override
    public void onRtmpConnected(String msg) {
        if (wantStream) {
            reconnectAttempt = 0;
            setStreamState(StreamState.LIVE, "Live");
        }
    }

    @Override
    public void onRtmpVideoStreaming() {
    }

    @Override
    public void onRtmpAudioStreaming() {
    }

    @Override
    public void onRtmpStopped() {
    }

    @Override
    public void onRtmpDisconnected() {
        scheduleReconnect("Disconnected");
    }

    @Override
    public void onRtmpVideoFpsChanged(double fps) {
    }

    @Override
    public void onRtmpVideoBitrateChanged(double bitrate) {
    }

    @Override
    public void onRtmpAudioBitrateChanged(double bitrate) {
    }

    @Override
    public void onRtmpSocketException(SocketException e) {
        scheduleReconnect("Network error");
    }

    @Override
    public void onRtmpIOException(IOException e) {
        scheduleReconnect("Connection failed");
    }

    @Override
    public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
        // bad URL etc.: retrying will not help
        if (wantStream) {
            wantStream = false;
            synchronized (pipeLock) {
                flvAttached = false;
            }
            flv.stop();
            setStreamState(StreamState.ERROR, e.getMessage());
            maybeStopPipeline();
        }
    }

    @Override
    public void onRtmpIllegalStateException(IllegalStateException e) {
        scheduleReconnect("Protocol error");
    }

    // ---- SrsRecordHandler.SrsRecordListener (main thread) ----
    @Override
    public void onRecordPause() {
    }

    @Override
    public void onRecordResume() {
    }

    @Override
    public void onRecordStarted(String msg) {
        for (Listener l : listeners) {
            l.onRecordState(true, msg, null);
        }
    }

    @Override
    public void onRecordFinished(String msg) {
        for (Listener l : listeners) {
            l.onRecordState(false, msg, null);
        }
    }

    @Override
    public void onRecordIllegalArgumentException(IllegalArgumentException e) {
        for (Listener l : listeners) {
            l.onRecordState(false, null, e.getMessage());
        }
    }

    @Override
    public void onRecordIOException(IOException e) {
        for (Listener l : listeners) {
            l.onRecordState(false, null, e.getMessage());
        }
    }
}
