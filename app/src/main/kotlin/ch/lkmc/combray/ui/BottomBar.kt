package ch.lkmc.combray.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.TabCounter
import ch.lkmc.combray.core.browser.TabStep
import ch.lkmc.combray.core.integration.SaveStatus
import kotlin.math.abs

/**
 * Back, address, Save, Close tab, New tab, Recent and Menu within thumb
 * reach. On the new-tab home the bar is a dock instead: one full-width search affordance and
 * labeled Hister, Karakeep, +, Recent and More actions ([onHister] and [onKarakeep] open the
 * Recent sheet on those scopes). A sideways swipe along the bar steps through open tabs
 * ([onSwipeTab]); the Recent sheet stays the visible way to switch, so nothing depends on the
 * gesture.
 */
@Composable
fun BottomBar(
    tab: ActiveTab?,
    openTabs: Int,
    tabCounter: TabCounter,
    saveStatus: PageSaveStatus,
    histerStored: Boolean,
    /** Whether Back has somewhere to go: BrowserScreen decides, for this button and system Back alike. */
    backEnabled: Boolean,
    onBack: () -> Unit,
    onAddress: () -> Unit,
    onSave: () -> Unit,
    onCloseTab: () -> Unit,
    readerOpen: Boolean,
    onStop: () -> Unit,
    onReader: () -> Unit,
    /** Recent on its Hister scope; used by the home dock. */
    onHister: () -> Unit,
    /** Recent on its Karakeep scope; used by the home dock. */
    onKarakeep: () -> Unit,
    /** New tab: a persistent action, always immediately before Recent (owner requirement). */
    onNewTab: () -> Unit,
    onRecent: () -> Unit,
    /** Long-press shortcut: focus the tab used before the focused one. */
    onRecentLongPress: () -> Unit,
    menuButton: @Composable () -> Unit,
    onCopyAddress: () -> Unit,
    onSwipeTab: (TabStep) -> Unit,
    homeSearch: (@Composable () -> Unit)? = null,
    homeMode: AddressEditorMode = AddressEditorMode.IDLE,
    modifier: Modifier = Modifier,
) {
    val swipeDistance = with(LocalDensity.current) { TAB_SWIPE_DISTANCE.toPx() }
    // Editing keeps horizontal drags for the cursor. Otherwise the whole bar shares one gesture.
    val swipeEnabled = tab?.url != null || homeMode == AddressEditorMode.IDLE
    val swipeTab by rememberUpdatedState(onSwipeTab)
    var swipeProgress by remember(tab?.id, swipeEnabled) { mutableFloatStateOf(0f) }
    val previewDistance = with(LocalDensity.current) { TAB_SWIPE_PREVIEW_DISTANCE.toPx() }
    val swipeOffset by animateFloatAsState(
        targetValue = swipeProgress * previewDistance,
        animationSpec = if (swipeProgress == 0f) spring() else snap(),
        label = "tab swipe offset",
    )
    val swipe = Modifier.pointerInput(tab?.id, swipeDistance, swipeEnabled) {
        if (!swipeEnabled) return@pointerInput

        awaitEachGesture {
            try {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent()
                    // Multi-touch and a child's claimed gesture cannot select another tab.
                    if (event.changes.size != 1) return@awaitEachGesture
                    val change = event.changes.single()
                    if (change.id != down.id || change.isConsumed) return@awaitEachGesture

                    val distance = change.position - down.position
                    if (!dragging) {
                        if (distance.getDistance() <= viewConfiguration.touchSlop) {
                            if (!change.pressed) return@awaitEachGesture
                            continue
                        }
                        // Lock intent at the first drag, so a vertical gesture stays vertical.
                        if (abs(distance.x) <= abs(distance.y)) return@awaitEachGesture
                        dragging = true
                    }

                    // The controls follow the thumb, but the gesture's coordinates stay fixed.
                    swipeProgress = (distance.x / swipeDistance).coerceIn(-1f, 1f)
                    // Cancel the button's click, but switch only on release, never cancellation.
                    change.consume()
                    if (change.pressed) continue

                    when {
                        distance.x >= swipeDistance -> swipeTab(TabStep.OLDER)
                        distance.x <= -swipeDistance -> swipeTab(TabStep.NEWER)
                    }
                    break
                }
            } finally {
                // Also clear a cancelled gesture or one interrupted by another tab or editing.
                swipeProgress = 0f
            }
        }
    }
    // The bar's color runs under the navigation bar and, in landscape, a cutout; only its
    // actions keep clear of them. A hairline along its top edge separates it from the page.
    // Explicit content colours: contentColorFor matches by value, and a role sharing a tier's
    // value would give its own on-colour.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Box {
            BoxWithConstraints(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .then(swipe)
                    .fillMaxWidth(),
            ) {
                // Null, blank or home: the dock, not browsing controls. One full-width search
                // affordance; labeled actions for the two services, +, Recent and More. The
                // browsing bar's disabled Back/Save/Share have no place here.
                val home = tab?.url == null
                // Six 48dp actions leave a narrow phone's chip too little room for a site name, so
                // the address takes a row of its own above them (owner decision, 22 October 2026).
                val stacked = maxWidth < STACKED_BAR_BELOW
                Column(Modifier.graphicsLayer { translationX = swipeOffset }) {
                    if (home && homeSearch != null) {
                        homeSearch()
                    } else if (stacked || home) {
                        AddressChip(
                             tab, histerStored, onAddress, onCopyAddress, readerOpen, onStop, onReader,
                            hint = if (home) R.string.home_search_hint else R.string.address_placeholder,
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                        )
                    }
                    if (home) {
                        if (homeMode == AddressEditorMode.IDLE) {
                            HomeActions(openTabs, tabCounter, onHister, onKarakeep, onNewTab, onRecent, onRecentLongPress, menuButton)
                        }
                        return@Column
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (stacked) STACKED_ACTION_ROW_HEIGHT else BAR_HEIGHT)
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = if (stacked) Arrangement.SpaceEvenly else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, enabled = backEnabled) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                        if (!stacked) AddressChip(tab, histerStored, onAddress, onCopyAddress, readerOpen, onStop, onReader, Modifier.weight(1f))
                        SaveButton(saveStatus, enabled = tab?.url != null, onSave)
                        IconButton(onClick = onCloseTab) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close_tab))
                        }
                        IconButton(onClick = onNewTab) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_new_tab))
                        }
                        // TalkBack keeps the count even when the visual badge is hidden.
                        val recentLabel = pluralStringResource(R.plurals.action_recent_with_count, openTabs, openTabs)
                        val switchPrevious = stringResource(R.string.recent_switch_previous)
                        // Combined click/long-click: IconButton has no long-press overload. Same 48dp
                        // target, role and ripple; the long-press is a shortcut beside the Recent sheet's rows.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(ACTION_TARGET)
                                .combinedClickable(
                                    interactionSource = null,
                                    indication = ripple(bounded = false, radius = 20.dp),
                                    role = Role.Button,
                                    onLongClickLabel = switchPrevious,
                                    onClick = onRecent,
                                    onLongClick = onRecentLongPress,
                                )
                                .semantics { contentDescription = recentLabel },
                        ) {
                            // A count, not an alert: pine rather than Material's error red.
                            BadgedBox(
                                badge = {
                                    if (tabCounter == TabCounter.SHOWN && openTabs > 0) {
                                        Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                            Text(tabCountLabel(openTabs))
                                        }
                                    }
                                },
                            ) {
                                Icon(TabStackIcon, contentDescription = null)
                            }
                        }
                        menuButton()
                    }
                }
            }
            HorizontalDivider(Modifier.align(Alignment.TopCenter), color = MaterialTheme.colorScheme.outlineVariant)
            // Overlaid on the bar's top edge: loading never moves or covers an action.
            if (swipeProgress != 0f) {
                val label = stringResource(if (swipeProgress > 0f) R.string.tab_swipe_older else R.string.tab_swipe_newer)
                // Fill in the physical swipe direction, including in an RTL interface.
                CompositionLocalProvider(LocalLayoutDirection provides if (swipeProgress > 0f) LayoutDirection.Ltr else LayoutDirection.Rtl) {
                    LinearProgressIndicator(
                        progress = { abs(swipeProgress) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.TopCenter).semantics { contentDescription = label },
                    )
                }
            } else if (tab?.loading == true) {
                val loading = stringResource(R.string.loading)
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).semantics { contentDescription = loading },
                )
            }
        }
    }
}

