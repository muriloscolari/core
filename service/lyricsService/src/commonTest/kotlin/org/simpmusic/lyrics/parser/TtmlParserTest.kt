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
}
