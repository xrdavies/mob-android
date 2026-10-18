package com.xrdavies.mob.stream

import kotlinx.coroutines.flow.StateFlow

/**
 * The live view the desktop screens draw and control ([StreamSession]). Coordinates are pixels of the picture ([LiveState.width] ×
 * [LiveState.height]); keys are X11 keysyms from the keys bar. Input is sent only in Control mode.
 */
interface LiveSession {
    val state: StateFlow<LiveState>
    val modifiers: StateFlow<Set<Int>>

    /** Scroll by pixels ([scrollPixels]) rather than wheel notches ([scroll]). */
    val pixelScroll: Boolean get() = false

    fun setControl(on: Boolean)
    fun click(x: Int, y: Int, button: Int = LiveKeys.BUTTON_LEFT)
    fun pointer(x: Int, y: Int, buttons: Int)
    fun scroll(x: Int, y: Int, dx: Int, dy: Int)
    fun scrollPixels(x: Int, y: Int, dx: Float, dy: Float) {}
    fun toggleModifier(keysym: Int)
    fun tapKey(keysym: Int)
    fun type(c: Char)
    fun typeText(text: String)
    fun disconnect()
}
