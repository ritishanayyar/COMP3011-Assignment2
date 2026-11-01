# Baseline: single-threaded player
Machine: MacBook Air, 10 cores, Java 25. Video: Test.mp4 (1920x800, 23.98 fps, budget 41708 us)

|Effects|Count|Headroom|Result|
| -n | 1 | 20 to 36 ms | green |
| nb | 2 | 11 to 29 ms | green |
|-nsfwyvdjmbp twice | 22 | -4.7 to +2.6 ms | at the limit, some late frames 

The first frame is always late at startup (about -60 ms), so it is ignored.