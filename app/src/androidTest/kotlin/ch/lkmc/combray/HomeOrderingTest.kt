package ch.lkmc.combray

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.combray.core.browser.BrowserSettings
import ch.lkmc.combray.core.browser.HomeLink
import ch.lkmc.combray.core.browser.HomeLinksState
import ch.lkmc.combray.ui.CombrayTheme
import ch.lkmc.combray.ui.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Lifecycle proof for the native Home projection; synthetic link updates require no renderer. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeOrderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun automaticLinksKeepTheirOrderAfterBackgrounding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("home-order-check", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val settings = BrowserSettings(preferences)
        val links = MutableStateFlow(state("a.test", "b.test"))
        compose.setContent {
            CombrayTheme {
                HomeScreen(
                    links = links,
                    continuation = flowOf(null),
                    settings = settings,
                    onOpenUrl = {}, onContinue = {}, onEdit = {}, onAdd = {}, onHide = {}, onUnpin = {},
                )
            }
        }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Open b.test"), Device.SCREEN_TIMEOUT_MS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(Device.SCREEN_TIMEOUT_MS) { links.subscriptionCount.value == 0 }
        links.value = state("b.test", "a.test", "c.test")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // A new item proves the resumed collector applied the changed dataset before measuring.
        compose.waitUntilAtLeastOneExists(hasContentDescription("Open c.test"), Device.SCREEN_TIMEOUT_MS)

        val a = compose.onNodeWithContentDescription("Open a.test").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithContentDescription("Open b.test").getUnclippedBoundsInRoot()
        assertTrue("returning to Home must not swap its automatic destinations", a.left < b.left)
    }

    private fun state(vararg sites: String) = HomeLinksState(
        emptyList(), emptyList(), sites.map { HomeLink(it, it, "https://$it/", null, null) },
    )
}
