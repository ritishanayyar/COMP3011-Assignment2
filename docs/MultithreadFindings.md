# Multi-threaded player: findings

## Test setup

| Item | Value |
|---|---|
| Machine | MacBook Air, 10 cores |
| Java | 25 |
| Video | `Test.mp4`, 1920x800, 23.98 fps (frame budget 41,708 us) |
| Pipeline | 1 decoder thread, 8 worker threads (`cores - 2`), up to 24 frames in flight |
| Effect stacks | the 11 effects `-nsfwyvdjmbp` repeated (22 = twice, 44 = four times,) |
| Method | `mvn javafx:run "-Djavafx.args=<effects> <video>"`, played 30 to 60 s, console output saved with `tee` |
| Reading the trace | The first sample is always late or unrepresentative at startup and is ignored. Green = frame ready before it was due, red (negative) = late |

## Results

| Effects | Single thread (baseline.md) | Multi-threaded headroom | Multi-threaded result |
|---|---|---|---|
| 1 (`-n`) | 20 to 36 ms | 978 to 999 ms | green |
| 2 (`-nb`) | 11 to 29 ms | 970 to 1018 ms | green |
| 22 | -4.7 to +2.6 ms (at the limit, some late frames) | 936 to 998 ms | green |
| 44 | not measured | 884 to 935 ms | green |
| 66 | not measured | 839 to 903 ms | green |
| 88 | not measured | 776 to 854 ms | green |
| 110 | not measured | about 625 to 763 ms (dipped to 143 ms while the pipeline filled) | green, no late frames |
| 154 | not measured | -33 to +120 ms | at the limit: 8 of 37 samples late |

**Headline:** the single-threaded player is at its limit at about 22 effects. The multi-threaded player shows no
late frames up to 110 effects and reaches its limit at about 154: roughly **5x more effects with no late frames, and
about 7x at the limit**. The exact limit lies between 110 and 154 effects (132 was not run).

## Analysis

**Why headroom is about 1 second.** With the pipeline, the decoder and workers run ahead of the display by up to 24
frames (24 x 41.7 ms is about 1 s). Frames are therefore finished long before they are due, and headroom mostly
measures how full that buffer is. The meaningful signal is the trend towards zero, not the absolute value, and it
should not be compared directly with the single-threaded 20 to 36 ms.

**Trend.** Headroom fell steadily as effects were added: about 950 ms at 22, 925 at 44, 870 at 66, 810 at 88 and
710 at 110. This is the buffer being drained slightly more as the workers take longer per frame. Between 110 and 154
the workers could no longer stay ahead: at 154 the headroom started at about 210 ms (the buffer never filled) and
hovered between -33 and +120 ms.

**Back-of-envelope check (an estimate, not a measurement).** The single-threaded player was just keeping up at 22
effects in 41.7 ms, which puts the cost at roughly 1.9 ms per effect per frame (this also includes decoding and
rendering, so it is an upper bound). With 8 workers, the capacity is about 8 x 41.7 / 1.9, or around 175 effects. The
observed limit of about 154 is close to that, and the gap is plausibly the work that does not parallelise
across workers: the single decoder thread, frame cloning, `Image` conversion, and the JavaFX rendering thread. This
suggests the design is close to the core-count limit rather than being held back by locking.

**Behaviour at the limit.** When frames are late, `pumpPlayback` advances the clock by the lateness once it exceeds
20 ms, so playback slows down smoothly instead of racing to catch up or showing frames out of order. Frames are still
displayed in sequence order.

* CPU use across cores during a heavy run (Activity Monitor, CPU History).
* Audio continuity (`-a`) under heavy load.
* GUI responsiveness while dragging and resizing the window under heavy load.
* The 132-effect run, which would narrow the limit between 110 and 154.
* Effect of different worker counts (`FramePipeline.DEFAULT_WORKER_COUNT`) or buffer depth
  (`FRAMES_IN_FLIGHT_PER_WORKER`).
