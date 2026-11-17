package com.veinfinder.app.processing

/** Smoothed FPS / processing time / dropped-frame counters for the on-screen status line. Thread-safe. */
class PerfMonitor {
    private var lastNs = 0L
    private var fps = 0.0
    private var ms = 0.0
    private var processed = 0L
    private var dropped = 0L

    @Synchronized fun frameProcessed(procMs: Double, nowNs: Long = System.nanoTime()) {
        if (lastNs != 0L) {
            val dt = (nowNs - lastNs) / 1e9
            if (dt > 0) fps = if (fps == 0.0) 1.0 / dt else fps + 0.1 * (1.0 / dt - fps)
        }
        lastNs = nowNs
        ms = if (processed == 0L) procMs else ms + 0.1 * (procMs - ms)
        processed++
    }

    @Synchronized fun frameDropped() { dropped++ }
    @Synchronized fun reset() { lastNs = 0; fps = 0.0; ms = 0.0; processed = 0; dropped = 0 }

    @Synchronized fun fps() = fps
    @Synchronized fun procMs() = ms
    @Synchronized fun dropped() = dropped
    @Synchronized fun processed() = processed
}
