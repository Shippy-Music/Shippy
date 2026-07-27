/*
 * Copyright (c) 2026 Auxio Project
 * ShippyMusicFileSystemFactory.kt is part of Auxio.
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
package org.oxycblt.auxio.music

import android.content.Context
import org.oxycblt.auxio.music.locations.LocationMode
import org.oxycblt.musikr.fs.CompositeFS
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.mediastore.MediaStore
import org.oxycblt.musikr.fs.saf.SAF

/**
 * Builds the one filesystem view used for both indexing and change tracking.
 *
 * MediaStore remains the selected library source. When Shippy also has SAF sources (including a
 * chosen download destination), they are appended as a secondary view so SAF-only content is not
 * invisible to a MediaStore-mode library. SAF mode intentionally keeps Auxio's original SAF-only
 * behavior.
 */
internal object ShippyMusicFileSystemFactory {
    fun create(context: Context, settings: MusicSettings): FS =
        when (settings.locationMode) {
            LocationMode.SAF -> SAF.from(context, settings.safQuery)
            LocationMode.MEDIA_STORE -> {
                val primary = MediaStore.from(context, settings.mediaStoreQuery)
                if (settings.safQuery.source.isEmpty()) {
                    primary
                } else {
                    CompositeFS(primary, SAF.from(context, settings.safQuery))
                }
            }
        }
}
