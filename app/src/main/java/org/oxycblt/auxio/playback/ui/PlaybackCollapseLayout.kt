/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCollapseLayout.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * Observes vertical gestures before child views consume them.
 *
 * The full player contains a ViewPager, seek bar, buttons, and (on phones) a nested scroller. Those
 * children legitimately own their touch streams, which means the surrounding bottom sheet does not
 * reliably receive a downward drag. This layout only observes the stream and asks the host to
 * collapse after a deliberate downward swipe; it never consumes the event from the child.
 */
class PlaybackCollapseLayout
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ConstraintLayout(context, attrs, defStyleAttr) {
    var onCollapseGesture: (() -> Unit)? = null

    private val minimumDistancePx =
        max(
            (ViewConfiguration.get(context).scaledTouchSlop * TOUCH_SLOP_MULTIPLIER).toFloat(),
            MINIMUM_DISTANCE_DP * resources.displayMetrics.density,
        )
    private var downX = 0f
    private var downY = 0f
    private var collapseDispatched = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                collapseDispatched = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - downX
                val deltaY = event.y - downY
                if (
                    !collapseDispatched &&
                        deltaY >= minimumDistancePx &&
                        deltaY > abs(deltaX) * VERTICAL_DOMINANCE
                ) {
                    collapseDispatched = true
                    onCollapseGesture?.invoke()
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> collapseDispatched = false
        }
        return super.dispatchTouchEvent(event)
    }

    private companion object {
        const val TOUCH_SLOP_MULTIPLIER = 3
        const val MINIMUM_DISTANCE_DP = 48f
        const val VERTICAL_DOMINANCE = 1.25f
    }
}
