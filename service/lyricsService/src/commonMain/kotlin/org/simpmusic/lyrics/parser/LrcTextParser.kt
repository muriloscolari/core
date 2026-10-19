package org.simpmusic.lyrics.parser

import com.maxrave.domain.extension.decodeHtmlEntities
import org.simpmusic.lyrics.domain.Lyrics

fun parseSyncedLyrics(data: String): Lyrics {
    val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2})\\](.+)")
    val lines = data.lines()
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    lines.map { line ->
        val matchResult = regex.matchEntire(line)
        if (matchResult != null) {
            val minutes = matchResult.groupValues[1].toLong()
            val seconds = matchResult.groupValues[2].toLong()
            val milliseconds = matchResult.groupValues[3].toLong()
            val timeInMillis = minutes * 60_000L + seconds * 1000L + milliseconds
            // Trim, don't drop a fixed first character. Most providers leave a space after
            // "]", but LRCLIB packs the text right against it — "[00:27.12]boy, you got me"
            // — so removeRange(0, 1) ate the first letter of every single line.
            val rawContent = matchResult.groupValues[4]
            val content = if (rawContent.isBlank()) "♫" else rawContent.trimStart()
            linesLyrics.add(
                Lyrics.LyricsX.Line(
                    endTimeMs = "0",
                    startTimeMs = timeInMillis.toString(),
                    syllables = listOf(),
                    words = decodeHtmlEntities(content),
                ),
            )
        }
    }
    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "LINE_SYNCED",
            ),
    )
}

fun parseRichSyncLyrics(
    data: String,
    plainLyric: String? = null,
    syncedLyrics: String? = null,
): Lyrics {
    // Unescape JSON string if needed (remove quotes and replace \n with actual newlines)
    val unescapedData =
        data
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

    // Handle different line separators (Unix \n, Windows \r\n, Mac \r)
    val lines = unescapedData.lines()
    // Skip offset line if present (starts with [offset:)
    val lyricsLines =
        lines.filter { line ->
            line.isNotBlank() && !line.trim().startsWith("[offset:")
        }

    // Regex to match [MM:SS.mm] format (flexible with 1-2 digits)
    val regex = Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\](.+)")
    // Strip a leading voice marker (e.g. "v1:", "v2:") that some lyrics
    // providers prepend before the first word timestamp, so it doesn't leak
    // into the word content and stick to the first word.
    val voiceMarkerRegex = Regex("""^v\d+:""")
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()

    // Prepare target words for syllable alignment if plain/synced text is available
    val targetWords = extractTargetWords(plainLyric, syncedLyrics)
    var targetIdx = 0

    val timestampRegex = Regex("""<(\d{1,2}:\d{2}\.\d{2,3})>""")

    lyricsLines.forEachIndexed { index, line ->
        val matchResult = regex.matchEntire(line.trim())
        if (matchResult != null) {
            val minutes = matchResult.groupValues[1].toLongOrNull() ?: 0L
            val seconds = matchResult.groupValues[2].toLongOrNull() ?: 0L
            val centiseconds = matchResult.groupValues[3].toLongOrNull() ?: 0L

            val millisPart = if (matchResult.groupValues[3].length == 3) centiseconds else centiseconds * 10
            val timeInMillis = minutes * 60_000L + seconds * 1000L + millisPart

            val content =
                matchResult.groupValues[4]
                    .trimStart()
                    .replace(voiceMarkerRegex, "")
                    .trimStart()

            if (content.isNotBlank()) {
                val timestamps = timestampRegex.findAll(content).toList()
                val finalWords =
                    if (timestamps.isNotEmpty()) {
                        val tokens = mutableListOf<Pair<String, String>>()
                        timestamps.forEachIndexed { i, match ->
                            val t = match.groupValues[1]
                            val startPos = match.range.last + 1
                            val endPos = if (i < timestamps.size - 1) timestamps[i + 1].range.first else content.length
                            val text = content.substring(startPos, endPos).trim()
                            if (text.isNotEmpty()) {
                                tokens.add(t to text)
                            }
                        }

                        if (targetWords.isNotEmpty()) {
                            val alignment = alignSyllablesWithTargetWords(tokens, targetWords, targetIdx)
                            targetIdx = alignment.newTargetIdx
                            alignment.formattedWords
                        } else {
                            mergeSyllablesHeuristically(tokens)
                        }
                    } else {
                        content
                    }

                if (finalWords.isNotBlank()) {
                    linesLyrics.add(
                        Lyrics.LyricsX.Line(
                            endTimeMs = "0",
                            startTimeMs = timeInMillis.toString(),
                            syllables = listOf(),
                            words = finalWords,
                        ),
                    )
                }
            }
        }
    }

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "RICH_SYNCED",
            ),
    )
}

