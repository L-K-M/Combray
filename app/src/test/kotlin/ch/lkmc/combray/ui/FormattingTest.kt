package ch.lkmc.combray.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingTest {
    private val style = SpanStyle(fontWeight = FontWeight.SemiBold)

    /** The highlighted parts of [text], in order. */
    private fun marked(text: String, query: String): List<String> {
        val highlighted: AnnotatedString = highlightMatches(text, query, style)
        assertEquals(text, highlighted.text)
        assertTrue(highlighted.spanStyles.all { it.item == style })
        return highlighted.spanStyles.map { text.substring(it.start, it.end) }
    }

    @Test
    fun matchesIgnoreCaseAndAccents() {
        assertEquals(listOf("Café", "CAFE"), marked("Café or CAFE", "cafe"))
        assertEquals(listOf("creme"), marked("creme brulee", "crème"))
        assertEquals(listOf("Zürich"), marked("Zürich", "zurich"))
    }

    @Test
    fun aDecomposedAccentIsMarkedWithItsLetter() {
        // "e" followed by U+0301 COMBINING ACUTE ACCENT, written out so no editor composes it.
        val decomposed = "Cafe\u0301 noir"
        assertEquals(listOf("Cafe\u0301"), marked(decomposed, "cafe"))
    }

    @Test
    fun oneHanOrKanaCharacterIsAWordButOneLatinLetterIsNot() {
        assertEquals(listOf("東"), marked("東京", "東"))
        assertEquals(listOf("カ"), marked("カフェ", "カ"))
        assertEquals(emptyList<String>(), marked("a cat", "a"))
    }

    @Test
    fun overlappingAndTouchingTermsMergeIntoOneRange() {
        assertEquals(listOf("Combray"), marked("Combray", "comb bray"))
        assertEquals(listOf("Combray"), marked("Combray", "com bray"))
        assertEquals(listOf("aaaa"), marked("aaaa b", "aa"))
    }

    @Test
    fun everyOccurrenceOfEveryTermIsMarked() {
        assertEquals(listOf("Lost", "Time", "lost"), marked("Lost Time, lost again", "  time\tLOST "))
    }

    @Test
    fun shortTermsBlankQueriesAndMissesMarkNothing() {
        assertEquals(emptyList<String>(), marked("a b c", "a b"))
        assertEquals(emptyList<String>(), marked("Madeleine", ""))
        assertEquals(emptyList<String>(), marked("Madeleine", "   "))
        assertEquals(emptyList<String>(), marked("Madeleine", "tea"))
        assertEquals(emptyList<String>(), marked("", "tea"))
    }

    @Test
    fun markupInTheTextStaysPlainText() {
        assertEquals(listOf("mark"), marked("<b>mark</b>", "mark"))
    }

    @Test
    fun monogramIsTheHostsFirstLetter() {
        assertEquals("E", monogram("https://www.example.org/page"))
        assertEquals("K", monogram("https://kagi.com/search?q=x"))
        assertEquals("9", monogram("https://9gag.com/"))
        assertEquals("+", monogram(null))
    }

    @Test
    fun daysCountByCalendarNotByHours() {
        val zurich = ZoneId.of("Europe/Zurich")
        fun at(text: String) = LocalDateTime.parse(text).atZone(zurich).toInstant().toEpochMilli()

        val lateEvening = calendarDay(at("2026-10-20T23:59"), zurich)
        val justAfterMidnight = calendarDay(at("2026-10-21T00:01"), zurich)
        assertEquals(DayName.YESTERDAY, dayName(lateEvening, justAfterMidnight))
        assertEquals(DayName.TODAY, dayName(justAfterMidnight, calendarDay(at("2026-10-21T23:59"), zurich)))

        // Summer time ends on 25 October 2026: that day has 25 hours, and still counts as one.
        val beforeChange = calendarDay(at("2026-10-24T12:00"), zurich)
        val afterChange = calendarDay(at("2026-10-26T00:30"), zurich)
        assertEquals(2L, afterChange - beforeChange)
    }

    @Test
    fun thePastWeekIsNamedByWeekdayOlderDaysByDate() {
        val today = 20_000L
        assertEquals(DayName.TODAY, dayName(today, today))
        assertEquals(DayName.YESTERDAY, dayName(today - 1, today))
        assertEquals(DayName.WEEKDAY, dayName(today - 2, today))
        assertEquals(DayName.WEEKDAY, dayName(today - 6, today))
        assertEquals(DayName.DATE, dayName(today - 7, today))
        // A clock set back can leave a visit dated after today.
        assertEquals(DayName.DATE, dayName(today + 1, today))
    }

    @Test
    fun pagesWithoutAHostGetAFixedLabelByScheme() {
        assertEquals(HostlessPage.DATA, HostlessPage.of("data:text/html,<!--://accounts.example.com/-->"))
        assertEquals(HostlessPage.DATA, HostlessPage.of("DATA:text/plain,x"))
        assertEquals(HostlessPage.BLANK, HostlessPage.of("about:blank"))
        assertEquals(HostlessPage.BLANK, HostlessPage.of("about:blank#top"))
        assertEquals(HostlessPage.LOCAL_FILE, HostlessPage.of("file:///sdcard/a.html"))
    }

    @Test
    fun hostlessPagesShowOnlyTheirSchemeAndNeverTheirAddress() {
        assertEquals("data:", displayHost("data:text/html,<!--://accounts.example.com/-->"))
        assertEquals("about:blank", displayHost("about:blank#top"))
        assertEquals("file:", displayHost("file:///sdcard/a.html"))
        assertEquals("javascript:", displayHost("javascript:alert(1)"))
        // Surrounding space is ignored, as HostNames.of ignores it.
        assertEquals("data:", displayHost("  data:text/html,x"))
        assertEquals("…", displayHost("accounts.example.com:x"))
        assertEquals("example.org", displayHost("https://www.example.org/a"))
        // A blob: page shows the site that made it.
        assertEquals("a.test", displayHost("blob:https://www.a.test/0f1e"))
        assertEquals("…", displayHost("a.b://accounts.example.com/"))
    }

    @Test
    fun otherHostlessPagesNeverShowTheirOwnText() {
        // A page picks its scheme and path too: "accounts.example.com:" is a valid scheme.
        assertEquals(HostlessPage.OTHER, HostlessPage.of("accounts.example.com:x"))
        assertEquals(HostlessPage.OTHER, HostlessPage.of("about:accounts.example.com"))
        assertEquals(HostlessPage.OTHER, HostlessPage.of("blob:null/0f1e"))
        assertEquals(HostlessPage.OTHER, HostlessPage.of("https://"))
    }
}
