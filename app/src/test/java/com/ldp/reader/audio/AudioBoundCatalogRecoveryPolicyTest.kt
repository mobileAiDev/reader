package com.ldp.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBoundCatalogRecoveryPolicyTest {
    @Test
    fun `cold compact route reloads the full book catalog`() {
        assertEquals(
            AudioBoundCatalogRecoveryPolicy.LOAD_FULL_CATALOG,
            AudioBoundCatalogRecoveryPolicy.decide("book-route", registeredEpisodeCount = 1)
        )
    }

    @Test
    fun `missing registry catalog also reloads when the book route is persisted`() {
        assertEquals(
            AudioBoundCatalogRecoveryPolicy.LOAD_FULL_CATALOG,
            AudioBoundCatalogRecoveryPolicy.decide("book-route", registeredEpisodeCount = 0)
        )
    }

    @Test
    fun `live full registry catalog is retained without another load`() {
        assertEquals(
            AudioBoundCatalogRecoveryPolicy.KEEP_REGISTERED,
            AudioBoundCatalogRecoveryPolicy.decide("book-route", registeredEpisodeCount = 6)
        )
    }

    @Test
    fun `catalog recovery is unavailable without a persisted book route`() {
        assertEquals(
            AudioBoundCatalogRecoveryPolicy.UNAVAILABLE,
            AudioBoundCatalogRecoveryPolicy.decide("", registeredEpisodeCount = 1)
        )
    }
}
