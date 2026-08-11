package com.ldp.reader.model.objectbox

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/** Durable request/activation fence for token-owned local TXT chapter rows. */
@Entity
class ObjectBoxChapterCatalogStateEntity {
    @Id
    var objectBoxId: Long = 0

    @Index
    @Unique
    var bookId: String = ""

    var requestedGeneration: Long = 0
    var requestedFingerprint: String? = null
    var activeGeneration: Long = 0
    var activeFingerprint: String? = null
}
