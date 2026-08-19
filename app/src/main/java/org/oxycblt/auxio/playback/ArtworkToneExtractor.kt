/*
 * Copyright (c) 2026 Auxio Project
 * ArtworkToneExtractor.kt is part of Auxio.
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
package org.oxycblt.auxio.playback

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap

/** Derives one muted, theme-blended tone from already loaded artwork. */
class ArtworkToneExtractor {
    private val cache = LruCache<Drawable, Int>(32)

    fun mutedColor(drawable: Drawable?, surfaceColor: Int, onSurfaceColor: Int): Int {
        if (drawable == null) return surfaceColor
        cache.get(drawable)?.let {
            return it
        }
        val bitmap =
            runCatching { drawable.toBitmap(width = 32, height = 32) }.getOrNull()
                ?: return surfaceColor
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 32) continue
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        if (count == 0L) return surfaceColor
        val average =
            Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
        val blended = ColorUtils.blendARGB(surfaceColor, average, 0.18f)
        val result =
            if (ColorUtils.calculateContrast(onSurfaceColor, blended) >= 4.5) blended
            else surfaceColor
        cache.put(drawable, result)
        return result
    }
}
