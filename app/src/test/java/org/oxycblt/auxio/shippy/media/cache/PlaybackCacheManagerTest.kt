/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCacheManagerTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.media.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.shippy.core.identity.SourceReferenceId
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.media.MediaObjectKey
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackCacheManagerTest {
    private lateinit var context: Context
    private lateinit var cacheDir: File
    private lateinit var simpleCache: SimpleCache
    private lateinit var cacheManager: PlaybackCacheManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cacheDir =
            File(context.cacheDir, "test-playback-cache-${System.currentTimeMillis()}").apply {
                mkdirs()
            }
        val databaseProvider = StandaloneDatabaseProvider(context)
        val evictor = LeastRecentlyUsedCacheEvictor(10L * 1024L * 1024L)
        simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
        cacheManager = PlaybackCacheManager(simpleCache)
    }

    @After
    fun tearDown() {
        simpleCache.release()
        cacheDir.deleteRecursively()
    }

    private fun writeToCache(key: MediaObjectKey, data: ByteArray) {
        val mutations = androidx.media3.datasource.cache.ContentMetadataMutations()
        androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(
            mutations,
            data.size.toLong(),
        )
        simpleCache.applyContentMetadataMutations(key.value, mutations)

        val span = simpleCache.startReadWrite(key.value, 0L, data.size.toLong())
        val file = simpleCache.startFile(key.value, 0L, data.size.toLong())
        file.writeBytes(data)
        simpleCache.commitFile(file, data.size.toLong())
    }

    @Test
    fun `hasComplete and copyCompleteTo succeed when full object is cached`() = runBlocking {
        val srcId = SourceReferenceId(java.util.UUID.randomUUID().toString())
        val key = MediaObjectKey.fromSourceReference(srcId, "audio/mp4-320000")
        val data = ByteArray(4096) { (it % 128).toByte() }
        writeToCache(key, data)

        assertTrue(cacheManager.hasComplete(key, data.size.toLong()))

        val out = ByteArrayOutputStream()
        val copied = cacheManager.copyCompleteTo(key, data.size.toLong(), out)
        assertEquals(data.size.toLong(), copied)
        assertTrue(data.contentEquals(out.toByteArray()))
    }

    @Test
    fun `copyCompleteTo returns null on missing or incomplete cache`() = runBlocking {
        val srcId = SourceReferenceId(java.util.UUID.randomUUID().toString())
        val key = MediaObjectKey.fromSourceReference(srcId, "DEFAULT")
        val out = ByteArrayOutputStream()
        val copied = cacheManager.copyCompleteTo(key, 1000L, out)
        assertNull(copied)
        assertEquals(0, out.size())
    }

    @Test
    fun `protection inhibits eviction during maintenance passes`() {
        val keyProtected =
            MediaObjectKey.fromSourceReference(
                SourceReferenceId(java.util.UUID.randomUUID().toString()),
                "DEFAULT",
            )
        val keyUnprotected =
            MediaObjectKey.fromSourceReference(
                SourceReferenceId(java.util.UUID.randomUUID().toString()),
                "DEFAULT",
            )
        val data = ByteArray(1024) { 1 }

        writeToCache(keyProtected, data)
        writeToCache(keyUnprotected, data)

        cacheManager.protectKey(keyProtected)
        assertTrue(cacheManager.isProtected(keyProtected))
        assertFalse(cacheManager.isProtected(keyUnprotected))

        // Age eviction with maxAgeMs = 0 (all spans qualify as expired)
        val evicted =
            cacheManager.evictOlderThan(
                maxAgeMs = 0L,
                nowEpochMs = System.currentTimeMillis() + 1000L,
            )
        assertTrue(evicted >= 1024L)

        // Protected resource survives
        assertTrue(cacheManager.hasComplete(keyProtected, data.size.toLong()))
        // Unprotected resource was evicted
        assertFalse(cacheManager.hasComplete(keyUnprotected, data.size.toLong()))

        cacheManager.unprotectKey(keyProtected)
        assertFalse(cacheManager.isProtected(keyProtected))
    }

    @Test
    fun `enforceFreeSpaceFloor evicts oldest spans when space is below minimum floor`() {
        val key1 =
            MediaObjectKey.fromSourceReference(
                SourceReferenceId(java.util.UUID.randomUUID().toString()),
                "DEFAULT",
            )
        val key2 =
            MediaObjectKey.fromSourceReference(
                SourceReferenceId(java.util.UUID.randomUUID().toString()),
                "DEFAULT",
            )
        val data = ByteArray(2048) { 1 }

        writeToCache(key1, data)
        writeToCache(key2, data)

        var simulatedUsableSpace = 50L * 1024L * 1024L // 50 MB
        val minFloor = 100L * 1024L * 1024L // 100 MB required

        val evicted = cacheManager.enforceFreeSpaceFloor(minFloor) { simulatedUsableSpace }
        assertTrue(evicted > 0L)
    }

    @Test
    fun `clear removes unprotected streaming cache resources and preserves protected items`() =
        runBlocking {
            val key1 =
                MediaObjectKey.fromSourceReference(
                    SourceReferenceId(java.util.UUID.randomUUID().toString()),
                    "DEFAULT",
                )
            val key2 =
                MediaObjectKey.fromSourceReference(
                    SourceReferenceId(java.util.UUID.randomUUID().toString()),
                    "DEFAULT",
                )
            writeToCache(key1, ByteArray(1024) { 1 })
            writeToCache(key2, ByteArray(2048) { 2 })

            cacheManager.protectKey(key1)
            assertTrue(cacheManager.sizeBytes() > 0L)

            cacheManager.clear()

            assertTrue(cacheManager.hasComplete(key1, 1024L))
            assertFalse(cacheManager.hasComplete(key2, 2048L))
        }

    @Test
    fun `performMaintenance evicts expired spans and enforces floor while protecting active keys`() =
        runBlocking {
            val keyProtected =
                MediaObjectKey.fromSourceReference(
                    SourceReferenceId(java.util.UUID.randomUUID().toString()),
                    "DEFAULT",
                )
            val keyUnprotected =
                MediaObjectKey.fromSourceReference(
                    SourceReferenceId(java.util.UUID.randomUUID().toString()),
                    "DEFAULT",
                )
            writeToCache(keyProtected, ByteArray(1024) { 1 })
            writeToCache(keyUnprotected, ByteArray(1024) { 2 })

            cacheManager.protectKey(keyProtected)

            val evicted = cacheManager.performMaintenance(maxAgeMs = 0L, minFreeBytes = 0L)
            assertTrue(evicted >= 1024L)
            assertTrue(cacheManager.hasComplete(keyProtected, 1024L))
            assertFalse(cacheManager.hasComplete(keyUnprotected, 1024L))
        }
}
