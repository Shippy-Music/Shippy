/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadDestinationLocalSourcePlanTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDestinationLocalSourcePlanTest {
    @Test
    fun `preserves an already manual Local source`() {
        val plan = plan(existing = listOf("manual"), previous = null, autoAdded = null, next = "manual")

        assertEquals(listOf("manual"), plan.sourceUris)
        assertEquals(null, plan.autoAddedDestinationUri)
        assertFalse(plan.sourceChanged)
    }

    @Test
    fun `replaces only the auto added Local source`() {
        val plan =
            plan(
                existing = listOf("manual", "old-downloads"),
                previous = "old-downloads",
                autoAdded = "old-downloads",
                next = "new-downloads",
            )

        assertEquals(listOf("manual", "new-downloads"), plan.sourceUris)
        assertEquals("new-downloads", plan.autoAddedDestinationUri)
        assertTrue(plan.sourceChanged)
    }

    @Test
    fun `adds the new destination as a Local source`() {
        val plan = plan(existing = listOf("manual"), previous = null, autoAdded = null, next = "downloads")

        assertEquals(listOf("manual", "downloads"), plan.sourceUris)
        assertEquals("downloads", plan.autoAddedDestinationUri)
        assertTrue(plan.sourceChanged)
    }

    @Test
    fun `keeps the same destination as a no op`() {
        val plan =
            plan(
                existing = listOf("downloads"),
                previous = "downloads",
                autoAdded = "downloads",
                next = "downloads",
            )

        assertEquals(listOf("downloads"), plan.sourceUris)
        assertEquals("downloads", plan.autoAddedDestinationUri)
        assertFalse(plan.sourceChanged)
    }

    private fun plan(
        existing: List<String>,
        previous: String?,
        autoAdded: String?,
        next: String,
    ) =
        DownloadDestinationLocalSourcePlan.create(existing, previous, autoAdded, next)
}
