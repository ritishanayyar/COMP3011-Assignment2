package comp3011;
 
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;
 import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
 
final class FramePipeline {
 
    // One worker per core 
    static final int DEFAULT_WORKER_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    private static final int FRAMES_IN_FLIGHT_PER_WORKER = 3;
    record PreparedFrame(Image image, int frameNumber, long mediaTimestampUs, long preparedAtNs) {
    }
     private record DecodedFrame(long sequence, int frameNumber, long timestampUs, Frame frame, InfoVideo videoInfo) {
    }
    private static final class WorkerChain {
        private final List<FrameProcessor> processors;
        private boolean initialised;
 
        WorkerChain(List<FrameProcessor> processors) {
            this.processors = processors;
        }
    }
 
    private final FFmpegFrameGrabber grabber;
    private final boolean grabAudio;
    private final BiConsumer<Long, Frame> audioHandler; // called on the decoder thread
    private final Function<Frame, InfoVideo> videoInfoFactory; //called once per run
    private final Consumer<Exception> failureHandler; // called on the JavaFX thread
 
    private final List<WorkerChain> workerChains = new ArrayList<>();
    private final List<Thread> workerThreads = new ArrayList<>();
    private final BlockingQueue<DecodedFrame> workQueue;
    private final Semaphore framePermits;
    private final int maxFramesInFlight;
    private final ConcurrentHashMap<Long, PreparedFrame> finishedFrames = new ConcurrentHashMap<>();
 
    private Thread decoderThread;
    private int generation; // JavaFX thread only
    private long nextSequenceToDisplay; // JavaFX thread only
    private volatile long framesSubmitted;
    private volatile boolean endOfMediaReached;
 
    FramePipeline(FFmpegFrameGrabber grabber, boolean grabAudio, List<String> effects, int workerCount,
            BiConsumer<Long, Frame> audioHandler, Function<Frame, InfoVideo> videoInfoFactory,
            Consumer<Exception> failureHandler) {
        this.grabber = grabber;
        this.grabAudio = grabAudio;
        this.audioHandler = audioHandler;
        this.videoInfoFactory = videoInfoFactory;
        this.failureHandler = failureHandler;
        this.maxFramesInFlight = workerCount * FRAMES_IN_FLIGHT_PER_WORKER;
        this.workQueue = new ArrayBlockingQueue<>(maxFramesInFlight);
        this.framePermits = new Semaphore(maxFramesInFlight);
        for (int i = 0; i < workerCount; i++) {
            workerChains.add(new WorkerChain(FrameProcessorFactory.createChain(effects)));
        }
    }
    void start() {
        stop(); 
        endOfMediaReached = false;
        framesSubmitted = 0;
        nextSequenceToDisplay = 0;
        finishedFrames.clear();
        workQueue.clear();
        framePermits.drainPermits();
        framePermits.release(maxFramesInFlight);
 
        final int myGeneration = generation;
        for (int i = 0; i < workerChains.size(); i++) {
            final WorkerChain chain = workerChains.get(i);
            Thread worker = new Thread(() -> workerLoop(chain, myGeneration), "video-worker-" + i);
            worker.setDaemon(true); // never keep the JVM alive after the window has closed
            workerThreads.add(worker);
            worker.start();
        }
        decoderThread = new Thread(() -> decodeLoop(myGeneration), "video-decoder");
        decoderThread.setDaemon(true);
        decoderThread.start();
    }
    void stop() {
        if (decoderThread != null) {
            decoderThread.interrupt(); // wakes it if blocked on a permit or a full queue
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
        workQueue.clear();
        finishedFrames.clear();
        endOfMediaReached = false;
        framesSubmitted = 0;
        nextSequenceToDisplay = 0;
        generation++; // failure reports from the threads just stopped are now stale
    }
    PreparedFrame peekNext() {
        return finishedFrames.get(nextSequenceToDisplay);
    }

    void advance() {
        finishedFrames.remove(nextSequenceToDisplay);
        nextSequenceToDisplay++;
        framePermits.release();
    }
 
    boolean isDrained() {
        boolean ended = endOfMediaReached; // read the flag first; the count is final once the flag is set
        long submitted = framesSubmitted;
        return ended && nextSequenceToDisplay >= submitted;
    }
 
    private void decodeLoop(int myGeneration) {
        final Thread self = Thread.currentThread();
        InfoVideo videoInfo = null;
        long sequence = 0;
        try {
            while (!self.isInterrupted()) {
                Frame frame = grabAudio ? grabber.grab() : grabber.grabImage();
                if (frame == null) {
                    endOfMediaReached = true;
                    return;
                }
                long timestampUs = grabber.getTimestamp();
                if (grabAudio && frame.samples != null) {
                    audioHandler.accept(timestampUs, frame);
                }
                if (frame.image == null) {
                    continue;
                }
                if (videoInfo == null) {
                    videoInfo = videoInfoFactory.apply(frame);
                }
                framePermits.acquire(); // blocks while too many frames are in flight
                //the grabber reuses its Frame so each worker needs its own copy.
                Frame privateCopy = frame.clone();
                workQueue.put(new DecodedFrame(sequence, grabber.getFrameNumber(), timestampUs, privateCopy, videoInfo));
                sequence++;
                framesSubmitted = sequence; // count first, then (at end of media) the flag
            }
        } catch (InterruptedException e) {
        // just end the thread
        } catch (Exception e) {
            reportFailure(e, myGeneration);
        }
    }
    private void workerLoop(WorkerChain chain, int myGeneration) {
        final Thread self = Thread.currentThread();
        JavaFXFrameConverter converter = new JavaFXFrameConverter(); // one per worker
        try {
            while (!self.isInterrupted()) {
                DecodedFrame work = workQueue.take(); // blocks when there is nothing to do
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
        } catch (Exception e) {
            reportFailure(e, myGeneration);
        } finally {
            converter.close();
        }
    }
    private Image copyOfImage(Image converted) {
        PixelReader reader = converted.getPixelReader();
        if (reader == null) {
            return converted;
        }
        return new WritableImage(reader, (int) converted.getWidth(), (int) converted.getHeight());
    }
    private void reportFailure(Exception e, int myGeneration) {
        Platform.runLater(() -> {
            if (myGeneration == generation) {
                failureHandler.accept(e);
            }
        });
    }
 
    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}