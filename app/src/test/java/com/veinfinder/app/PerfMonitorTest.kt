package com.veinfinder.app

import com.veinfinder.app.processing.PerfMonitor
import org.junit.Assert.*
import org.junit.Test

class PerfMonitorTest {
    @Test fun fpsAndDrops() {
        val p = PerfMonitor()
        var t = 1_000_000_000L
        repeat(60) { p.frameProcessed(20.0, t); t += 33_333_333L }
        assertEquals(30.0, p.fps(), 0.5); assertEquals(20.0, p.procMs(), 0.01)
        p.frameDropped(); p.frameDropped(); assertEquals(2L, p.dropped())
        p.reset(); assertEquals(0.0, p.fps(), 0.0); assertEquals(0L, p.dropped())
    }
}
