# Offline evaluation

Runs the app's **real** processing code (VeinProcessor / TemporalStabilizer / FramePipeline) on a desktop JVM.

1. Get an OpenCV desktop Java jar + native lib (Windows: official release `build/java/opencv-4xx.jar` and `build/java/x64/opencv_java4xx.dll`;
   Ubuntu: `apt install libopencv-java libopencv406-jni`) and Kotlin (`kotlinc`).
2. `tools/bench/build.sh /path/to/opencv.jar`
3. `tools/bench/run.sh spatial <inDir> <outDir> legacy0,low,medium,high`      (one image -> one output per variant)
   `tools/bench/run.sh seq <frameDir> <outDir> medium,stab:medium:60`         (ordered frames, state carried across frames)
   Variants accept `@nr=0..100 @ct=0..100 @rg= @cl= @tg= @eb= @sg=a/b @gs= @gr=` overrides, e.g. `medium@nr=30@cl=2`.
   `legacy0` is a verbatim copy of the ORIGINAL VeinProcessor (for side-by-side / bit-exact comparison).
4. Put YOUR OWN reference stills/videos (frames exported with ffmpeg) in a folder and compare variants visually; the python scripts here
   (`prep_real.py`, `phantom.py`, `seqgen.py`, `metrics_*.py`) are the synthetic/pseudo-ground-truth tests used during development.
   They expect the public dataset github.com/wilchesf/dorsalhandveins cloned next to them (not redistributed here).
5. `java --finalization=disabled -Xmx96m -Xmn24m -XX:+UseSerialGC ... bench.StressKt <image>` = leak / degenerate-input stress test.