/**
 * Parse TTML (Timed Text Markup Language) lyrics from BetterLyrics / Apple Music.
 * Supports both line-synced and word-by-word synced lyrics.
 *
 * TTML format: `<p begin="M:SS.mmm" end="M:SS.mmm">` contains `<span begin="..." end="...">syllable/word</span>`
 * When syllables within a word are segmented by <span> tags without whitespace between them,
 * they are joined into full words so words don't get chopped up (e.g. "al"+"rea"+"dy " -> "already ").
 * If spans with timing exist → word-by-word (RICH_SYNCED)
 * If no spans → line-synced (LINE_SYNCED)
 */
fun parseTtmlLyrics(data: String): Lyrics {
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    var hasWordTiming = false

    // Match each <p ...>...</p> element
    val pRegex = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    val spanTagRegex = Regex("""<span\b([^>]*)>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
    val beginRegex = Regex("""begin="([^"]+)"""", RegexOption.IGNORE_CASE)
    val endRegex = Regex("""end="([^"]+)"""", RegexOption.IGNORE_CASE)

    for (pMatch in pRegex.findAll(data)) {
        val pAttrs = pMatch.groupValues[1]
        val innerContent = pMatch.groupValues[2]

        val lineBegin = beginRegex.find(pAttrs)?.groupValues?.get(1)?.let { parseTtmlTime(it) } ?: 0L
        val lineEnd = endRegex.find(pAttrs)?.groupValues?.get(1)?.let { parseTtmlTime(it) } ?: 0L

        val spans = spanTagRegex.findAll(innerContent).toList()
        val timedSpans = spans.filter { beginRegex.containsMatchIn(it.groupValues[1]) }

        if (timedSpans.isNotEmpty()) {
            hasWordTiming = true
            val wordParts = StringBuilder()

            var currentWord = StringBuilder()
            var currentWordStartMs: Long? = null

            for (i in timedSpans.indices) {
                val spanMatch = timedSpans[i]
                val spanAttrs = spanMatch.groupValues[1]
                val spanBegin = parseTtmlTime(beginRegex.find(spanAttrs)!!.groupValues[1])
                val rawContent = decodeHtmlEntities(spanMatch.groupValues[2].replace(Regex("<[^>]*>"), ""))

                // Check gap before this span (only horizontal whitespace or <br>; ignore XML indentation/newlines between tags)
                val lastEndPos = if (i == 0) 0 else timedSpans[i - 1].range.last + 1
                val gapBefore = innerContent.substring(lastEndPos, spanMatch.range.first)
                val gapBeforeHasSpace = !gapBefore.contains('\n') && !gapBefore.contains('\r') && gapBefore.any { it == ' ' || it == '\t' }
                val gapBeforeHasBreak = gapBefore.contains("<br", ignoreCase = true) || gapBeforeHasSpace

                // Check gap after this span
                val nextStartPos = if (i < timedSpans.lastIndex) timedSpans[i + 1].range.first else innerContent.length
                val gapAfter = innerContent.substring(spanMatch.range.last + 1, nextStartPos)
                val gapAfterHasSpace = !gapAfter.contains('\n') && !gapAfter.contains('\r') && gapAfter.any { it == ' ' || it == '\t' }
                val gapAfterHasBreak = gapAfter.contains("<br", ignoreCase = true) || gapAfterHasSpace || i == timedSpans.lastIndex

                val spanHasLeadingSpace = rawContent.isNotEmpty() && (rawContent.first() == ' ' || rawContent.first() == '\t')
                val spanHasTrailingSpace = rawContent.isNotEmpty() && (rawContent.last() == ' ' || rawContent.last() == '\t')

                // If there is whitespace between words before this span, close previous word
                if ((gapBeforeHasBreak || spanHasLeadingSpace) && currentWord.isNotEmpty() && currentWordStartMs != null) {
                    val beginFormatted = formatMsToLrc(currentWordStartMs)
                    wordParts.append("<$beginFormatted>$currentWord ")
                    currentWord.clear()
                    currentWordStartMs = null
                }

                val trimmedText = rawContent.trim()
                if (trimmedText.isNotEmpty()) {
                    val tokens = trimmedText.split(Regex("""\s+"""))
                    if (tokens.size == 1) {
                        if (currentWord.isEmpty()) {
                            currentWordStartMs = spanBegin
                        }
                        currentWord.append(tokens[0])

                        if (spanHasTrailingSpace || gapAfterHasBreak) {
                            val beginFormatted = formatMsToLrc(currentWordStartMs!!)
                            wordParts.append("<$beginFormatted>$currentWord ")
                            currentWord.clear()
                            currentWordStartMs = null
                        }
                    } else {
                        // First token completes current word
                        if (currentWord.isEmpty()) {
                            currentWordStartMs = spanBegin
                        }
                        currentWord.append(tokens[0])
                        val beginFormatted = formatMsToLrc(currentWordStartMs!!)
                        wordParts.append("<$beginFormatted>$currentWord ")
                        currentWord.clear()
                        currentWordStartMs = null

                        // Intermediate tokens are full words
                        for (j in 1 until tokens.size - 1) {
                            val midBeginFormatted = formatMsToLrc(spanBegin)
                            wordParts.append("<$midBeginFormatted>${tokens[j]} ")
                        }

                        // Last token
                        currentWordStartMs = spanBegin
                        currentWord.append(tokens.last())
                        if (spanHasTrailingSpace || gapAfterHasBreak) {
                            val lastBeginFormatted = formatMsToLrc(currentWordStartMs)
                            wordParts.append("<$lastBeginFormatted>$currentWord ")
                            currentWord.clear()
                            currentWordStartMs = null
                        }
                    }
                }
            }

            // Flush any remaining word
            if (currentWord.isNotEmpty() && currentWordStartMs != null) {
                val beginFormatted = formatMsToLrc(currentWordStartMs)
                wordParts.append("<$beginFormatted>$currentWord ")
                currentWord.clear()
                currentWordStartMs = null
            }

            val words = wordParts.toString().trimEnd()
            if (words.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = words,
                    ),
                )
            }
        } else {
            // No spans — extract plain text (strip any remaining tags)
            val plainText = innerContent.replace(Regex("<[^>]*>"), "").replace(Regex("""\s+"""), " ").trim()
            if (plainText.isNotBlank()) {
                linesLyrics.add(
                    Lyrics.LyricsX.Line(
                        startTimeMs = lineBegin.toString(),
                        endTimeMs = lineEnd.toString(),
                        syllables = listOf(),
                        words = plainText,
                    ),
                )
            }
        }
    }

    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = if (hasWordTiming) "RICH_SYNCED" else "LINE_SYNCED",
            ),
    )
}

/**
 * Parse TTML time format to milliseconds.
 * Supports: "M:SS.mmm", "MM:SS.mmm", "H:MM:SS.mmm", "SS.mmm"
 */
private fun parseTtmlTime(time: String): Long {
    val parts = time.split(":")
    return when (parts.size) {
        3 -> {
            val hours = parts[0].toLongOrNull() ?: 0L
            val minutes = parts[1].toLongOrNull() ?: 0L
            val secParts = parts[2].split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + millis
        }
        2 -> {
            val minutes = parts[0].toLongOrNull() ?: 0L
            val secParts = parts[1].split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            minutes * 60_000L + seconds * 1000L + millis
        }
        else -> {
            val secParts = time.split(".")
            val seconds = secParts[0].toLongOrNull() ?: 0L
            val millis = parseMillisPart(secParts.getOrNull(1))
            seconds * 1000L + millis
        }
    }
}

private fun parseMillisPart(part: String?): Long {
    if (part.isNullOrEmpty()) return 0L
    val value = part.toLongOrNull() ?: return 0L
    return when (part.length) {
        1 -> value * 100
        2 -> value * 10
        3 -> value
        else -> value
    }
}

private fun formatMsToLrc(ms: Long): String {
    val minutes = ms / 60_000L
    val seconds = (ms % 60_000L) / 1000L
    val centis = (ms % 1000L) / 10L
    val m = if (minutes < 10) "0$minutes" else "$minutes"
    val s = if (seconds < 10) "0$seconds" else "$seconds"
    val c = if (centis < 10) "0$centis" else "$centis"
    return "$m:$s.$c"
}

fun parseUnsyncedLyrics(data: String): Lyrics {
    val lines = data.lines()
    val linesLyrics = ArrayList<Lyrics.LyricsX.Line>()
    lines.map { line ->
        linesLyrics.add(
            Lyrics.LyricsX.Line(
                endTimeMs = "0",
                startTimeMs = "0",
                syllables = listOf(),
                words = line,
            ),
        )
    }
    return Lyrics(
        lyrics =
            Lyrics.LyricsX(
                lines = linesLyrics,
                syncType = "UNSYNCED",
            ),
    )
}

private class AlignmentResult(
    val formattedWords: String,
    val newTargetIdx: Int,
)

private fun extractTargetWords(plainLyric: String?, syncedLyrics: String?): List<Pair<String, String>> {
    val sourceText = when {
        !plainLyric.isNullOrBlank() -> plainLyric
        !syncedLyrics.isNullOrBlank() -> syncedLyrics.replace(Regex("""\[\d{1,2}:\d{2}\.\d{2,3}\]"""), " ")
        else -> return emptyList()
    }
    return sourceText.split(Regex("""\s+""")).mapNotNull { raw ->
        val clean = cleanWordForAlignment(raw)
        if (clean.isNotEmpty()) raw to clean else null
    }
}

internal fun cleanWordForAlignment(word: String): String =
    word.filter { it.isLetterOrDigit() || it == '\'' }.lowercase()

private fun alignSyllablesWithTargetWords(
    tokens: List<Pair<String, String>>,
    targetWords: List<Pair<String, String>>,
    startTargetIdx: Int,
): AlignmentResult {
    var targetIdx = startTargetIdx
    val rebuilt = StringBuilder()
    var currT: String? = null
    val currSyllables = StringBuilder()
    val currClean = StringBuilder()

    for ((t, txt) in tokens) {
        if (currT == null) {
            currT = t
        }
        currSyllables.append(txt)
        currClean.append(cleanWordForAlignment(txt))

        if (targetIdx < targetWords.size) {
            val (_, targetCw) = targetWords[targetIdx]
            val currCleanStr = currClean.toString()
            if (currCleanStr == targetCw) {
                rebuilt.append("<$currT>$currSyllables ")
                currT = null
                currSyllables.clear()
                currClean.clear()
                targetIdx++
            } else if (!targetCw.startsWith(currCleanStr)) {
                var foundAhead = false
                for (ahead in 1..4) {
                    if (targetIdx + ahead < targetWords.size) {
                        val (_, nextCw) = targetWords[targetIdx + ahead]
                        if (nextCw == currCleanStr || nextCw.startsWith(currCleanStr)) {
                            targetIdx += ahead
                            if (nextCw == currCleanStr) {
                                rebuilt.append("<$currT>$currSyllables ")
                                currT = null
                                currSyllables.clear()
                                currClean.clear()
                                targetIdx++
                            }
                            foundAhead = true
                            break
                        }
                    }
                }
                if (!foundAhead) {
                    rebuilt.append("<$currT>$currSyllables ")
                    currT = null
                    currSyllables.clear()
                    currClean.clear()
                }
            }
        } else {
            rebuilt.append("<$currT>$currSyllables ")
            currT = null
            currSyllables.clear()
            currClean.clear()
        }
    }

    if (currSyllables.isNotEmpty() && currT != null) {
        rebuilt.append("<$currT>$currSyllables ")
    }

    return AlignmentResult(rebuilt.toString().trimEnd(), targetIdx)
}

private fun mergeSyllablesHeuristically(tokens: List<Pair<String, String>>): String {
    if (tokens.isEmpty()) return ""
    val merged = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i < tokens.size) {
        var (t, text) = tokens[i]
        while (i + 1 < tokens.size) {
            val (_, nextText) = tokens[i + 1]
            if (shouldMergeAdjacentSyllables(text, nextText)) {
                text += nextText
                i++
            } else {
                break
            }
        }
        merged.add(t to text)
        i++
    }
    return merged.joinToString(" ") { "<${it.first}>${it.second}" }
}

internal fun shouldMergeAdjacentSyllables(curr: String, next: String): Boolean {
    val cLow = cleanWordForAlignment(curr)
    val nLow = cleanWordForAlignment(next)
    if (cLow.isEmpty() || nLow.isEmpty()) return false

    // 1. Hyphen
    if (curr.endsWith("-")) return true

    // 2. Single Latin letter that cannot be an English word
    if (nLow.length == 1 && nLow[0] in 'a'..'z' && nLow !in setOf("a", "i", "o")) return true
    if (cLow.length == 1 && cLow[0] in 'a'..'z' && cLow !in setOf("a", "i", "o")) return true

    // 3. Known syllable prefixes that are not standalone words
    if (cLow in setOf("brea", "camou", "wi", "lo", "fa", "pie", "pixe", "beau")) return true

    // 4. Known syllable suffixes that are not standalone words
    if (nLow in setOf("rea", "dy", "ce", "sh", "ve", "king", "flaged", "lete", "peat", "ture", "ted", "ces")) return true

    // 5. 'al' + 'ready' / 'rea' / 'dy'
    if (cLow == "a" && (nLow == "l" || nLow == "lready")) return true
    if (cLow == "al" && (nLow == "rea" || nLow == "ready")) return true
    if (cLow == "alrea" && nLow == "dy") return true

    // 6. 'de' + 'lete', 're' + 'peat'
    if ((cLow == "de" || cLow == "re") && (nLow == "lete" || nLow == "peat")) return true

    return false
}
