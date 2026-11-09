package com.xblackbytesx.slopstream.data

import com.xblackbytesx.slopstream.data.SearchHistoryRules.record
import com.xblackbytesx.slopstream.data.SearchHistoryRules.suggestions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun List<PastSearch>.queries() = map { it.query }

    @Test
    fun aRepeatMovesToTheFrontInsteadOfAddingACopy() {
        var list = emptyList<PastSearch>()
        list = record(list, "yt", "linus", false, 1)
        list = record(list, "yt", "jerryrig", false, 2)
        list = record(list, "yt", "  Linus  ", false, 3)
        assertEquals(listOf("Linus", "jerryrig"), list.queries())
    }

    @Test
    fun tooShortIsNotKeptAndSpacesAreTidied() {
        assertTrue(record(emptyList(), "yt", " a ", false, 1).isEmpty())
        assertEquals(listOf("pixel 10 durability"), record(emptyList(), "yt", "pixel   10 durability ", false, 1).queries())
    }

    @Test
    fun onceGlobalStaysGlobal() {
        var list = record(emptyList(), "yt", "pixel", true, 1)
        list = record(list, "yt", "pixel", false, 2)
        assertTrue(list.single().global)
    }

    @Test
    fun eachServiceKeepsItsOwnAndItsOwnLimit() {
        var list = record(emptyList(), "fp", "wan show", false, 0)
        repeat(SearchHistoryRules.KEEP + 5) { list = record(list, "yt", "query $it", false, it.toLong()) }
        assertEquals(SearchHistoryRules.KEEP, list.count { it.source == "yt" })
        assertEquals("query ${SearchHistoryRules.KEEP + 4}", list.first().query)
        assertEquals(listOf("wan show"), suggestions(list, "fp", "").queries())
    }

    @Test
    fun typingSuggestsWhatContainsEveryWord() {
        var list = emptyList<PastSearch>()
        for (q in listOf("pixel 10 durability", "pixel case", "linus tech tips", "shield tv")) list = record(list, "yt", q, false, 0)
        assertEquals(listOf("pixel case", "pixel 10 durability"), suggestions(list, "yt", "pix").queries())
        assertEquals(listOf("pixel 10 durability"), suggestions(list, "yt", "durab pix").queries())
        assertTrue(suggestions(list, "yt", "nothing").isEmpty())
    }

    @Test
    fun theSearchJustRunKeepsTheWholeRowInPlace() {
        var list = emptyList<PastSearch>()
        for (q in listOf("a1", "b2", "c3")) list = record(list, "yt", q, false, 0)
        // Running "b2" from the row must not shrink the row under the remote's focus.
        assertEquals(listOf("c3", "b2", "a1"), suggestions(list, "yt", "B2").queries())
    }

    @Test
    fun keptInAFileAndReadBack() = runTest {
        val file = tmp.newFile("history.json")
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val history = SearchHistory(file, clock = { 5 }, scope = scope)
        scope.advanceUntilIdle()
        history.record("yt", "jerryrig", global = false)
        history.record("yt", "pixel", global = true)
        history.remove("yt", "JERRYRIG")
        history.record("fp", "wan show", global = false)
        history.clear("fp")
        scope.advanceUntilIdle()

        val again = SearchHistory(file, scope = scope)
        scope.advanceUntilIdle()
        assertEquals(listOf(PastSearch("yt", "pixel", global = true, at = 5)), again.all.first())
        assertFalse(file.readText().contains("jerryrig"))
    }

    @Test
    fun anUnreadableFileStartsEmpty() = runTest {
        val file = tmp.newFile("history.json").apply { writeText("not json") }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val history = SearchHistory(file, scope = scope)
        scope.advanceUntilIdle()
        assertTrue(history.all.first().isEmpty())
    }
}
