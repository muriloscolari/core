package org.simpmusic.lyrics.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TtmlParserTest {

    @Test
    fun testSyllablesMergedIntoWords() {
        val ttml = """
            <tt>
              <body>
                <div>
                  <p begin="00:15.000" end="00:18.000">
                    <span begin="00:15.000" end="00:15.200">I'm </span><span begin="00:15.200" end="00:15.500">al</span><span begin="00:15.500" end="00:15.800">rea</span><span begin="00:15.800" end="00:16.100">dy </span><span begin="00:16.100" end="00:16.300">in </span><span begin="00:16.300" end="00:16.600">lo</span><span begin="00:16.600" end="00:17.000">ve</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = parseTtmlLyrics(ttml)
        val line = lyrics.lyrics?.lines?.firstOrNull()
        assertNotNull(line)
        assertEquals("<00:15.00>I'm <00:15.20>already <00:16.10>in <00:16.30>love", line.words)
    }

    @Test
    fun testKoreanAndEnglishMixedSyllables() {
        val ttml = """
            <tt>
              <body>
                <div>
                  <p begin="00:20.000" end="00:25.000">
                    <span begin="00:20.000" end="00:20.300">하</span><span begin="00:20.300" end="00:20.500">나 </span><span begin="00:20.500" end="00:20.700">둘 </span><span begin="00:20.700" end="00:21.000">모</span><span begin="00:21.000" end="00:21.200">인 </span><span begin="00:21.200" end="00:21.500">Pie</span><span begin="00:21.500" end="00:21.700">ce</span><span begin="00:21.700" end="00:22.000">s</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = parseTtmlLyrics(ttml)
        val line = lyrics.lyrics?.lines?.firstOrNull()
        assertNotNull(line)
        assertEquals("<00:20.00>하나 <00:20.50>둘 <00:20.70>모인 <00:21.20>Pieces", line.words)
    }

    @Test
    fun testSpacesBetweenSpanTags() {
        val ttml = """
            <tt>
              <body>
                <div>
                  <p begin="00:10.000" end="00:15.000">
                    <span begin="00:10.000" end="00:10.200">al</span><span begin="00:10.200" end="00:10.500">ready</span> <span begin="00:10.500" end="00:11.000">in</span> <span begin="00:11.000" end="00:11.500">love</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = parseTtmlLyrics(ttml)
        val line = lyrics.lyrics?.lines?.firstOrNull()
        assertNotNull(line)
        assertEquals("<00:10.00>already <00:10.50>in <00:11.00>love", line.words)
    }

    @Test
    fun testIndentedTtmlSpansWithNewlines() {
        val ttml = """
            <tt>
              <body>
                <div>
                  <p begin="00:10.000" end="00:15.000">
                    <span begin="00:10.000" end="00:10.200">Pie</span>
                    <span begin="00:10.200" end="00:10.500">ce</span>
                    <span begin="00:10.500" end="00:11.000">s</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = parseTtmlLyrics(ttml)
        val line = lyrics.lyrics?.lines?.firstOrNull()
        assertNotNull(line)
        assertEquals("<00:10.00>Pieces", line.words)
    }

    @Test
    fun testRichSyncWithPlainLyricsAlignment() {
        val rich = """
            [00:12.26] <00:12.26>I'm <00:12.85>brea <00:13.99>king <00:14.23>down <00:14.57>
            [00:30.19] <00:30.19>눈 <00:30.50>을 <00:30.81>뜨 <00:31.05>면 <00:31.30>다 <00:31.70>를 <00:32.12>까 <00:32.90>
        """.trimIndent()
        val plain = """
            I'm breaking down
            눈을 뜨면 다를까
        """.trimIndent()

        val lyrics = parseRichSyncLyrics(rich, plainLyric = plain)
        val lines = lyrics.lyrics?.lines
        assertNotNull(lines)
        assertEquals("<00:12.26>I'm <00:12.85>breaking <00:14.23>down", lines[0].words)
        assertEquals("<00:30.19>눈을 <00:30.81>뜨면 <00:31.30>다를까", lines[1].words)
    }

    @Test
    fun testRichSyncHeuristicMergingWithoutPlainLyrics() {
        val rich = """
            [00:15.00] <00:15.00>I'm <00:15.20>a <00:15.30>l <00:15.40>rea <00:15.50>dy <00:15.60>in <00:15.70>lo <00:15.80>ve
            [00:20.00] <00:20.00>Make <00:20.20>a <00:20.30>wi <00:20.50>sh <00:21.00>Pie <00:21.20>ce <00:21.40>s
        """.trimIndent()

        val lyrics = parseRichSyncLyrics(rich)
        val lines = lyrics.lyrics?.lines
        assertNotNull(lines)
        assertEquals("<00:15.00>I'm <00:15.20>already <00:15.60>in <00:15.70>love", lines[0].words)
        assertEquals("<00:20.00>Make <00:20.20>a <00:20.30>wish <00:21.00>Pieces", lines[1].words)
    }
}
