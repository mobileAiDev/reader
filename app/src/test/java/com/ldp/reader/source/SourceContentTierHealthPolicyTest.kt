package com.ldp.reader.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceContentTierHealthPolicyTest {
    @Test
    fun failureThresholdUsesTwoRoutesOrOneThirdWithoutMinimumPoolSize() {
        assertEquals(1, SourceContentTierHealthPolicy.failureThreshold(1))
        assertEquals(1, SourceContentTierHealthPolicy.failureThreshold(2))
        assertEquals(1, SourceContentTierHealthPolicy.failureThreshold(3))
        assertEquals(2, SourceContentTierHealthPolicy.failureThreshold(4))
        assertEquals(2, SourceContentTierHealthPolicy.failureThreshold(8))
        assertEquals(2, SourceContentTierHealthPolicy.failureThreshold(32))
    }

    @Test
    fun cullsFailedRoutesOnlyAfterHealthThreshold() {
        assertFalse(SourceContentTierHealthPolicy.evaluate(tierSize = 32, failedRoutes = 1).cullFailedRoutes)
        assertTrue(SourceContentTierHealthPolicy.evaluate(tierSize = 32, failedRoutes = 2).cullFailedRoutes)
        assertTrue(SourceContentTierHealthPolicy.evaluate(tierSize = 3, failedRoutes = 1).cullFailedRoutes)
        assertTrue(SourceContentTierHealthPolicy.evaluate(tierSize = 1, failedRoutes = 1).cullFailedRoutes)
        assertFalse(SourceContentTierHealthPolicy.evaluate(tierSize = 0, failedRoutes = 1).cullFailedRoutes)
    }
}
