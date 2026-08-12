package com.ldp.reader.ui.activity

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.source.SourceEngineCatalogPersistencePolicy
import com.ldp.reader.sourceengine.catalog.CatalogTitleEnricher
import com.ldp.reader.sourceengine.catalog.ChapterNormalizer

internal enum class ReaderCatalogPublicationOutcome {
    APPLIED_CHANGED,
    APPLIED_UNCHANGED,
    RETAINED_CURRENT,
    REJECTED,
    NO_CHANGE,
    FETCH_FAILED,
    STALE
}

/**
 * Serializes SourceEngine catalog publication without serializing the network work itself.
 *
 * Each request captures the catalog revision and identity it started from. The first request
 * that publishes a changed catalog advances the revision; every other result from the old
 * baseline is then stale, even if it would have been compatible with that old baseline.
 */
internal class ReaderCatalogPublicationGate {
    private val chapterNormalizer = ChapterNormalizer()
    private val titleEnricher = CatalogTitleEnricher(chapterNormalizer)

    class Session internal constructor(
        internal val serial: Long,
        internal val bookKey: String
    )

    class Attempt internal constructor(
        internal val session: Session,
        internal val revision: Long,
        internal val baseline: CatalogIdentity
    )

    internal data class CatalogIdentity(
        val chapters: List<ChapterIdentity>
    )

    internal data class ChapterIdentity(
        val route: String?,
        val title: String
    )

    private var nextSessionSerial = 0L
    private var activeSession: Session? = null
    private var revision = 0L

    @Synchronized
    fun beginSession(bookKey: String): Session {
        check(nextSessionSerial < Long.MAX_VALUE) { "Reader catalog session generation exhausted" }
        return Session(++nextSessionSerial, bookKey).also { session ->
            activeSession = session
            revision = 0L
        }
    }

    @Synchronized
    fun capture(
        session: Session,
        current: List<BookChapterBean>
    ): Attempt {
        return Attempt(
            session = session,
            revision = revision,
            baseline = identityOf(current)
        )
    }

    @Synchronized
    fun publishMainResult(
        attempt: Attempt,
        current: List<BookChapterBean>,
        candidate: List<BookChapterBean>,
        publish: (List<BookChapterBean>) -> Unit
    ): ReaderCatalogPublicationOutcome {
        if (!isCurrent(attempt, current)) return ReaderCatalogPublicationOutcome.STALE
        val evaluation = SourceEngineCatalogPersistencePolicy.evaluate(current, candidate)
        if (!evaluation.compatible) {
            publish(current)
            return ReaderCatalogPublicationOutcome.RETAINED_CURRENT
        }
        publish(candidate)
        if (!evaluation.changed) {
            return ReaderCatalogPublicationOutcome.APPLIED_UNCHANGED
        }
        revision += 1L
        return ReaderCatalogPublicationOutcome.APPLIED_CHANGED
    }

    @Synchronized
    fun promoteTierResult(
        attempt: Attempt,
        current: List<BookChapterBean>,
        candidate: List<BookChapterBean>,
        publish: (List<BookChapterBean>) -> Unit
    ): ReaderCatalogPublicationOutcome {
        if (!isCurrent(attempt, current)) return ReaderCatalogPublicationOutcome.STALE
        val evaluation = SourceEngineCatalogPersistencePolicy.evaluate(current, candidate)
        if (!evaluation.compatible) {
            val titleProjection = projectSafeTitleUpgradesOntoCurrentRoutes(current, candidate)
                ?: return ReaderCatalogPublicationOutcome.REJECTED
            publish(titleProjection)
            revision += 1L
            return ReaderCatalogPublicationOutcome.APPLIED_CHANGED
        }
        if (!evaluation.changed) return ReaderCatalogPublicationOutcome.NO_CHANGE
        publish(candidate)
        revision += 1L
        return ReaderCatalogPublicationOutcome.APPLIED_CHANGED
    }

    /**
     * Performs one bounded recapture after a tier result lost a publication race.
     *
     * The result is merged against both the attempt's original baseline and the latest
     * published catalog. This keeps compatible changes from both branches (for example a
     * main-request append plus a tier title completion), while route conflicts remain rejected.
     */
    @Synchronized
    fun retryStaleTierResult(
        attempt: Attempt,
        current: List<BookChapterBean>,
        candidate: List<BookChapterBean>,
        publish: (List<BookChapterBean>) -> Unit
    ): ReaderCatalogPublicationOutcome {
        if (activeSession != attempt.session) return ReaderCatalogPublicationOutcome.STALE
        if (isCurrent(attempt, current)) {
            return promoteTierResult(attempt, current, candidate, publish)
        }
        val merged = mergeCompatibleBranches(
            baseline = catalogOf(attempt.baseline),
            current = current,
            candidate = candidate
        ) ?: return ReaderCatalogPublicationOutcome.REJECTED
        val recapturedAttempt = Attempt(
            session = attempt.session,
            revision = revision,
            baseline = identityOf(current)
        )
        return promoteTierResult(recapturedAttempt, current, merged, publish)
    }

