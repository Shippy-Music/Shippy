package org.oxycblt.auxio.shippy.persistence.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalTrackMetadataMigrationTest {
    @Test
    fun `canonical track catalog has an explicit v5 to v6 migration`() {
        assertEquals(5, ShippyDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, ShippyDatabase.MIGRATION_5_6.endVersion)
    }

    @Test
    fun `lastfm outbox has an explicit v6 to v7 migration`() {
        assertEquals(6, ShippyDatabase.MIGRATION_6_7.startVersion)
        assertEquals(7, ShippyDatabase.MIGRATION_6_7.endVersion)
    }
}
