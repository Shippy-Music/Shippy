/*
 * Copyright (c) 2026 Auxio Project
 * SparseOrderKey.kt is part of Auxio.
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
package app.shippy.data.db.util

internal object SparseOrderKey {
    private const val GAP = 1_024L

    fun between(before: Long?, after: Long?): Long? =
        when {
            before == null && after == null -> GAP
            before == null -> subtractOrNull(requireNotNull(after), GAP)
            after == null -> addOrNull(before, GAP)
            else -> {
                require(before < after) { "Order anchors must be increasing" }
                if (before == after - 1) null else (before and after) + ((before xor after) shr 1)
            }
        }

    fun rebalancedKeys(size: Int): List<Long> {
        require(size >= 0) { "Order size cannot be negative" }
        return List(size) { index -> Math.multiplyExact(index.toLong() + 1, GAP) }
    }

    private fun addOrNull(value: Long, amount: Long): Long? =
        try {
            Math.addExact(value, amount)
        } catch (_: ArithmeticException) {
            null
        }

    private fun subtractOrNull(value: Long, amount: Long): Long? =
        try {
            Math.subtractExact(value, amount)
        } catch (_: ArithmeticException) {
            null
        }
}
