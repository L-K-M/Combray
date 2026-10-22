package ch.lkmc.combray.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.integration.SaveStatus

/** Back, address, Save, Share, Recent and More: every routine action within thumb reach. */
@Composable
fun BottomBar(
    tab: ActiveTab?,
    openTabs: Int,
    saveStatus: PageSaveStatus,
    /** Whether Back has somewhere to go: BrowserScreen decides, for this button and system Back alike. */
    backEnabled: Boolean,
    onBack: () -> Unit,
    onAddress: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRecent: () -> Unit,
    onMore: () -> Unit,
    onCopyAddress: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                AddressChip(tab, onAddress, onCopyAddress, Modifier.weight(1f))
                SaveButton(saveStatus, enabled = tab?.url != null, onSave)
                IconButton(onClick = onShare, enabled = tab?.url != null) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                }
                val recentLabel = pluralStringResource(R.plurals.action_recent_with_count, openTabs, openTabs)
                IconButton(onClick = onRecent, modifier = Modifier.semantics { contentDescription = recentLabel }) {
                    // A count, not an alert: pine rather than Material's error red.
                    BadgedBox(
                        badge = {
                            if (openTabs > 0) {
                                Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Text(tabCountLabel(openTabs))
                                }
                            }
                        },
                    ) {
                        Icon(TabStackIcon, contentDescription = null)
                    }
                }
                IconButton(onClick = onMore) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
            }
            HorizontalDivider(Modifier.align(Alignment.TopCenter), color = MaterialTheme.colorScheme.outlineVariant)
            // Overlaid on the bar's top edge: loading never moves or covers an action.
            if (tab?.loading == true) {
                val loading = stringResource(R.string.loading)
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).semantics { contentDescription = loading },
                )
            }
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
private fun AddressChip(tab: ActiveTab?, onClick: () -> Unit, onCopy: () -> Unit, modifier: Modifier) {
    val url = tab?.url
    val host = url?.let { displayHost(it) }
    // Cleartext is a property of the scheme. HTTPS is not treated as verified.
    val cleartext = url?.startsWith("http:", ignoreCase = true) == true
    val connection = if (cleartext) stringResource(R.string.address_insecure) else null
    val failed = if (tab?.loadFailed == true) stringResource(R.string.address_load_failed) else null
    val label = listOfNotNull(stringResource(R.string.address_edit), host, connection, failed).joinToString(", ")

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (url != null) onCopy else null,
                    onLongClickLabel = if (url != null) stringResource(R.string.action_copy_address) else null,
                    role = Role.Button,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                }
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
                host ?: stringResource(R.string.address_placeholder),
                maxLines = 1,
                overflow = if (host != null) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Beyond this many open tabs the badge shows a capped label instead of the count. */
private const val BADGE_LIMIT = 99

/** The save icon grows into place from this fraction of its size as it fades in. */
private const val SAVE_ICON_START_SCALE = 0.6f
