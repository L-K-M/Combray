package ch.lkmc.combray.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader view's type scale keeps the outline readable: a heading never prints below its text. */
class ReaderTypeTest {
    @Test
    fun noHeadingIsSmallerThanTheBody() {
        for (level in 1..6) {
            val size = ReaderType.heading(level).fontSize
            assertTrue("h$level is $size, the body ${ReaderType.body.fontSize}", size >= ReaderType.body.fontSize)
        }
    }

    @Test
    fun aDeeperHeadingIsNeverLargerAndTheTitleIsTheLargest() {
        for (level in 2..6) {
            assertTrue("h$level is larger than h${level - 1}", ReaderType.heading(level).fontSize <= ReaderType.heading(level - 1).fontSize)
        }
        assertTrue("the title is smaller than h1", ReaderType.title.fontSize > ReaderType.heading(1).fontSize)
    }
}
