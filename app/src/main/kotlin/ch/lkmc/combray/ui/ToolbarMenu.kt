package ch.lkmc.combray.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab
import kotlin.math.abs

/** Coordinates stay in one window so the press that opens the menu can also select a row. */
internal class ToolbarMenuState {
    var expanded by mutableStateOf(false)
        private set
    var anchor by mutableStateOf(Rect.Zero)
    var gestureEpoch by mutableLongStateOf(0L)
        private set
    val isDragging get() = pointer != null
    private var pointer by mutableStateOf<Offset?>(null)
    private val rows = mutableStateMapOf<MoreAction, Rect>()

    val selected: MoreAction?
        get() = pointer?.let { position -> rows.entries.firstOrNull { it.value.contains(position) }?.key }

    fun toggle() {
        if (expanded) dismiss() else expanded = true
    }

    fun drag(position: Offset) {
        expanded = true
        pointer = position
    }

    fun place(action: MoreAction, bounds: Rect) { rows[action] = bounds }

    fun remove(action: MoreAction) { rows.remove(action) }

    fun dismiss() {
        // Restart the button's handler so a held finger cannot reopen a dismissed menu.
        gestureEpoch++
        expanded = false
        pointer = null
        rows.clear()
    }
}

@Composable
internal fun ToolbarMenuButton(state: ToolbarMenuState, onSelect: (MoreAction) -> Unit) {
    val select by rememberUpdatedState(onSelect)
    IconButton(
        onClick = state::toggle,
        modifier = Modifier
            .onGloballyPositioned { state.anchor = it.boundsInRoot() }
            .pointerInput(state, state.gestureEpoch) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.size != 1) return@awaitEachGesture
                            val change = event.changes.single()
                            if (change.id != down.id || change.isConsumed) return@awaitEachGesture

                            val distance = change.position - down.position
                            if (!dragging) {
                                if (distance.getDistance() <= viewConfiguration.touchSlop) {
                                    if (!change.pressed) return@awaitEachGesture
                                    continue
                                }
                                // Sideways drags still belong to the toolbar's tab switcher.
                                if (distance.y >= -abs(distance.x)) return@awaitEachGesture
                                dragging = true
                            }

                            change.consume()
                            state.drag(state.anchor.topLeft + change.position)
                            if (change.pressed) continue

                            val action = state.selected
                            state.dismiss()
                            action?.let(select)
                            break
                        }
                    } finally {
                        // A cancellation or second finger never triggers the highlighted entry.
                        if (dragging) state.dismiss()
                    }
                }
            },
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
    }
}

/** In-window overlay: no sheet transition, popup window or focus handoff delays selection. */
@Composable
internal fun ToolbarMenu(state: ToolbarMenuState, tab: ActiveTab?, readerDisplay: ReaderDisplay, onSelect: (MoreAction) -> Unit) {
    if (!state.expanded) return
    BackHandler(onBack = state::dismiss)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val title = stringResource(R.string.action_more)
    val close = stringResource(R.string.action_close_menu)
    var viewport by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().imePadding()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .onGloballyPositioned { viewport = it.boundsInRoot() }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // The first finger belongs to the button; a new overlay press is another finger.
                    if (!state.isDragging) return@awaitEachGesture
                    down.consume()
                    state.dismiss()
                }
            },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = MENU_SCRIM_ALPHA))
            .clickable(interactionSource = null, indication = null, onClick = state::dismiss)
            .semantics { contentDescription = close })
        val available = with(density) { (state.anchor.top - viewport.top).toDp() }.coerceIn(0.dp, maxHeight)
        val end = with(density) {
            (if (direction == LayoutDirection.Ltr) viewport.right - state.anchor.right else state.anchor.left - viewport.left).toDp()
        }.coerceAtLeast(0.dp)
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = maxHeight - available, end = end)
                .width(MENU_WIDTH).heightIn(max = available)
                .semantics { paneTitle = title; isTraversalGroup = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
        ) {
            MoreActions(tab, readerDisplay, onSelect, state)
        }
    }
}

@Composable
internal fun MoreActions(tab: ActiveTab?, readerDisplay: ReaderDisplay, onSelect: (MoreAction) -> Unit, state: ToolbarMenuState? = null) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        for (action in moreActions(tab, readerDisplay)) {
            DisposableEffect(state, action) { onDispose { state?.remove(action) } }
            val highlighted = state?.selected == action
            ListItem(
                headlineContent = { Text(stringResource(action.label)) },
                leadingContent = { Icon(action.icon, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
                    .onGloballyPositioned { state?.place(action, it.boundsInRoot()) }
                    .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .semantics { selected = highlighted }
                    .clickable { onSelect(action) },
                colors = sheetListItemColors(),
            )
        }
    }
}

internal enum class MoreAction(val icon: ImageVector, @StringRes val label: Int) {
    FORWARD(Icons.AutoMirrored.Filled.ArrowForward, R.string.action_forward),
    RELOAD(Icons.Filled.Refresh, R.string.action_reload),
    SHARE(Icons.Filled.Share, R.string.action_share),
    PAGE_INFO(Icons.Outlined.Info, R.string.action_page_info),
    FIND(Icons.Filled.Search, R.string.action_find_in_page),
    PLAY_MEDIA(Icons.Filled.PlayArrow, R.string.action_play_media),
    TEXT_SIZE(TextSizeIcon, R.string.action_text_size),
    SETTINGS(Icons.Filled.Settings, R.string.action_settings),
}

internal fun moreActions(tab: ActiveTab?, readerDisplay: ReaderDisplay): List<MoreAction> = buildList {
    if (tab?.canGoForward == true) add(MoreAction.FORWARD)
    if (tab?.url != null) {
        add(MoreAction.RELOAD)
        add(MoreAction.SHARE)
        add(MoreAction.PAGE_INFO)
        if (readerDisplay == ReaderDisplay.PAGE) add(MoreAction.FIND)
    }
    if (tab?.media?.isNotEmpty() == true) add(MoreAction.PLAY_MEDIA)
    add(MoreAction.TEXT_SIZE)
    add(MoreAction.SETTINGS)
}

private val MENU_WIDTH = 280.dp
private const val MENU_SCRIM_ALPHA = 0.32f
