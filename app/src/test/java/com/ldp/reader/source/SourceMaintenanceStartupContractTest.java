package com.ldp.reader.source;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class SourceMaintenanceStartupContractTest {
    @Test
    public void appStartupAutoStartsLowPriorityV8Maintenance() throws IOException {
        String app = readFile("src/main/java/com/ldp/reader/App.kt");

        assertTrue(app.contains("BookContentProviderRouter.startLowPriorityV8Maintenance()"));
    }

    @Test
    public void mediaReadersStopLowPriorityV8Maintenance() throws IOException {
        String audioPlayer = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String comicReader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(audioPlayer.contains("stopLowPriorityV8Maintenance(\"audio-player\")"));
        assertTrue(comicReader.contains("stopLowPriorityV8Maintenance(\"comic-reader\")"));
    }

    @Test
    public void settingsStillOffersManualV8MaintenanceStart() throws IOException {
        String settings = readFile("src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt");

        assertTrue(settings.contains("BookContentProviderRouter.startLowPriorityV8Maintenance()"));
        assertTrue(settings.contains("BookContentProviderRouter.stopLowPriorityV8Maintenance(\"settings-disabled\")"));
    }

    @Test
    public void currentV8MaintenanceRestoresCacheWithoutContentTier() throws IOException {
        String provider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");

        int currentBranch = provider.indexOf("if (candidate.cacheState == V8MaintenanceCacheState.CURRENT)");
        int cacheRestore = provider.indexOf("recordCachedV8Marks(current, \"maintenance-current-cache\", \"maintenance\")", currentBranch);
        int readyCheck = provider.indexOf("cachedV8MarksCanSkip(current, V8ValidationScope.FULL)", cacheRestore);
        int tierSkipped = provider.indexOf("\"tierSkipped\" to true", currentBranch);
        int insufficient = provider.indexOf("\"source_catalog_v8_maintenance_current_cache_insufficient\"", tierSkipped);
        int finishedEvent = provider.indexOf("\"source_catalog_v8_maintenance_book_finished\"", currentBranch);
        int coldMaintenance = provider.indexOf("val ready = runLowPriorityContentTierMaintenance(", currentBranch);

        assertTrue(currentBranch >= 0);
        assertTrue(cacheRestore > currentBranch);
        assertTrue(readyCheck > cacheRestore);
        assertTrue(finishedEvent > cacheRestore);
        assertTrue(tierSkipped > finishedEvent);
        assertTrue(insufficient > tierSkipped);
        assertTrue(coldMaintenance > insufficient);
    }

    @Test
    public void lowPriorityV8MaintenanceUsesReadingLightTier() throws IOException {
        String provider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");

        int method = provider.indexOf("private suspend fun runLowPriorityContentTierMaintenance(");
        int prepare = provider.indexOf("prepareBookContentTier(", method);
        int lightMode = provider.indexOf("mode = SourceContentTierMode.READING_LIGHT", prepare);

        assertTrue(method >= 0);
        assertTrue(prepare > method);
        assertTrue(lightMode > prepare);
    }

    @Test
    public void readingLightTierSkipsBookFingerprintBuild() throws IOException {
        String provider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");

        assertTrue(provider.contains("probeCatalogTail(resolved, mode)"));
        assertTrue(provider.contains("mode == SourceContentTierMode.READING_LIGHT"));
        assertTrue(provider.contains("\"source_catalog_tail_probe_skipped\""));
        assertTrue(provider.contains("\"skip_content_probe\""));
        assertTrue(provider.contains("\"source_quality_trusted_lightweight\""));
        assertTrue(provider.contains("\"reading_light_skip_fingerprint\""));
        assertTrue(provider.contains("promoteTrustedResolvedBookInWaterfall(waterfall, resolved, mode)"));
    }

    @Test
    public void readingLightTierUsesPersistedCandidatesBeforePersonalSearch() throws IOException {
        String provider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");

        int loadPersisted = provider.indexOf("loadPersistedTierIntoWaterfall(waterfall, collBookBean?.get_id())");
        int skipCheck = provider.indexOf("skipReadingLightPersonalRefresh", loadPersisted);
        int skippedEvent = provider.indexOf("\"source_content_tier_personal_search_skipped\"", skipCheck);
        int refresh = provider.indexOf("refreshBookContentWaterfall(sourceBook, FallbackSearchPolicy.PERSONAL_ONLY)", skipCheck);

        assertTrue(loadPersisted >= 0);
        assertTrue(skipCheck > loadPersisted);
        assertTrue(skippedEvent > skipCheck);
        assertTrue(refresh > skippedEvent);
    }

    @Test
    public void v8ScheduleRestoresCurrentCacheBeforeStartingBackgroundJob() throws IOException {
        String provider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");

        int scheduleMethod = provider.indexOf("private fun scheduleV8ValidationForResolvedBooks(");
        int effectiveScope = provider.indexOf("effectiveV8ValidationScope(resolved, reason, scope, currentCached)", scheduleMethod);
        int cacheRestore = provider.indexOf("restoreCachedV8MarksForResolvedBook(resolved, reason, \"schedule\", effectiveScope, currentCached)", effectiveScope);
        int cacheSkipReason = provider.indexOf("\"reason\" to \"cache_current\"", cacheRestore);
        int newRequestScope = provider.indexOf("newSourceRequestScope(", cacheRestore);

        assertTrue(scheduleMethod >= 0);
        assertTrue(effectiveScope > scheduleMethod);
        assertTrue(cacheRestore > effectiveScope);
        assertTrue(cacheSkipReason > cacheRestore);
        assertTrue(newRequestScope > cacheRestore);
        assertTrue(cacheSkipReason < newRequestScope);
    }

    @Test
    public void v8MarkLogsIncludeSourcePhaseCountsAndBoundedSamples() throws IOException {
        String provider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");

        int helper = provider.indexOf("private fun traceV8MarkDetails(");
        int cacheRestored = provider.indexOf("\"source_catalog_v8_cache_marks_restored\"");
        int cacheDetails = provider.indexOf("origin = \"cache_restore\"", cacheRestored);
        int commitDetails = provider.indexOf("origin = if (epoch.replayedFromCache) \"cache_replay_commit\" else \"v8_commit\"");

        assertTrue(helper >= 0);
        assertTrue(cacheRestored >= 0);
        assertTrue(cacheDetails > cacheRestored);
        assertTrue(commitDetails >= 0);
        assertTrue(provider.contains("\"source_catalog_v8_mark_details\""));
        assertTrue(provider.contains("\"sample\" to sample"));
        assertTrue(provider.contains("\"idx\" to sortedTargets.take(V8_MARK_DETAIL_SAMPLE_LIMIT).joinToString(\",\")"));
        assertTrue(provider.contains("\"h\" to marks.count { mark -> mark.state.isBadForTail }"));
        assertTrue(provider.contains("private fun v8MarkDetailFields("));
        assertTrue(provider.contains("private const val V8_MARK_DETAIL_SAMPLE_LIMIT = 4"));
    }

    @Test
    public void readingLightV8CanPromoteTailRiskScopeWithoutFullTierWork() throws IOException {
        String provider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");

        assertTrue(provider.contains("READING_LIGHT("));
        assertTrue(provider.contains("traceName = \"reading_light\""));
        assertTrue(provider.contains("targetLimit = 2"));
        assertTrue(provider.contains("allowExpansion = false"));
        assertTrue(provider.contains("READING_TAIL("));
        assertTrue(provider.contains("traceName = \"reading_tail\""));
        assertTrue(provider.contains("targetLimit = 16"));
        assertTrue(provider.contains("effectiveV8ValidationScope("));
        assertTrue(provider.contains("\"source_catalog_v8_scope_promoted\""));
        assertTrue(provider.contains("SourceEngineV8TailScopePolicy.recommendReadingTail"));
        assertTrue(provider.contains("allowSecondaryProbe = false"));
        assertTrue(provider.contains("scope = v8ValidationScopeForContentTier(mode)"));
        assertTrue(provider.contains("scopedV8InitialTargetIndexes(scope, plan, validationChapters)"));
        assertTrue(provider.contains("if (scope != V8ValidationScope.FULL)"));
        assertTrue(provider.contains("\"source_catalog_v8_validate_expand_skipped\""));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
