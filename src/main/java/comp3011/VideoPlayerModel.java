* Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <Ritisha Nayyar,a1948388>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;
 
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
 public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; //when seek position it active this is used.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
 
    private static final int WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    //if a frame is shown more than this late move the clock forward
    private static final long MAX_LATENESS_BEFORE_RESYNC_NS = 20_000_000L;
    private static final int MAX_FRAMES_IN_FLIGHT = WORKER_COUNT* 3;
 
    private final List<String> frameEffects;
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, PreparedFrame> finishedFrames= new ConcurrentHashMap<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;
 
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };
 
    //fields below are only used by the JavaFX thread
    private File videoFile; 
    private FFmpegFrameGrabber grabber; 
    private AudioPlayer audioPlayer; 
    private List<WorkerChain> workerChains =new ArrayList<>(); //one per worker, made when a video is opened.
    private BlockingQueue<DecodedFrame> workQueue; // Decoder to workers
    private Semaphore framePermits; //limits frames
    private Thread decoderThread;
    private final List<Thread> workerThreads =new ArrayList<>();
    private int pipelineGeneration; /
    private long nextSequenceToDisplay; 
    private volatile long framesSubmitted; 
    private volatile boolean endOfMediaReached; 
    private boolean playbackOpen; 
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; 
    private boolean audioAvailable;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
 
    public VideoPlayerModel(
            boolean audioEnabled,
            List<String> frameEffects,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.frameEffects = List.copyOf(frameEffects);
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
    }
    public void play(File file) {
        videoFile= file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }
     public void startOver() {
        if (videoFile == null) {
            return;
        }
        seekTo(0);
    }
 
    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }
    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }
    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }
        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;
        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        } notifyPlaybackStateChanged();
    }
    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }
    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }
 
    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }
    public void shutdown() {
        closePlaybackResources();
    }
    private void seekRelative(long offsetUs) {
        if (videoFile== null) {
            return;
        }
        long baseTimestampUs =relativeSeekBaseUs!= NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }
 
    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);
 
        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }
 
        try {
            // The grabber belongs to the decoder thread while it runs
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }
 
    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();
 
        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;
 
        notifyStatusChanged(videoFile.getName());
 
        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }
 
    private void openPlaybackResources(long startTimestampUs) throws Exception {
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();
 
        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());
 
        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();
 
        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;
        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }
        //One private chain of effects per worker
        workerChains = new ArrayList<>();
        for (int i = 0; i < WORKER_COUNT; i++) {
            workerChains.add(new WorkerChain(FrameProcessorFactory.createChain(frameEffects)));
        }
 
        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }
    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }
 
    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }
 
    // The clock starts when the first frame after a start or seek reaches the JavaFX thread
    private void startPlaybackClock(long firstFrameTimestampUs) {
        firstTimestampUs = firstFrameTimestampUs;
        playbackStartNs = System.nanoTime();
        if (pauseRequested) {
            pauseStartedNs = playbackStartNs;
        }
    }
    private void startPipeline() {
        final int generation = pipelineGeneration;
        endOfMediaReached = false;
        framesSubmitted = 0;
        nextSequenceToDisplay = 0;
        finishedFrames.clear();
        workQueue = new ArrayBlockingQueue<>(MAX_FRAMES_IN_FLIGHT);
        framePermits = new Semaphore(MAX_FRAMES_IN_FLIGHT);
 
        for (int i = 0; i < workerChains.size(); i++) {
            final WorkerChain chain = workerChains.get(i);
            Thread worker = new Thread(() -> workerLoop(chain, generation), "video-worker-" + i);
            worker.setDaemon(true); // Never keep the JVM alive after the window has closed.
            workerThreads.add(worker);
            worker.start();
        }
 
        decoderThread = new Thread(() -> decodeLoop(generation), "video-decoder");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }
    // Interrupts every pipeline thread
    private void stopPipeline() {
        if (decoderThread != null) {
            decoderThread.interrupt(); //interrupts it if it is waiting for a permit or queue space
        }
        for (Thread worker : workerThreads) {
            worker.interrupt();
        }
        joinQuietly(decoderThread);
        for (Thread worker : workerThreads) {
            joinQuietly(worker);
        }
        decoderThread = null;
        workerThreads.clear();
        if (workQueue != null) {
            workQueue.clear();
        }
        finishedFrames.clear();
        endOfMediaReached = false;
        framesSubmitted = 0;
        nextSequenceToDisplay= 0;
        pipelineGeneration++; //any error report still waiting to run belongs to the old pipeline
    }
    private void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try { thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    //show the error on the JavaFX thread, unless the pipeline has been replaced
    private void reportPipelineFailure(Exception e, int generation) {
        Platform.runLater(() -> {
            if (generation == pipelineGeneration) {
                handlePlaybackError(e);
            }
        });
    }
    private void decodeLoop(int generation) {
        final Thread self = Thread.currentThread();
        InfoVideo videoInfo = null;
        long sequence = 0;
        try {
            while (!self.isInterrupted()) {
                Frame frame = grabFrame();
                if (frame == null) {
                    endOfMediaReached = true;
                    return;
                }
 
                long timestampUs = grabber.getTimestamp();
                if (audioAvailable && frame.samples != null) {
                    queueAudio(timestampUs, frame);
                }
                if (frame.image == null) {
                    continue;
                }
                if (videoInfo == null) {
                    videoInfo = new InfoVideo(
                            mediaName(videoFile),
                            totalVideoFrames,
                            frame.imageWidth,
                            frame.imageHeight,
                            frame.imageDepth,
                            frame.imageChannels,
                            frame.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            grabber.getPixelFormat());
                }
 
                framePermits.acquire(); // Waits here when too many frames 
                Frame privateCopy = frame.clone();
                workQueue.put(new DecodedFrame(sequence, grabber.getFrameNumber(), timestampUs, privateCopy, videoInfo));
                sequence++;
                framesSubmitted = sequence;
            }
        } catch (InterruptedException e) {
        } catch (Exception e) {
            reportPipelineFailure(e, generation);
        }
    }
 
    private void workerLoop(WorkerChain chain, int generation) {
        final Thread self = Thread.currentThread();
        JavaFXFrameConverter converter = new JavaFXFrameConverter(); //each worker has its own.
        try {
            while (!self.isInterrupted()) {
                DecodedFrame work = workQueue.take(); //waits here when there is nothing to do.
 
                if (!chain.initialised) {
                    for (FrameProcessor processor : chain.processors) {
                        processor.initialise(work.videoInfo());
                    }
                    chain.initialised = true;
                }
                InfoFrame info = new InfoFrame(work.frameNumber(), work.timestampUs());
                for (FrameProcessor processor : chain.processors) {
                    processor.process(work.frame(), info);
                }
                Image image = copyOfImage(converter.convert(work.frame()));
                finishedFrames.put(work.sequence(),
                        new PreparedFrame(image, work.frameNumber(), work.timestampUs(), System.nanoTime()));
            }
        } catch (InterruptedException e) {
            // thread ending
        } catch (Exception e) {
            reportPipelineFailure(e, generation);
        } finally {
            converter.close();
        }
    }
    // The converter may hand back the same image object each time
    private Image copyOfImage(Image converted) {
        PixelReader reader = converted.getPixelReader();
        if (reader == null) {
            return converted;
        }
        return new WritableImage(reader, (int) converted.getWidth(), (int) converted.getHeight());
    }
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }
        // 1
        writeDueAudio(now);
 
        // 2- Read the end flag before the count, the decoder updates the count before it sets the flag, so if the flag is true the count is final
        boolean mediaEnded = endOfMediaReached;
        long submitted = framesSubmitted;
        PreparedFrame next = finishedFrames.get(nextSequenceToDisplay);
 
        if (next == null) {
            if (mediaEnded && nextSequenceToDisplay >= submitted && !pauseRequested) {
                finishPlayback();
            }
            return;
        }
 
        if (firstTimestampUs == NO_SEEK_REQUEST) {// start the clock, and show it straight away.
            startPlaybackClock(next.mediaTimestampUs());
        } else if (pauseRequested) {
            return; // keep showing the current frame.
        }
        long relativeTimestampUs = Math.max(0, next.mediaTimestampUs() - firstTimestampUs);
        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
        if (targetTimeNs <= now) {
            finishedFrames.remove(nextSequenceToDisplay);
            nextSequenceToDisplay++;
            framePermits.release(); // One more frame may now enter the pipeline.
            displayPreparedFrame(next, logicalPlaybackBaseUs + relativeTimestampUs, targetTimeNs);
 
            //when the workers cannot keep up we fall behind. Ra few milliseconds apart. move the clock forward so playback slows down smoothly, one frame per frame duration.
            long latenessNs = now - targetTimeNs;
            if (latenessNs > MAX_LATENESS_BEFORE_RESYNC_NS) {
                playbackStartNs += latenessNs;
            }
        }
    }
 
    private void displayPreparedFrame(PreparedFrame frame, long logicalTimestampUs, long targetTimeNs) {
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = NO_SEEK_REQUEST;
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }
        notifyFrameReady(frame.image());
    }
    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }
 
        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }
    private void writeDueAudio(long now) {
        // Handle someearly exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }
        //real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }
 
            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }
 
            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }
    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;
        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }
    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }
    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        stopPipeline(); // Must happen before the grabber is closed below
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        workerChains = new ArrayList<>();
 
        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }
 
        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed, no useful recovery action
            }
            grabber= null;
        }
    }
 
    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }
    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }
    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }
        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }
 
        return Math.max(0, durationUs - frameDurationUs);
    }
    // Decoder thread only
    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }
 
    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }
     private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }
 
    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }
 
    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }
 
    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }
    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
// One worker's private chain of effects. Only the worker thread that currently owns it touches it and a new worker thread only gets it after the old one was joined, so no locking is needed.
    private static class WorkerChain {
        private final List<FrameProcessor> processors;
        private boolean initialised;
 
        WorkerChain(List<FrameProcessor> processors) {
            this.processors = processors;
        }
    }
    private record DecodedFrame(
            long sequence,
            int frameNumber, long timestampUs, Frame frame,
            InfoVideo videoInfo) {
    }
    private record PreparedFrame(
            Image image,
            int frameNumber, long mediaTimestampUs, long preparedAtNs) {
    }
 
    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;
 
        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }
        long timestampUs() {
            return timestampUs;
        }
        byte[] samples() {
            return samples;
        }
        int offset() {
            return offset;
        }
        int remaining() {
            return samples.length - offset;
        }
        void advance(int byteCount) {
            offset += byteCount;
        }
        boolean finished() {
            return offset>= samples.length;
        }
    }
}