/**
 * The home dock's actions. Labeled so the service shortcuts read as destinations; they reflow
 * onto a second line when the labels no longer fit (narrow screens, large fonts). + and Recent
 * are one unit, so a wrap cannot put New tab anywhere but beside Recent.
 */
@Composable
private fun HomeActions(
    openTabs: Int,
    tabCounter: TabCounter,
    onHister: () -> Unit,
    onKarakeep: () -> Unit,
    onNewTab: () -> Unit,
    onRecent: () -> Unit,
    onRecentLongPress: () -> Unit,
    menuButton: @Composable () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().heightIn(min = STACKED_ACTION_ROW_HEIGHT).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        DockAction(stringResource(R.string.recent_scope_hister), onHister)
        DockAction(stringResource(R.string.recent_scope_karakeep), onKarakeep)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNewTab) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_new_tab))
            }
            RecentDockAction(openTabs, tabCounter, onRecent, onRecentLongPress)
        }
        menuButton()
    }
}

/** A labeled home-dock action: the service or surface it opens, in words. */
@Composable
private fun DockAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = ACTION_TARGET)) {
        Text(label)
    }
}

/**
 * Recent as a labeled dock action: the icon button's open-tab count (badge when enabled,
 * TalkBack either way) and long-press shortcut preserved.
 */
