package ch.lkmc.combray.ui

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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
    saveStatus: SaveStatus,
    onBack: () -> Unit,
    onAddress: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRecent: () -> Unit,
    onMore: () -> Unit,
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
                IconButton(onClick = onBack, enabled = tab?.canGoBack == true) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                AddressChip(tab, onAddress, Modifier.weight(1f))
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
                                    Text(openTabs.toString())
                                }
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
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

/** One tap saves; the icon says saved, saving or needs attention, the label says it in words. */
@Composable
private fun SaveButton(status: SaveStatus, enabled: Boolean, onSave: () -> Unit) {
    val (icon, label) = when (status) {
        SaveStatus.Saved -> Icons.Filled.Star to stringResource(R.string.save_state_saved)
        SaveStatus.Pending -> StarBorder to stringResource(R.string.save_state_pending)
        is SaveStatus.NeedsAttention -> Icons.Filled.Warning to stringResource(R.string.save_state_attention)
        SaveStatus.NotConfigured, SaveStatus.Unsaved -> StarBorder to stringResource(R.string.action_save)
    }
    // A live region: TalkBack announces when the save finishes or needs attention.
    IconButton(onClick = onSave, enabled = enabled, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label)
            if (status == SaveStatus.Pending) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/** Shows where the page is from and whether the connection is encrypted; opens the editor. */
@Composable
private fun AddressChip(tab: ActiveTab?, onClick: () -> Unit, modifier: Modifier) {
    val url = tab?.url
    val host = url?.let { displayHost(it) }
    val secure = url?.startsWith("https:", ignoreCase = true) == true
    val connection = when {
        url == null -> null
        secure -> stringResource(R.string.address_secure)
        else -> stringResource(R.string.address_insecure)
    }
    val failed = if (tab?.loadFailed == true) stringResource(R.string.address_load_failed) else null
    val label = listOfNotNull(stringResource(R.string.address_edit), host, connection, failed).joinToString(", ")

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            contentDescription = label
        },
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (url != null) {
                Icon(
                    when {
                        tab.loadFailed -> Icons.Filled.Warning
                        secure -> Icons.Filled.Lock
                        else -> Icons.Outlined.Info
                    },
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

/**
 * Material's "star_border" (Apache License 2.0, like the icon library). The core icon set has no
 * empty star: its outlined Star is filled, which made saved and unsaved pages look the same.
 */
private val StarBorder: ImageVector = ImageVector.Builder("StarBorder", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24z" +
                "M12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
        ),
        fill = SolidColor(Color.Black),
    )
    .build()
