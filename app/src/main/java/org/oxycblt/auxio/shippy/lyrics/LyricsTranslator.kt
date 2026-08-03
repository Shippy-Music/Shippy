/*
 * Copyright (c) 2026 Auxio Project
 * LyricsTranslator.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.shippy.lyrics

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

interface LyricsTranslator {
    suspend fun translateToEnglish(lyrics: ParsedLyrics): LyricsTranslationResult
}

sealed interface LyricsTranslationResult {
    data class Translated(val sourceLanguage: String, val lyrics: TranslatedLyrics) :
        LyricsTranslationResult

    data object AlreadyEnglish : LyricsTranslationResult

    data object UnsupportedLanguage : LyricsTranslationResult
}

sealed interface TranslatedLyrics {
    data class Plain(val lines: List<TranslatedLyricLine>) : TranslatedLyrics

    data class Synced(val lines: List<TranslatedSyncedLyricLine>) : TranslatedLyrics
}

data class TranslatedLyricLine(val originalText: String, val translatedText: String)

data class TranslatedSyncedLyricLine(
    val startMs: Long,
    val originalText: String,
    val translatedText: String,
)

@Singleton
class MlKitLyricsTranslator @Inject constructor() : LyricsTranslator {
    override suspend fun translateToEnglish(lyrics: ParsedLyrics): LyricsTranslationResult {
        val sourceText = lyrics.plainText.trim()
        if (sourceText.isBlank()) return LyricsTranslationResult.UnsupportedLanguage

        val languageIdentifier = LanguageIdentification.getClient()
        val languageTag =
            try {
                languageIdentifier.identifyLanguage(sourceText).awaitValue()
            } finally {
                languageIdentifier.close()
            }
        if (languageTag == TranslateLanguage.ENGLISH) return LyricsTranslationResult.AlreadyEnglish
        val sourceLanguage =
            TranslateLanguage.fromLanguageTag(languageTag)
                ?: return LyricsTranslationResult.UnsupportedLanguage

        val options =
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        val translator = Translation.getClient(options)
        return try {
            translator
                .downloadModelIfNeeded(DownloadConditions.Builder().build())
                .awaitValue()
            val translated =
                translateLyricsWith(lyrics) { text -> translator.translate(text).awaitValue() }
            LyricsTranslationResult.Translated(languageTag, translated)
        } finally {
            translator.close()
        }
    }
}

internal suspend fun translateLyricsWith(
    lyrics: ParsedLyrics,
    translate: suspend (String) -> String,
): TranslatedLyrics {
    val originals =
        when (lyrics) {
            is PlainLyrics -> lyrics.plainText.lines()
            is SyncedLyrics -> lyrics.lines.map(SyncedLyricLine::text)
        }
    val translations = mutableMapOf<String, String>()
    originals.distinct().filter(String::isNotBlank).forEach { original ->
        translations[original] = translate(original)
    }
    return when (lyrics) {
        is PlainLyrics ->
            TranslatedLyrics.Plain(
                originals.map { original ->
                    TranslatedLyricLine(original, translations[original].orEmpty())
                }
            )
        is SyncedLyrics ->
            TranslatedLyrics.Synced(
                lyrics.lines.map { line ->
                    TranslatedSyncedLyricLine(
                        startMs = line.startMs,
                        originalText = line.text,
                        translatedText = translations[line.text].orEmpty(),
                    )
                }
            )
    }
}

private suspend fun <T> Task<T>.awaitValue(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { continuation.cancel() }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class LyricsTranslationModule {
    @Binds
    @Singleton
    abstract fun translator(translator: MlKitLyricsTranslator): LyricsTranslator
}
