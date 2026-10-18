package com.xrdavies.mob.stream

enum class LiveStatus { Idle, Connecting, Connected, Error }

/** The live view's status and picture size, shared by the thumbnail and the full-screen view. */
data class LiveState(
    val status: LiveStatus = LiveStatus.Idle,
    val detail: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** Touches and keys reach the office Mac only while true (full-screen Control mode). */
    val control: Boolean = false,
)

/**
 * The gestures and keys bar speak in mouse button bits and X11 keysyms; [StreamSession] turns them
 * into mobd's button names and web key codes ([buttonName], [keyCode]).
 */
object LiveKeys {
    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 4
    const val XK_BACKSPACE = 0xff08
    const val XK_TAB = 0xff09
    const val XK_RETURN = 0xff0d
    const val XK_ESCAPE = 0xff1b
    const val XK_DELETE = 0xffff
    const val XK_LEFT = 0xff51
    const val XK_UP = 0xff52
    const val XK_RIGHT = 0xff53
    const val XK_DOWN = 0xff54
    const val XK_SHIFT_L = 0xffe1
    const val XK_CONTROL_L = 0xffe3
    /** Command on the office Mac. */
    const val XK_META_L = 0xffe7
    const val XK_ALT_L = 0xffe9
}