    /** Performs the symmetric bounded recapture when the main result loses to a tier update. */
    @Synchronized
    fun retryStaleMainResult(
        attempt: Attempt,
        current: List<BookChapterBean>,
        candidate: List<BookChapterBean>,
        publish: (List<BookChapterBean>) -> Unit
    ): ReaderCatalogPublicationOutcome {
        if (activeSession != attempt.session) return ReaderCatalogPublicationOutcome.STALE
        if (isCurrent(attempt, current)) {
            return publishMainResult(attempt, current, candidate, publish)
        }
        val merged = mergeCompatibleBranches(
            baseline = catalogOf(attempt.baseline),
            current = current,
            candidate = candidate
        ) ?: return ReaderCatalogPublicationOutcome.REJECTED
        val recapturedAttempt = Attempt(
            session = attempt.session,
            revision = revision,
            baseline = identityOf(current)
        )
        return publishMainResult(recapturedAttempt, current, merged, publish)
    }

    private fun isCurrent(
        attempt: Attempt,
        current: List<BookChapterBean>
    ): Boolean {
        return activeSession == attempt.session &&
            revision == attempt.revision &&
            identityOf(current) == attempt.baseline
    }

    private fun identityOf(chapters: List<BookChapterBean>): CatalogIdentity {
        return CatalogIdentity(
            chapters.map { chapter ->
                ChapterIdentity(
                    route = chapter.link,
                    title = chapter.title.orEmpty()
                )
            }
        )
    }

    private fun projectSafeTitleUpgradesOntoCurrentRoutes(
        current: List<BookChapterBean>,
        candidate: List<BookChapterBean>
    ): List<BookChapterBean>? {
        if (current.isEmpty() || candidate.isEmpty()) return null
        val candidateTitlesByOrdinal = candidate
            .mapNotNull { chapter ->
                val normalized = chapterNormalizer.normalize(chapter.title.orEmpty())
                normalized.ordinal?.let { ordinal -> ordinal to normalized.displayTitle }
            }
            .groupBy({ (ordinal, _) -> ordinal }, { (_, title) -> title })
        val upgrades = current.mapIndexedNotNull { index, chapter ->
            val currentTitle = chapter.title.orEmpty()
            val ordinal = chapterNormalizer.normalize(currentTitle).ordinal ?: return@mapIndexedNotNull null
            val upgradedTitle = candidateTitlesByOrdinal[ordinal]
                .orEmpty()
                .asSequence()
                .filter { candidateTitle -> titleEnricher.isSafeTitleUpgrade(currentTitle, candidateTitle) }
                .distinct()
                .singleOrNull()
                ?: return@mapIndexedNotNull null
            index to upgradedTitle
        }.toMap()
        if (upgrades.isEmpty()) return null
        val projected = current.mapIndexed { index, chapter ->
            upgrades[index]?.let { title -> chapter.copyWithTitle(title) } ?: chapter
        }
        val projectionEvaluation = SourceEngineCatalogPersistencePolicy.evaluate(current, projected)
        return projected.takeIf { projectionEvaluation.compatible && projectionEvaluation.changed }
    }

    private fun BookChapterBean.copyWithTitle(newTitle: String): BookChapterBean {
        val source = this
        return BookChapterBean().also { copy ->
            copy.id = source.id
            copy.chapterId = source.chapterId
            copy.link = source.link
            copy.title = newTitle
            copy.taskName = source.taskName
            copy.sourceIntegrityState = source.sourceIntegrityState
            copy.sourceIntegrityConfidence = source.sourceIntegrityConfidence
            copy.sourceIntegrityReason = source.sourceIntegrityReason
            copy.setUnreadble(source.getUnreadble())
            copy.setValidInZhuishu(source.getValidInZhuishu())
            copy.bookId = source.bookId
            copy.start = source.start
            copy.end = source.end
        }
    }

    private fun catalogOf(identity: CatalogIdentity): List<BookChapterBean> {
        return identity.chapters.map { chapter ->
            BookChapterBean().apply {
                link = chapter.route
                title = chapter.title
            }
        }
    }

    private fun mergeCompatibleBranches(
        baseline: List<BookChapterBean>,
        current: List<BookChapterBean>,
        candidate: List<BookChapterBean>
    ): List<BookChapterBean>? {
        if (!SourceEngineCatalogPersistencePolicy.evaluate(baseline, current).compatible) return null
        if (!SourceEngineCatalogPersistencePolicy.evaluate(baseline, candidate).compatible) return null

        val overlapSize = minOf(current.size, candidate.size)
        val merged = ArrayList<BookChapterBean>(maxOf(current.size, candidate.size))
        repeat(overlapSize) { index ->
            val currentChapter = current[index]
            val candidateChapter = candidate[index]
            val currentTitle = currentChapter.title.orEmpty()
            val candidateTitle = candidateChapter.title.orEmpty()
            val accepted = when {
                currentChapter.link == candidateChapter.link && currentTitle == candidateTitle ->
                    currentChapter
                SourceEngineCatalogPersistencePolicy.evaluate(
                    listOf(currentChapter),
                    listOf(candidateChapter)
                ).compatible -> candidateChapter
                SourceEngineCatalogPersistencePolicy.evaluate(
                    listOf(candidateChapter),
                    listOf(currentChapter)
                ).compatible -> currentChapter
                else -> return null
            }
            merged += accepted
        }
        if (current.size > overlapSize) {
            merged += current.subList(overlapSize, current.size)
        } else if (candidate.size > overlapSize) {
            merged += candidate.subList(overlapSize, candidate.size)
        }
        return merged.takeIf {
            SourceEngineCatalogPersistencePolicy.evaluate(current, it).compatible
        }
    }
}
