For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505
## COMP3011 Assignment 2 - Multi-threaded Video Player

This project is a Java video player that uses FFmpeg (through JavaCV) to decode videos and JavaFX to display them. It also supports several old-film effects that can be selected and combined through command-line arguments.
The original player processed everything on one thread. I changed this to a multi-threaded design where frames can be decoded and processed simultaneously, allowing more effects to be applied without slowing playback as much.

## Building and running

Run all commands from the folder containing `pom.xml`.

| Command | Purpose |
| --- | --- |
| `mvn validate` | Check project validity |
| `mvn compile` | Compile the source code |
| `mvn package` | Build the JAR |
| `mvn verify` | Run unit tests |
| `mvn javafx:run '-Djavafx.args=--help'` | Show available options |
| `mvn javafx:run '-Djavafx.args=-nsfwyvdjmbp video.mp4'` | Run with effects |

Available options include `-a` for audio, `-x` to maximise the window and `-1` / `-2` to select a monitor.

Effects can be combined, repeated and applied in command-line order. For example, `-jfddn` is equivalent to `-j -f -d -d -n`.

| Option | Effect |
| --- | --- |
| `-n` | Display frame numbers |
| `-s` | Add film scratches |
| `-f` | Add flickering |
| `-w` | Black and white |
| `-y` | Warmer colours |
| `-v` | Darken frame edges |
| `-d` | Add dust and hair marks |
| `-j` | Random image displacement |
| `-m` | Add film mottling |
| `-b` | Light bleeding |
| `-p` | Add dark spots |

Keyboard controls: Space pauses/resumes, Left/Right seeks 5 seconds, Home restarts, A toggles audio and Esc stops playback.

## Class overview

| Class | Responsibility |
| --- | --- |
| `VideoPlayerApp`, `VideoPlayerAppLauncher` | Start JavaFX |
| `CommandLineController` | Parse command-line arguments |
| `FrameProcessorFactory` | Create effect processors |
| `VideoPlayerController`, `VideoPlayerView` | Handle the GUI |
| `VideoPlayerModel` | Manage playback, timing and audio |
| `FramePipeline` | Manage decoding, worker threads and frame ordering |
| `FrameProcessor` and `Frame*` | Implement individual effects |
| `AudioPlayer`, `InfoVideo`, `InfoFrame` | Handle audio and video information |

### Adding an effect

To add another effect:

1. Create a class extending `FrameProcessor`.
2. Add it to `FrameProcessorFactory.createChain()`.
3. Add its command-line flag and help text.
4. Add a test in `FrameProcessorFactoryTest`.

The threading code does not need to change because each worker creates its own effect chain.

## Multi-threading design

The processing is split into three parts:

```text
Decoder thread
      |
   workQueue
      |
Worker threads
      |
 finishedFrames
      |
 JavaFX thread
```

- **Decoder:** Reads video and audio from FFmpeg. Video frames are cloned and added to a work queue, while audio is placed in its own queue.
- **Workers:** Each worker processes frames using its own effect chain and converts them into JavaFX images. The number of workers is based on CPU cores (`cores - 2`), with a minimum of two.
- **JavaFX:** Handles the interface, playback clock, audio and displaying frames in the correct order using `AnimationTimer`.

Workers process different frames simultaneously, but effects within each frame are still applied in the order selected by the user.


Since multiple threads access shared resources, I used different approaches to avoid race conditions:

| Shared resource | Protection |
| --- | --- |
| `FFmpegFrameGrabber` | Only the decoder uses it during playback. The decoder is interrupted and joined before seeking or closing. |
| `workQueue` | Bounded `ArrayBlockingQueue` |
| Frames in progress | `Semaphore` limits the number of frames being processed. |
| Effect objects | Each worker has its own effect chain (thread confinement). |
| `finishedFrames` | `ConcurrentHashMap` stores completed frames by sequence number. |
| `pendingAudio` | `ConcurrentLinkedQueue` |
| Shared flags and counters | `volatile` ensures changes are visible between threads. |
| Playback clock and seek fields | Only accessed by the JavaFX thread. |

## Important design decisions

- **Frame ordering:** Each frame has a sequence number. The JavaFX thread only displays the next expected frame, even if other workers finish earlier.
- **Independent processing:** Frames are cloned and each worker has its own effects, avoiding shared-state problems.
- **Stopping threads:** Blocking operations are interruptible. The pipeline interrupts and joins its threads when stopping.
- **Safe seeking:** The decoder is stopped before the grabber is repositioned or closed.
- **Old errors:** A generation counter prevents errors from previous pipeline runs affecting a new run.
- **Playback timing:** Frames are displayed according to their timestamps. If processing falls behind, the playback clock moves forward by the lateness.

## Trade-offs

- Each worker has its own effect chain, which uses more memory, especially for effects that create large masks or tables.
- The number of frames in progress is limited to `3 x workers` to control memory usage and latency.
- Two CPU cores are left available for JavaFX, decoding and audio rather than creating as many workers as possible.

## Testing and results

I used `mvn verify` to run the JUnit tests.

- `CommandLineControllerTest` checks argument parsing, combined flags, repeated effects and ordering.
- `FrameProcessorFactoryTest` checks effect creation, ordering and whether each factory call returns new objects.

I also compared the original and multi-threaded implementations through performance measurements and manual testing.

The results are available in:

- `docs/baseline.md` - Original single-threaded performance.
- `docs/MultithreadFindings.md` - Multi-threaded performance and observations.