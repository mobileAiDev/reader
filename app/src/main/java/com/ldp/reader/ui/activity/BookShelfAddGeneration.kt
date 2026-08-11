package com.ldp.reader.ui.activity

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class BookShelfAddGeneration {
    private val generation = AtomicLong(0L)
    private val readerOwnsCatalogPersistence = AtomicBoolean(false)

    fun beginAdd(): Long {
        readerOwnsCatalogPersistence.set(false)
        return generation.incrementAndGet()
    }

    fun giveUp() {
        generation.incrementAndGet()
    }

    fun handOffCatalogPersistenceToReader() {
        readerOwnsCatalogPersistence.set(true)
    }

    fun isShelfAddCurrent(candidate: Long): Boolean = generation.get() == candidate

    fun isCurrent(candidate: Long): Boolean {
        return isShelfAddCurrent(candidate) && !readerOwnsCatalogPersistence.get()
    }
}