@Composable
private fun RecentDockAction(openTabs: Int, tabCounter: TabCounter, onClick: () -> Unit, onLongClick: () -> Unit) {
    val recentLabel = pluralStringResource(R.plurals.action_recent_with_count, openTabs, openTabs)
    val switchPrevious = stringResource(R.string.recent_switch_previous)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = ACTION_TARGET)
            .combinedClickable(
                interactionSource = null,
                indication = ripple(),
                role = Role.Button,
                onLongClickLabel = switchPrevious,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = recentLabel }
            .padding(horizontal = 12.dp),
    ) {
        BadgedBox(
            badge = {
                if (tabCounter == TabCounter.SHOWN && openTabs > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Text(tabCountLabel(openTabs))
                    }
                }
            },
        ) {
            Text(stringResource(R.string.action_recent))
        }
    }
}

/** The badge's visible text: the exact count, or a capped label once it no longer fits. */
@Composable
private fun tabCountLabel(openTabs: Int): String =
    if (openTabs > BADGE_LIMIT) stringResource(R.string.recent_badge_many, BADGE_LIMIT) else openTabs.toString()

/** One tap saves; the icon says saved, saving, waiting or needs attention, the label in words. */
@Composable
private fun SaveButton(save: PageSaveStatus, enabled: Boolean, onSave: () -> Unit) {
    val status = save.status
    val (icon, label) = when (status) {
        SaveStatus.Saved -> BookmarkAddedIcon to stringResource(R.string.save_state_saved)
        SaveStatus.Pending -> BookmarkIcon to stringResource(R.string.save_state_pending)
        // Karakeep could not be reached: nothing is being sent, so no spinner.
        SaveStatus.Waiting -> ScheduleIcon to stringResource(R.string.save_state_waiting)
        is SaveStatus.NeedsAttention -> Icons.Filled.Warning to stringResource(R.string.save_state_attention)
        SaveStatus.NotConfigured, SaveStatus.Unsaved -> BookmarkBorderIcon to stringResource(R.string.action_save)
    }

    // A save that ends on the shown page is felt too. Through the view, so the system's touch
    // feedback setting applies.
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf(save) }
    LaunchedEffect(save) {
        when (saveFeedback(previous, save)) {
            SaveFeedback.CONFIRM -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            SaveFeedback.REJECT -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            null -> Unit
        }
        previous = save
    }

    // A live region: TalkBack announces when the save finishes or needs attention. The label is
    // the button's, not the icon's: during the cross-fade both icons are composed.
    IconButton(
        onClick = onSave,
        enabled = enabled,
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = label
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                icon,
                transitionSpec = { (scaleIn(initialScale = SAVE_ICON_START_SCALE) + fadeIn()) togetherWith fadeOut() },
                label = "save icon",
            ) { shown -> Icon(shown, contentDescription = null) }
            if (status == SaveStatus.Pending) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** Shows the site name. A lock is not drawn: the engine does not report a verified connection. */
