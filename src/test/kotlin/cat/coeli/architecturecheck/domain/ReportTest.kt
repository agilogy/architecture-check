package cat.coeli.architecturecheck.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ReportTest {
    private val finding =
        Finding(
            kind = FindingKind.MembersTooVisible,
            module = "modules/weather",
            subject = "a.B.c(List<String>)",
            detail = "public → internal",
            source = SourceLine("modules/weather/src/main/kotlin/a/B.kt", 7),
        )

    @Test
    fun aReportWithoutFindingsSaysSo() {
        val markdown = Report(emptyList(), sourceLinkBase = null).markdown()

        assertEquals(
            """
            ## Architecture check

            ✅ No findings.

            """
                .trimIndent(),
            markdown,
        )
    }

    @Test
    fun theMarkdownLinksEachFindingToItsSource() {
        val markdown =
            Report(listOf(finding), sourceLinkBase = "https://github.com/o/r/blob/abc").markdown()

        assertContains(
            markdown,
            "| modules/weather | `a.B.c(List<String>)` | public → internal | " +
                "[B.kt:7](https://github.com/o/r/blob/abc/modules/weather/src/main/kotlin/a/B.kt#L7) |",
        )
    }

    @Test
    fun theHtmlEscapesWhatItShows() {
        val html = Report(listOf(finding), sourceLinkBase = null).html()

        assertContains(html, "<code>a.B.c(List&lt;String&gt;)</code>")
    }

    @Test
    fun theTextListsTheFindingsByKind() {
        val text = Report(listOf(finding), sourceLinkBase = null).text()

        assertEquals(
            """
            Architecture check: 1 finding. The build fails until none is left.

            Members more visible than their uses need:
              a.B.c(List<String>) public → internal (modules/weather/src/main/kotlin/a/B.kt:7)
            """
                .trimIndent(),
            text,
        )
    }
}
