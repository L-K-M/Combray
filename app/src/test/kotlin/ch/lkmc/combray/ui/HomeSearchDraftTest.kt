package ch.lkmc.combray.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import ch.lkmc.combray.core.browser.ActiveTab
import ch.lkmc.combray.core.browser.TabCounter
import ch.lkmc.combray.core.integration.SaveStatus
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's search field shares the address editor, which leaves composition when the user
 * switches to a page tab. The draft typed into it must survive the round trip (Phase 1
 * finding: home's search draft was lost on a tab switch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w600dp-h900dp")
class HomeSearchDraftTest {
    @get:Rule val compose = createComposeRule()

    private var shownTab by mutableStateOf(HOME)

    @Test
    fun homesTypedDraftSurvivesLeavingAndReturning() {
        compose.setContent {
            CombrayTheme {
                Box(Modifier.width(600.dp)) {
                    BottomBar(
                        tab = shownTab,
                        openTabs = 2,
                        tabCounter = TabCounter.HIDDEN,
                        saveStatus = PageSaveStatus(shownTab.url, SaveStatus.Unsaved),
                        histerStored = false,
                        backEnabled = false,
                        onBack = {},
                        onAddress = {},
                        onSave = {},
                        onCloseTab = {},
                        readerDisplay = ReaderDisplay.PAGE,
                        onStop = {},
                        onReader = {},
                        onHister = {},
                        onKarakeep = {},
                        onNewTab = {},
                        onRecent = {},
                        onRecentLongPress = {},
                        menuButton = {},
                        onCopyAddress = {},
                        onSwipeTab = {},
                        homeMode = AddressEditorMode.EDITING,
                        homeSearch = {
                            AddressEditor(
                                target = shownTab.id,
                                initialText = "",
                                restartKey = 0,
                                recent = { flowOf(emptyList()) },
                                onDecision = {},
                                onSelectTab = {},
                                onNewTab = {},
                                onDismiss = {},
                                mode = AddressEditorMode.EDITING,
                            )
                        },
                    )
                }
            }
        }

        compose.onNode(hasSetTextAction()).performTextInput("madeleine")
        compose.runOnIdle { shownTab = PAGE }
        compose.runOnIdle { shownTab = HOME }

        compose.onNode(hasSetTextAction()).assert(hasText("madeleine"))
    }

    private companion object {
        val HOME = ActiveTab("tab", null, null, false, false, false, false, false)
        val PAGE = ActiveTab("tab", "https://example.org/", "Example", false, true, false, false, true)
    }
}
