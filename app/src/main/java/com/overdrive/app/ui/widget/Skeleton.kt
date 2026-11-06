package com.overdrive.app.ui.widget

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver

/**
 * A block overlays its value slot while the value stays `INVISIBLE`, so the row measures the same
 * before and after. Revealing is one-way: these pages poll, and re-showing a block reads as flicker.
 */
class Skeleton(private val host: View) {

    private class Slot(val block: View, val content: List<View>)

    private val slots = LinkedHashMap<Int, Slot>()
    private val followed = ArrayList<View>()
    private val loaded = HashSet<Int>()
    private var pulse: ValueAnimator? = null
    private var attachListenerRegistered = false
    private var layoutObserver: ViewTreeObserver? = null

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        syncPulseState()
    }

    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            attachLayoutListener()
            syncPulseState()
        }

        override fun onViewDetachedFromWindow(v: View) {
            detachLayoutListener()
            stopPulse()
        }
    }

    /** Registers a placeholder block and the views it stands in for, and shows the placeholder. */
    fun bind(blockId: Int, vararg content: View) {
        val block = host.findViewById<View>(blockId) ?: return
        slots[blockId] = Slot(block, content.toList())
        if (loaded.contains(blockId)) return
        block.visibility = View.VISIBLE
        for (view in content) view.visibility = View.INVISIBLE
        ensureLifecycleListeners()
        syncPulseState()
    }

    /** Pulses [blockId] whenever it is visible, for a placeholder the caller may show again. */
    fun follow(blockId: Int) {
        val block = host.findViewById<View>(blockId) ?: return
        followed.add(block)
        ensureLifecycleListeners()
        syncPulseState()
    }

    fun isLoaded(blockId: Int): Boolean = loaded.contains(blockId)

    /** Retires a placeholder for good and reveals the real value. */
    fun markLoaded(blockId: Int) {
        loaded.add(blockId)
        val slot = slots[blockId] ?: return
        slot.block.visibility = View.GONE
        for (view in slot.content) view.visibility = View.VISIBLE
        if (!hasPotentialTargets()) {
            teardownLifecycleListeners()
            stopPulse()
        } else if (!hasVisibleTarget()) {
            stopPulse()
        }
    }

    fun cancel() {
        teardownLifecycleListeners()
        stopPulse()
        slots.clear()
        followed.clear()
    }

    private fun hasPotentialTargets(): Boolean {
        return followed.isNotEmpty() || slots.keys.any { !loaded.contains(it) }
    }

    private fun hasVisibleTarget(): Boolean {
        for ((id, slot) in slots) {
            if (!loaded.contains(id) && slot.block.visibility == View.VISIBLE) return true
        }
        for (block in followed) {
            if (block.visibility == View.VISIBLE) return true
        }
        return false
    }

    private fun isHostVisible(): Boolean {
        return host.isAttachedToWindow && host.windowVisibility == View.VISIBLE && host.isShown
    }

    private fun syncPulseState() {
        if (!hasPotentialTargets()) {
            teardownLifecycleListeners()
            stopPulse()
            return
        }
        if (isHostVisible() && hasVisibleTarget()) {
            startPulse()
        } else {
            stopPulse()
        }
    }

    private fun ensureLifecycleListeners() {
        if (!attachListenerRegistered) {
            host.addOnAttachStateChangeListener(attachStateListener)
            attachListenerRegistered = true
        }
        if (host.isAttachedToWindow) {
            attachLayoutListener()
        }
    }

    private fun attachLayoutListener() {
        val currentVto = host.viewTreeObserver
        if (layoutObserver === currentVto && currentVto.isAlive) return
        detachLayoutListener()
        if (currentVto.isAlive) {
            currentVto.addOnGlobalLayoutListener(globalLayoutListener)
            layoutObserver = currentVto
        }
    }

    private fun detachLayoutListener() {
        val obs = layoutObserver
        layoutObserver = null
        if (obs != null && obs.isAlive) {
            obs.removeOnGlobalLayoutListener(globalLayoutListener)
        }
    }

    private fun teardownLifecycleListeners() {
        detachLayoutListener()
        if (attachListenerRegistered) {
            host.removeOnAttachStateChangeListener(attachStateListener)
            attachListenerRegistered = false
        }
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                if (!isHostVisible() || !hasVisibleTarget()) {
                    stopPulse()
                    return@addUpdateListener
                }
                val alpha = animator.animatedValue as Float
                for ((id, slot) in slots) {
                    if (!loaded.contains(id)) slot.block.alpha = alpha
                }
                for (block in followed) {
                    if (block.visibility == View.VISIBLE) block.alpha = alpha
                }
            }
            start()
        }
    }

    private fun stopPulse() {
        val active = pulse ?: return
        pulse = null
        active.cancel()
        for (slot in slots.values) slot.block.alpha = 1f
        for (block in followed) block.alpha = 1f
    }

    companion object {
        private const val PULSE_MS = 620L
    }
}