@Composable
private fun AddressChip(
    tab: ActiveTab?,
    histerStored: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    readerOpen: Boolean,
    onStop: () -> Unit,
    onReader: () -> Unit,
    modifier: Modifier,
    @StringRes hint: Int = R.string.address_placeholder,
) {
    val url = tab?.url
    val host = url?.let { displayHost(it) }
    // Cleartext is a property of the scheme (a blob: page's is its maker's). HTTPS is not
    // treated as verified.
    val cleartext = url?.let(PageConnection::of) == PageConnection.CLEARTEXT
    val connection = if (cleartext) stringResource(R.string.address_insecure) else null
    val failed = if (tab?.loadFailed == true) stringResource(R.string.address_load_failed) else null
    val hister = if (histerStored) stringResource(R.string.address_sent_hister) else null
    val label = listOfNotNull(stringResource(R.string.address_edit), host, connection, failed, hister).joinToString(", ")

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Separate targets: stopping or toggling Reader must not edit or copy the address.
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = ACTION_TARGET)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = if (url != null) onCopy else null,
                        onLongClickLabel = if (url != null) stringResource(R.string.action_copy_address) else null,
                        role = Role.Button,
                    )
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (url != null && (tab.loadFailed || cleartext)) {
                Icon(
                    if (tab.loadFailed) Icons.Filled.Warning else Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            // A long host loses its start, never the registrable domain that names the site.
            Text(
                host ?: stringResource(hint),
                maxLines = 1,
                overflow = if (host != null) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            // A quiet mark that Hister accepted this page; the address stays the chip's main text.
            if (histerStored) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp),
                )
            }
            }
            if (url != null) {
                if (tab.loading) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_stop_loading))
                    }
                } else {
                    IconToggleButton(checked = readerOpen, onCheckedChange = { onReader() }) {
                        Icon(ReaderIcon, contentDescription = stringResource(if (readerOpen) R.string.action_leave_reader_view else R.string.action_reader_view))
                    }
                }
            }
        }
    }
}

/** The single-row bar's height. */
private val BAR_HEIGHT = 64.dp

/** One action's touch target: Android's and Material's 48dp minimum. */
private val ACTION_TARGET = 48.dp

/** The room the address needs beside the actions to still name a site. */
private val ADDRESS_MIN_WIDTH = 132.dp + ACTION_TARGET

/**
 * Below this width the address gets its own row: six actions (Back, Save, Close tab, New tab,
 * Recent, More) need 6 · 48dp, and the field needs its room beside them. The old five-action
 * rule was 400dp.
 */
private val STACKED_BAR_BELOW = ACTION_TARGET * 6 + ADDRESS_MIN_WIDTH

/** The action row's height under the address row: 48dp targets with a little room. */
private val STACKED_ACTION_ROW_HEIGHT = 56.dp

/** How far a drag along the bar must go to switch tabs: well past a tap's slop, short of the bar. */
private val TAB_SWIPE_DISTANCE = 56.dp

/** Bounded motion previews the swipe without moving its touch target or the page. */
private val TAB_SWIPE_PREVIEW_DISTANCE = 24.dp

/** Beyond this many open tabs the badge shows a capped label instead of the count. */
private const val BADGE_LIMIT = 99

/** The save icon grows into place from this fraction of its size as it fades in. */
private const val SAVE_ICON_START_SCALE = 0.6f
