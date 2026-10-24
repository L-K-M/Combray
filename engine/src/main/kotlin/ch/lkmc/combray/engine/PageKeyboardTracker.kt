package ch.lkmc.combray.engine

/**
 * Tells the user's closing of a page field's keyboard (Back, or the keyboard's own key) from
 * the app's. [EngineSurfaceView] reports the app's requests and what the window shows; a
 * closing the user made must reach the page, or its field keeps a focus that a tap cannot
 * turn into a keyboard again.
 *
 * It decides from changes of what the window shows, not from the openings it expects: an
 * opening can be missed (the keyboard was already up, or it came and went between two
 * reports), and a missed opening must not hide the user's closing.
 */
internal class PageKeyboardTracker {
    private var page = PageKeyboard.NONE

    /** The window shows the keyboard, as last reported. */
    private var onScreen = false

    /**
     * The app hid the keyboard while it was on screen, and it has not left yet: that leaving is
     * the app's, even if a page field asked for the keyboard again meanwhile. Should that show
     * cancel the hide before the window reports any change, the next leaving still counts as
     * the app's; the surface logs its decisions, so a device run shows that case.
     */
    private var appHiding = false

    fun showRequested() {
        // Focus moving between fields keeps a keyboard that is already the page's.
        if (page != PageKeyboard.NONE) return
        // A keyboard the app is hiding may still leave and come back.
        page = if (onScreen && !appHiding) PageKeyboard.OPEN else PageKeyboard.REQUESTED
    }

    fun hideRequested() {
        page = PageKeyboard.NONE
        appHiding = onScreen
    }

    /** The window shows the keyboard ([onScreen]) or not, read whenever it may have changed. */
    fun observed(onScreen: Boolean): KeyboardChange {
        if (onScreen == this.onScreen) return KeyboardChange.NONE
        this.onScreen = onScreen

        if (onScreen) {
            appHiding = false
            if (page == PageKeyboard.REQUESTED) page = PageKeyboard.OPEN
            return KeyboardChange.SHOWN
        }
        if (appHiding) {
            // A field that asked again meanwhile stays REQUESTED: its show brings the keyboard back.
            appHiding = false
            return KeyboardChange.HIDDEN
        }
        if (page == PageKeyboard.NONE) return KeyboardChange.HIDDEN
        page = PageKeyboard.NONE
        return KeyboardChange.DISMISSED
    }

    /**
     * The page field loses the keyboard without a closing to observe (the system refused to
     * show it, or the window went). True when a page field had asked for it.
     */
    fun reset(): Boolean {
        val had = page != PageKeyboard.NONE
        page = PageKeyboard.NONE
        return had
    }

    override fun toString() = "page=$page onScreen=$onScreen appHiding=$appHiding"

    enum class KeyboardChange {
        NONE,
        SHOWN,

        /** Hidden by the app, or no page field had it. */
        HIDDEN,

        /** The user closed a page field's keyboard: the page must hear of it. */
        DISMISSED,
    }

    private enum class PageKeyboard {
        NONE,

        /** Asked for and not on screen yet (or on its way out and back); still the page's. */
        REQUESTED,
        OPEN,
    }
}
