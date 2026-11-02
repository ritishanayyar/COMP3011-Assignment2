For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505
# COMP3011 Assignment 2 – Video Player

## Overview
This project is a Java-based video player developed for Adelaide University's COMP3011 Assignment 2.
The application uses FFmpeg through JavaCV to decode video files and JavaFX to display the video and provide playback controls. It also supports audio playback and a range of frame-processing effects that can be selected through command-line arguments.

The main focus of this assignment is to improve the original single threaded video player by introducing a multithreaded processing pipeline. This allows video frames to be decoded and processed concurrently, making better use of available CPU cores while maintaining smooth playback.

## Features
- Play video files supported by FFmpeg.
- Play and toggle audio output.
- Pause and resume playback.
- Seek forward and backwards by five seconds.
- Restart or stop playback.
- Apply multiple visual effects to video frames.
- Select and combine effects through command-line arguments.
- Open the player maximised or on a selected monitor.
- Process video frames using multiple threads.
- Maintain frame ordering during concurrent processing.

## Frame Processing Effects

The player supports the following effects:

| Option | Effect | Description |
|---|---|---|
| `-n`, `--number-frames` | Frame Numberer | Displays the frame number on each frame. |
| `-s`, `--scratch-frames` | Scratcher | Adds vertical film scratches. |
| `-f`, `--flicker-frames` | Flickerer | Randomly dims frames. |
| `-w`, `--black-and-white` | Black and White | Converts frames to greyscale. |
| `-y`, `--yellow-frames` | Yellower | Applies a warmer colour temperature. |
| `-v`, `--vignette-frames` | Vignette | Darkens the edges of frames. |
| `-d`, `--dust-frames` | Duster | Adds dust and hair marks. |
| `-j`, `--jitter-frames` | Jitterer | Randomly displaces frames by a few pixels. |
| `-m`, `--mottle-frames` | Mottler | Adds cloudy emulsion mottling. |
| `-b`, `--bleed-frames` | Bleeder | Adds light bleeding to frames. |
| `-p`, `--pepper-frames` | Pepperer | Adds dark spots and blotches. |

Effects are applied in the order they appear in the command line. The same effect can also be used more than once.

For example:

```bash
mvn javafx:run '-Djavafx.args=-s -f -d -n video.mp4'
```

This applies scratches,flicker,dust and frame numbering in that order.

## Multithreaded Architecture

The original player processed decoding, frame effects and playback through a single-threaded approach. This limited the amount of processing that could be completed before the next frame was due.
The updated implementation uses a multithreaded pipeline to distribute the workload.


**Decoder thread**

Reads frames from the video using FFmpeg. It also collects audio data and submits video frames for processing.

**Worker threads**

Take decoded frames from the processing queue, apply the selected effects and prepare the processed images.

**Processing queue**

A blocking queue holds frames waiting to be processed by an available worker.

**Frame permits**

A semaphore limits the number of frames that can be in the pipeline at once. This prevents the decoder from continuously adding work faster than the workers can complete it.

**Completed frames**

Processed frames are stored with sequence numbers. The playback system uses these numbers to display frames in the correct order, even when workers finish at different times.

**JavaFX playback thread**

Handles the user interface and displays completed frames at the appropriate playback time.

### Thread safety and synch

Several mechanisms are used to coordinate the different threads:

- Blocking queues allow workers to wait for available frames without continuously checking the queue.
- Semaphores control how many frames can be processed or waiting at once.
- Sequence numbers preserve the original order of video frames.
- Pipeline generation tracking helps prevent errors from an old pipeline from affecting a newly started one.
- Worker threads are interrupted and joined when the pipeline is stopped.

These mechanisms help prevent frames from being displayed out of order and reduce the possibility of race conditions when playback is stopped or restarted.

## Controls

The player supports the following keyboard shortcuts:

| Key | Action |
|---|---|
| `Space` | Pause or resume playback |
| `Left Arrow` | Seek backwards five seconds |
| `Right Arrow` | Seek forwards five seconds |
| `Home` | Restart the video |
| `A` | Toggle audio |
| `Escape` | Stop playback and exit |

The same playback actions are also available through the on-screen controls.

## Requirements

- Java Development Kit compatible with the version configured in `pom.xml`.
- Apache Maven.
- Internet connection for Maven to download dependencies on the first build.
- A media file supported by FFmpeg.

## Building and Running

Run the following commands from the project directory containing `pom.xml`.

### Validate the project

```bash
mvn validate
```

### Compile the source code

```bash
mvn compile
```

### Package the application

```bash
mvn package
```

### Display command-line help

```bash
mvn javafx:run '-Djavafx.args=--help'
```

### Run the player with a video

```bash
mvn javafx:run '-Djavafx.args=video.mp4'
```

### Run with audio enabled

```bash
mvn javafx:run '-Djavafx.args=--audio video.mp4'
```

### Run with multiple effects

```bash
mvn javafx:run '-Djavafx.args=-sfdj video.mp4'
```

### Open the player maximised

```bash
mvn javafx:run '-Djavafx.args=--maximise video.mp4'
```

The player also supports selecting a monitor:

```bash
mvn javafx:run '-Djavafx.args=--monitor-1 video.mp4'
```


The project is organised into separate classes to keep the playback logic, user interface and frame-processing effects independent.

| Class | Use |
|---|---|
| `VideoPlayerApp` | Starts the JavaFX application. |
| `VideoPlayerController` | Connects user interactions to the video player model. |
| `VideoPlayerView` | Builds and manages the graphical interface. |
| `VideoPlayerModel` | Manages video playback, decoding, audio and frame processing. |
| `CommandLineController` | Parses command-line arguments and playback options. |
| `FrameProcessor` | Base class for the visual effects. |
| `FrameProcessorFactory` | Creates the requested sequence of frame processors. |
| `AudioPlayer` | Handles audio output. |
| `InfoVideo` | Stores video metadata. |
| `InfoFrame` | Stores information about an individual frame. |

Individual frame effects are implemented in their own classes, allowing them to be added or modified without changing the main playback logic.

## Performance and Testing

The multithreaded implementation is intended to improve playback performance when several frame-processing effects are applied.

Performance should be assessed by comparing the modified player with the supplied single-threaded implementation using the same video and effects.

Useful observations include:

- Whether frames are prepared before their scheduled display time.
- How many effects can be applied before playback starts falling behind.
- Whether audio remains continuous during playback.
- Whether the interface remains responsive while effects are being processed.
- Whether seeking, pausing and restarting work correctly while processing is underway.

Performance results should be recorded from actual tests rather than assumed, as they can vary depending on the hardware and system load.

