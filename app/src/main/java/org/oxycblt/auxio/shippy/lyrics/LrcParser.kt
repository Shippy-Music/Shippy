/*
 * Copyright (c) 2026 Shippy contributors
 * LrcParser.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.lyrics

sealed interface ParsedLyrics {
    val plainText: String
}

data class PlainLyrics(override val plainText: String) : ParsedLyrics

data class SyncedLyricLine(
    val startMs: Long,
    val text: String,
)

data class SyncedLyrics(
    val lines: List<SyncedLyricLine>,
    override val plainText: String,
) : ParsedLyrics

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)
    private val metadata = Regex("^\\[[a-z][a-z0-9]*:.*]$", RegexOption.IGNORE_CASE)

    fun parse(input: String): ParsedLyrics {
        val sourceLines = input.replace("\r\n", "\n").replace('\r', '\n').lines()
        val offsetMs =
            sourceLines.firstNotNullOfOrNull { offset.matchEntire(it.trim())?.groupValues?.get(1) }
                ?.toLongOrNull()
                ?: 0L
        val timedLines = mutableListOf<IndexedValue<SyncedLyricLine>>()
        val plainLines = mutableListOf<String>()
        val syncedPlainLines = mutableListOf<String>()

        sourceLines.forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || offset.matches(line) || metadata.matches(line)) {
                return@forEachIndexed
            }

            val matches = timestamp.findAll(line).toList()
            if (matches.isEmpty() || matches.first().range.first != 0) {
                plainLines += line
                return@forEachIndexed
            }

            val validMatches = matches.filter { it.groupValues[2].toInt() <= 59 }
            if (validMatches.isEmpty()) {
                plainLines += line
                return@forEachIndexed
            }

            val lyricText = line.substring(matches.last().range.last + 1).trimStart()
            syncedPlainLines += lyricText
            validMatches.forEach { match ->
                val seconds = match.groupValues[2].toInt()
                val minutes = match.groupValues[1].toLong()
                val fractionMs = fractionToMilliseconds(match.groupValues[3])
                val startMs =
                    (minutes * 60_000 + seconds * 1_000 + fractionMs + offsetMs).coerceAtLeast(0)
                timedLines += IndexedValue(index, SyncedLyricLine(startMs, lyricText))
            }
        }

        if (timedLines.isEmpty()) {
            return PlainLyrics(plainLines.joinToString("\n"))
        }

        val sortedLines =
            timedLines.sortedWith(compareBy<IndexedValue<SyncedLyricLine>> { it.value.startMs }.thenBy { it.index })
                .map(IndexedValue<SyncedLyricLine>::value)
        return SyncedLyrics(sortedLines, syncedPlainLines.joinToString("\n"))
    }

    private fun fractionToMilliseconds(fraction: String): Int =
        when (fraction.length) {
            0 -> 0
            1 -> fraction.toInt() * 100
            2 -> fraction.toInt() * 10
            else -> fraction.toInt()
        }
}
