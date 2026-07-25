package org.oxycblt.auxio.shippy.persistence.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalTrackMetadataMigrationTest {
    @Test
    fun `canonical track catalog has an explicit v5 to v6 migration`() {
        assertEquals(5, ShippyDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, ShippyDatabase.MIGRATION_5_6.endVersion)
    }
}
