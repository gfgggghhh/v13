package com.veinfinder.app.settings

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.veinfinder.app.AutoValues
import com.veinfinder.app.camera.CameraCapabilities
import com.veinfinder.app.camera.CameraMath
import com.veinfinder.app.camera.Res
import com.veinfinder.app.processing.DisplayMode
import com.veinfinder.app.processing.EnhanceLevel
import kotlin.math.pow

/**
 * One scrollable settings screen. Changes are applied live through [onChange] (so the effect can be judged on the
 * running image); OK stores them, Cancel restores the values the dialog was opened with.
 * Controls the selected camera cannot do are shown disabled with the reason instead of silently doing nothing.
 */
class SettingsDialog(
    private val ctx: Context,
    private val initial: AppSettings,
    private val caps: CameraCapabilities?,
    private val usbSource: Boolean,
    private val auto: AutoValues?,
    private val onChange: (AppSettings) -> Unit,
    private val onCommit: (AppSettings) -> Unit
) {
    private var s = initial
    private val dp = ctx.resources.displayMetrics.density
    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(8)) }
    private val live = ArrayList<() -> Unit>()                       // re-evaluates enabled/visible state after a change

    private fun px(v: Int) = (v * dp).toInt()
    private fun set(n: AppSettings) { s = n.sanitized(); onChange(s); live.forEach { it() } }

    fun show() {
        build()
        val sv = ScrollView(ctx).apply { addView(root) }
        AlertDialog.Builder(ctx).setTitle("Settings").setView(sv)
            .setPositiveButton("OK") { _, _ -> onCommit(s) }
            .setNegativeButton("Cancel") { _, _ -> onChange(initial); onCommit(initial) }
            .setNeutralButton("Reset") { _, _ -> val d = AppSettings(display = initial.display, view = initial.view, resolution = initial.resolution); onChange(d); onCommit(d) }
            .show()
    }

    // ---------------------------------------------------------------- content
    private fun build() {
        section("Camera")
        val c = caps
        val resItems: List<Res> = c?.resolutions?.takeIf { it.isNotEmpty() } ?: listOf(Res(640, 480), Res(1280, 720), Res(1920, 1080))
        val cur = Res.parse(s.resolution)
        spinner("Camera resolution", resItems.map { it.toString() }, resItems.indexOfFirst { it == CameraMath.pickResolution(resItems, cur) }.coerceAtLeast(0)) {
            set(s.copy(resolution = resItems[it].key))
        }
        hint("Processing is capped at 960 px wide; higher capture sizes are averaged down (less noise). Larger sizes are slower.")

        if (usbSource || c == null) { hint("Exposure / focus / frame-rate controls are not available for this camera."); }
        else {
            val fps = listOf(0) + c.selectableFps
            spinner("Frame rate", fps.map { if (it == 0) "Auto" else "$it fps" }, fps.indexOf(s.camera.targetFps).coerceAtLeast(0)) {
                set(s.copy(camera = s.camera.copy(targetFps = fps[it])))
            }
            toggle("Exposure lock", { s.camera.aeLock }, { c.aeLockSupported && !s.camera.manualExposure },
                if (!c.aeLockSupported) "not supported on this camera" else if (s.camera.manualExposure) "manual exposure is on" else null) {
                set(s.copy(camera = s.camera.copy(aeLock = it)))
            }
            toggle("White balance lock", { s.camera.awbLock }, { c.awbLockSupported }, if (!c.awbLockSupported) "not supported on this camera" else null) {
                set(s.copy(camera = s.camera.copy(awbLock = it)))
            }
            toggle("Focus lock", { s.camera.focusLock }, { c.focusLockSupported && !s.camera.manualFocus },
                if (!c.focusLockSupported) "not supported on this camera" else if (s.camera.manualFocus) "manual focus is on" else "freezes the lens where autofocus is now") {
                set(s.copy(camera = s.camera.copy(focusLock = it)))
            }
            if (c.evSupported) {
                seek("Exposure compensation", c.evMin, c.evMax, { s.camera.evIndex }, { "%+.1f EV".format(it * c.evStep) }, { !s.camera.manualExposure }) {
                    set(s.copy(camera = s.camera.copy(evIndex = it)))
                }
            } else hint("Exposure compensation: not supported on this camera")

            if (c.manualExposureSupported) {
                toggle("Manual exposure", { s.camera.manualExposure }, { true }, "needs a stable light source; turns auto-exposure off") {
                    set(s.copy(camera = s.camera.copy(manualExposure = it)))
                }
                val lo = c.exposureTimeMinNs.toDouble(); val hi = c.exposureTimeMaxNs.toDouble().coerceAtMost(200_000_000.0)
                seek("Exposure time", 0, 100, { (Math.log(s.camera.exposureTimeNs.coerceIn(lo.toLong(), hi.toLong()) / lo) / Math.log(hi / lo) * 100).toInt() },
                    { p -> fmtExposure((lo * (hi / lo).pow(p / 100.0)).toLong()) }, { s.camera.manualExposure }) { p ->
                    set(s.copy(camera = s.camera.copy(exposureTimeNs = (lo * (hi / lo).pow(p / 100.0)).toLong())))
                }
                seek("ISO", c.isoMin, c.isoMax, { s.camera.iso.coerceIn(c.isoMin, c.isoMax) }, { "ISO $it" }, { s.camera.manualExposure }) {
                    set(s.copy(camera = s.camera.copy(iso = it)))
                }
                auto?.let { hint("Auto values right now: ${fmtExposure(it.exposureTimeNs)}, ISO ${it.iso}") }
            } else hint("Manual exposure / ISO: not supported on this camera")

            if (c.manualFocusSupported) {
                toggle("Manual focus", { s.camera.manualFocus }, { true }, null) { set(s.copy(camera = s.camera.copy(manualFocus = it, focusLock = if (it) false else s.camera.focusLock))) }
                seek("Focus distance", 0, 100, { (s.camera.focusDiopters / c.minFocusDistance * 100).toInt() },
                    { p -> val d = p / 100f * c.minFocusDistance; if (d < 0.05f) "far (infinity)" else "%.0f cm".format(100f / d) }, { s.camera.manualFocus }) { p ->
                    set(s.copy(camera = s.camera.copy(focusDiopters = p / 100f * c.minFocusDistance)))
                }
            } else hint("Manual focus: not supported on this camera")
        }

        section("Display")
        val modes = DisplayMode.values()
        spinner("Display mode", listOf("Original (raw, no processing)", "Enhanced (spatial)", "Stabilized (enhanced + temporal)"), modes.indexOf(s.display)) {
            set(s.copy(display = modes[it]))
        }

        section("Image enhancement")
        val levels = EnhanceLevel.values()
        spinner("Enhancement level", levels.map { it.label }, levels.indexOf(s.level)) { set(s.copy(level = levels[it])) }
        hint("Legacy = the original algorithm, kept so results can always be compared.")
        seek("Contrast", 0, 100, { s.contrast }, { if (it == 50) "neutral" else "$it" }, { s.level != EnhanceLevel.LEGACY }) { set(s.copy(contrast = it)) }
        seek("Noise reduction", 0, 100, { s.noiseReduction }, { "$it" }, { s.level != EnhanceLevel.LEGACY }) { set(s.copy(noiseReduction = it)) }
        seek("Legacy k (0 – 1)", 0, 100, { (s.k * 100).toInt() }, { "%.2f".format(it / 100.0) }, { s.level == EnhanceLevel.LEGACY }) { set(s.copy(k = it / 100.0)) }
        seek("Legacy b (0 – 10)", 0, 100, { (s.b * 10).toInt() }, { "%.1f".format(it / 10.0) }, { s.level == EnhanceLevel.LEGACY }) { set(s.copy(b = it / 10.0)) }

        section("Temporal stabilization")
        toggle("Temporal stabilization", { s.stabilizeEnabled }, { true }, "aligns frames, averages only what matches, rejects fast motion") { set(s.copy(stabilizeEnabled = it)) }
        seek("Strength", 0, 100, { s.stabilizeStrength }, { "$it" }, { s.stabilizeEnabled }) { set(s.copy(stabilizeStrength = it)) }
        hint("Applies in 'Stabilized' display mode. With level Legacy it uses Medium.")
    }

    // ---------------------------------------------------------------- widgets
    private fun section(t: String) = root.addView(TextView(ctx).apply {
        text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, px(16), 0, px(4)); setTextColor(0xFF7FD0FF.toInt())
    })

    private fun hint(t: String) = root.addView(TextView(ctx).apply { text = t; textSize = 12f; alpha = 0.7f; setPadding(0, 0, 0, px(4)) })

    private fun label(t: String) = TextView(ctx).apply { text = t; textSize = 14f; setPadding(0, px(8), 0, 0) }

    private fun spinner(title: String, items: List<String>, sel: Int, onPick: (Int) -> Unit) {
        root.addView(label(title))
        val sp = Spinner(ctx)
        sp.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(sel)
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (first) { first = false; return }; onPick(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(sp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toggle(title: String, value: () -> Boolean, enabled: () -> Boolean, note: String?, onSet: (Boolean) -> Unit) {
        val sw = SwitchCompat(ctx).apply { text = title; isChecked = value(); setPadding(0, px(6), 0, 0) }
        var updating = false
        sw.setOnCheckedChangeListener { _, b -> if (!updating) onSet(b) }
        root.addView(sw)
        val n = note?.let { TextView(ctx).apply { text = it; textSize = 12f; alpha = 0.7f } }
        n?.let { root.addView(it) }
        live.add { updating = true; sw.isChecked = value(); sw.isEnabled = enabled(); updating = false; sw.alpha = if (enabled()) 1f else 0.45f }
        sw.isEnabled = enabled(); sw.alpha = if (enabled()) 1f else 0.45f
    }

    private fun seek(title: String, min: Int, max: Int, value: () -> Int, fmt: (Int) -> String, enabled: () -> Boolean, onSet: (Int) -> Unit) {
        val lab = label("")
        fun upd(p: Int) { lab.text = "$title:  ${fmt(p)}" }
        val sb = SeekBar(ctx).apply { this.max = max - min; progress = value() - min }
        upd(value())
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { upd(p + min); onSet(p + min) } else upd(p + min) }
            override fun onStartTrackingTouch(b: SeekBar?) {}
            override fun onStopTrackingTouch(b: SeekBar?) {}
        })
        root.addView(lab); root.addView(sb)
        fun refresh() { sb.isEnabled = enabled(); sb.alpha = if (enabled()) 1f else 0.4f; lab.alpha = sb.alpha }
        live.add { refresh() }; refresh()
    }

    private fun fmtExposure(ns: Long): String =
        if (ns >= 1_000_000L) "%.1f ms".format(ns / 1e6) else "%.0f µs".format(ns / 1e3)
}
