package com.xblackbytesx.slopstream.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.xblackbytesx.slopstream.ui.nav.AppChrome
import com.xblackbytesx.slopstream.ui.search.SearchBar
import com.xblackbytesx.slopstream.ui.theme.SlopstreamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Remote control rough edges found on the Shield (2026-10-24). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvNavigationTest {
    @get:Rule val rule = createComposeRule()

    /**
     * Opening the player hides the rail. The screens must keep their state through that (the
     * tile to focus on the way back): the content stays in the same place in the tree.
     */
    @Test
    fun hidingTheRailKeepsTheScreensState() {
        var showNav by mutableStateOf(true)
        rule.setContent {
            SlopstreamTheme(isTv = true) {
                AppChrome(current = 1, onSelect = {}, showNav = showNav) { modifier ->
                    var taps by rememberSaveable { mutableIntStateOf(0) }
                    Column(modifier) { Text("Tapped $taps", Modifier.testTag("count").clickable { taps++ }) }
                }
            }
        }
        rule.onNodeWithTag("count").performClick()
        rule.onNodeWithText("Tapped 1").assertExists()
        showNav = false
        rule.waitForIdle()
        showNav = true
        rule.waitForIdle()
        rule.onNodeWithText("Tapped 1").assertExists()
    }

    @Test
    fun downLeavesTheSearchField() {
        rule.setContent {
            SlopstreamTheme(isTv = true) {
                Column {
                    SearchBar(query = "linus", global = false, onQuery = {}, onGlobal = {}, onSubmit = {}, globalService = null)
                    Text("First result", Modifier.testTag("result").focusable())
                }
            }
        }
        val field = rule.onNodeWithText("linus")
        field.performClick()
        field.requestFocus()
        field.assertIsFocused()
        field.performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("result").assertIsFocused()
        field.assertIsNotFocused()
    }
}
