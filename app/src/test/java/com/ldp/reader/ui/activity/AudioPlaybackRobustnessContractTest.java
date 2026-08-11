package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.R;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class AudioPlaybackRobustnessContractTest {
    @Test
    public void episodeBoundaryButtonsAreDisabledAsWellAsDimmed() throws Exception {
        String activity = readSource("AudioPlayerActivity.kt");

        assertTrue(activity.contains("binding.audioPlayerPrevious.isEnabled = hasPrevious"));
        assertTrue(activity.contains("binding.audioPlayerNext.isEnabled = hasNext"));
        assertTrue(activity.contains("binding.audioPlayerPrevious.alpha = if (hasPrevious)"));
        assertTrue(activity.contains("binding.audioPlayerNext.alpha = if (hasNext)"));
    }

    @Test
    public void detailFailureOffersRetryAndMiniPlayerActionIsInteractive() throws IOException {
        assertNotEquals(0, R.id.audio_detail_loading_retry);
        String layout = readFile("src/main/res/layout/activity_audio_detail.xml");
        String activity = readSource("AudioDetailActivity.kt");

        assertTrue(layout.contains("audio_detail_loading_retry"));
        assertTrue(layout.contains("android:text=\"重新加载\""));
        assertTrue(activity.contains("audioDetailLoadingRetry.setOnClickListener { loadDetail() }"));
        assertTrue(activity.contains("audioDetailMiniAction.setOnClickListener"));
        assertTrue(activity.contains("AudioMiniPlayerAction.playButtonCommand"));
        assertTrue(activity.contains("miniPlayerHandler.post(miniPlayerTicker)"));
    }

    @Test
    public void audioScreensAndAutoAdvanceShareChronologicalEpisodeOrder() throws IOException {
        String detail = readSource("AudioDetailActivity.kt");
        String player = readSource("AudioPlayerActivity.kt");
        String autoAdvance = readFile("src/main/java/com/ldp/reader/audio/AudioAutoAdvance.kt");

        assertTrue(detail.contains("episodes = AudioChapterOrder.chronological(result.episodes)"));
        assertTrue(player.contains("AudioChapterOrder.chronological("));
        assertTrue(autoAdvance.contains("AudioChapterOrder.chronologicalSnapshots(chapters)"));
    }

    @Test
    public void detailRefreshesShelfCtaAfterReturningFromPlayer() throws IOException {
        String activity = readSource("AudioDetailActivity.kt");
        int onResumeStart = activity.indexOf("override fun onResume()");
        int onPauseStart = activity.indexOf("override fun onPause()", onResumeStart);
        String onResume = activity.substring(onResumeStart, onPauseStart);

        assertTrue(onResume.contains("MediaShelfStore.restoreForBook(this, routeId)"));
        assertTrue(onResume.contains("renderShelfButton()"));
    }

    @Test
    public void playerHasBoundedLoadingAndClickableRetryState() throws IOException {
        String activity = readSource("AudioPlayerActivity.kt");
        String layout = readFile("src/main/res/layout/activity_audio_player.xml");
        String detailProgress = readFile("src/main/java/com/ldp/reader/media/MediaDetailLoadProgress.kt");
        String failureCopy = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackFailureCopy.kt");

        assertTrue(layout.contains("audio_player_catalog_state"));
        assertTrue(activity.contains("withTimeoutOrNull(PLAYER_LOAD_TIMEOUT_MS)"));
        assertTrue(activity.contains("AudioRepositoryCallRunner.runForegroundPlayer"));
        assertTrue(activity.contains("private fun renderPlayerFailure(message: String)"));
        assertTrue(activity.contains("if (retryFailedPlayback()) return@setOnClickListener"));
        assertTrue(activity.contains("if (!playerRetryEnabled) return false"));
        assertTrue(failureCopy.contains("音频文件无效或格式暂不支持，请重试其他章节或内容"));
        assertTrue(failureCopy.contains("请检查网络后重试"));
        assertTrue(failureCopy.contains("暂时无法播放，请稍后重试"));
        assertTrue(detailProgress.contains("暂时无法加载目录，点击重试"));
        assertTrue(activity.contains("MediaDetailLoadProgress.Step.FAILED.label"));
        assertTrue(activity.contains("if (catalogRetryEnabled) loadPlayer()"));
    }

    @Test
    public void playerRejectsStaleControllerCallbacksAndSwitchesEpisodesThroughOneTransition() throws IOException {
        String activity = readSource("AudioPlayerActivity.kt");

        assertTrue(activity.contains("controllerConnectGeneration"));
        assertTrue(activity.contains("isCurrentControllerConnection("));
        assertTrue(activity.contains("AudioEpisodeNavigation.sibling(episodes, chapterRouteId"));
        assertTrue(activity.contains("AudioPlaybackLoadIntentPolicy.afterServiceDispatch(loadIntent)"));
        int transitionStart = activity.indexOf("private fun transitionToEpisode(");
        int transitionEnd = activity.indexOf("private fun renderCover(", transitionStart);
        String transition = activity.substring(transitionStart, transitionEnd);
        assertTrue(transition.contains("controller?.pause()"));
        assertTrue(transition.contains("progressHandler.removeCallbacks(progressTicker)"));
        assertTrue(transition.contains("releaseControllerConnection()"));
    }

    @Test
    public void configurationRecreationDoesNotReplayOneShotPlaybackCommands() throws IOException {
        String activity = readSource("AudioPlayerActivity.kt");

        assertTrue(activity.contains(
                "savedInstanceState?.getBoolean(STATE_PLAYBACK_COMMAND_CONSUMED, false) == true"));
        assertTrue(activity.contains(
                "readIntent(intent, readPlaybackCommand = !playbackCommandConsumed)"));
        assertTrue(activity.contains(
                "forceStartPlayback = readPlaybackCommand && intent.getBooleanExtra(EXTRA_FORCE_START, false)"));
        assertTrue(activity.contains(
                "autoPlayPlayback = readPlaybackCommand && intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)"));
        assertTrue(activity.contains(
                "outState.putBoolean(STATE_PLAYBACK_COMMAND_CONSUMED, playbackCommandConsumed)"));
        assertTrue(activity.contains("consumePlaybackCommand()"));
        assertTrue(activity.contains("intent.removeExtra(EXTRA_FORCE_START)"));
        assertTrue(activity.contains("intent.removeExtra(EXTRA_AUTO_PLAY)"));
        assertTrue(activity.contains("playbackCommandConsumed = false\n        readIntent(intent, readPlaybackCommand = true)"));
    }

    @Test
    public void serviceGuardsAutoAdvanceAndRefreshesAColdCatalog() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");

        assertTrue(service.contains("AudioPlaybackTransitionGate"));
        assertTrue(service.contains("cancelPendingAutoAdvance("));
        assertTrue(service.contains("AudioAutoAdvanceResolver.remainingEpisodes("));
        assertTrue(service.contains("AudioAutoAdvanceCandidateStateMachine("));
        assertTrue(service.contains("AudioRepositoryCallRunner.runBackgroundServiceResult"));
        assertTrue(service.contains("media_audio_auto_next_failed"));
        assertTrue(service.contains("catalog_resolve_failed"));
        assertTrue(service.contains("transitionGate.canApply("));
    }

    @Test
    public void autoAdvanceStagesMediaButPersistsOnlyAfterTheCandidateIsReady() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        String player = readSource("AudioPlayerActivity.kt");
        int prepareStart = service.indexOf("private fun prepareAutoAdvanceCandidate(");
        int stageStart = service.indexOf("private fun stageAutoAdvanceCandidate(", prepareStart);
        int finalizeStart = service.indexOf("private fun finalizeStagedAutoAdvanceCandidate(", stageStart);
        int failureTraceStart = service.indexOf("private fun traceAutoAdvanceFailure(", finalizeStart);
        String prepare = service.substring(prepareStart, stageStart);
        String stage = service.substring(stageStart, finalizeStart);
        String finalize = service.substring(finalizeStart, failureTraceStart);
        int playerRouteSyncStart = player.indexOf("private fun syncPlaybackRoute(");
        int playerUiStart = player.indexOf("private fun updatePlayerUi()", playerRouteSyncStart);
        String playerRouteSync = player.substring(playerRouteSyncStart, playerUiStart);
        int playerLoadStart = player.indexOf("private fun loadPlayer()");
        int bindExistingStart = player.indexOf("private fun bindExistingPlaybackSession()", playerLoadStart);
        String manualPlayerLoad = player.substring(playerLoadStart, bindExistingStart);

        assertTrue(service.contains("autoAdvanceStateMachine.advance("));
        assertTrue(prepare.contains("createAudioMediaSource("));
        assertTrue(prepare.contains("exoPlayer.prepare()"));
        assertFalse(stage.contains("AudioPlaybackStateStore.setNowPlaying("));
        assertFalse(stage.contains("AudioPlaybackProgressStore.clear("));
        assertFalse(stage.contains("MediaShelfStore.addOrUpdateForChapter("));
        assertFalse(stage.contains("MediaShelfStore.updateAudioChapter("));
        assertFalse(
                "MediaItem transition is emitted before READY, so Activity route sync must not persist shelf state",
                playerRouteSync.contains("MediaShelfStore.addOrUpdateForChapter("));
        assertTrue(
                "manual playback keeps the existing shelf persistence semantics",
                manualPlayerLoad.contains("MediaShelfStore.addOrUpdateForChapter("));
        assertTrue(finalize.contains("AudioPlaybackStateStore.setNowPlaying("));
        assertTrue(finalize.contains("MediaShelfStore.updateAudioChapter("));
        int readyBranchStart = service.indexOf("Player.STATE_READY -> {");
        int endedBranchStart = service.indexOf("Player.STATE_ENDED -> {", readyBranchStart);
        assertTrue(readyBranchStart >= 0);
        assertTrue(endedBranchStart > readyBranchStart);
        assertTrue(
                service.substring(readyBranchStart, endedBranchStart)
                        .contains("finalizeStagedAutoAdvanceCandidate()"));
    }

    @Test
    public void manualEpisodeSelectionCancelsPendingAutoAdvanceBeforeRouteResolutionStarts() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        String player = readSource("AudioPlayerActivity.kt");
        String detail = readSource("AudioDetailActivity.kt");
        String catalog = readSource("MediaCatalogActivity.kt");
        String bookshelf = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        int manualStart = player.indexOf("fun startManualSelection(");
        int regularStart = player.indexOf("fun start(", manualStart);
        String manualSelection = player.substring(manualStart, regularStart);
        int shelfOpenStart = bookshelf.indexOf("private fun openMediaShelfItem(");
        int shelfOpenEnd = bookshelf.indexOf("private class ReaderBookGridSpacingDecoration", shelfOpenStart);
        String shelfOpen = bookshelf.substring(shelfOpenStart, shelfOpenEnd);
        int floatingOpenStart = bookshelf.indexOf("homeBookshelfAudioFloat?.setOnClickListener");
        int floatingOpenEnd = bookshelf.indexOf("homeBookshelfAudioPlay?.setOnClickListener", floatingOpenStart);
        String floatingOpen = bookshelf.substring(floatingOpenStart, floatingOpenEnd);
        int cancellationStart = service.indexOf("private fun cancelPendingAutoAdvance(");
        int cacheStart = service.indexOf("private fun audioCache()", cancellationStart);
        String cancellation = service.substring(cancellationStart, cacheStart);

        assertTrue(service.contains("ACTION_CANCEL_AUTO_ADVANCE ->"));
        assertTrue(service.contains("cancelPendingAutoAdvance(\"manual_selection\")"));
        assertTrue(cancellation.contains("val invalidation = transitionGate.invalidatePendingTransitions()"));
        assertTrue(cancellation.contains("invalidation.shouldStopMedia("));
        assertTrue(cancellation.contains("player?.stop()"));
        assertTrue(cancellation.contains("player?.clearMediaItems()"));
        assertTrue(manualSelection.contains("setAction(AudioPlaybackService.ACTION_CANCEL_AUTO_ADVANCE)"));
        assertTrue(manualSelection.indexOf("startService(") < manualSelection.indexOf("start("));
        assertTrue(player.contains("startManualSelection("));
        assertTrue(detail.contains("AudioPlayerActivity.startManualSelection("));
        assertTrue(catalog.contains("AudioPlayerActivity.startManualSelection("));
        assertTrue(shelfOpen.contains("ReaderMediaKind.AUDIO -> AudioPlayerActivity.startManualSelection("));
        assertTrue(floatingOpen.contains("AudioPlayerActivity.openCurrent("));
        assertFalse(floatingOpen.contains("startManualSelection("));
    }

    @Test
    public void miniPlayerOpeningCurrentPlaybackBindsWithoutRedispatchingItsUrl() throws IOException {
        String player = readSource("AudioPlayerActivity.kt");
        String detail = readSource("AudioDetailActivity.kt");
        String bookshelf = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");

        assertTrue(player.contains("fun openCurrent("));
        assertTrue(player.contains("EXTRA_BIND_EXISTING_SESSION_ONLY"));
        int loadPlayerStart = player.indexOf("private fun loadPlayer()");
        int bindStart = player.indexOf("private fun bindExistingPlaybackSession()");
        String loadPlayer = player.substring(loadPlayerStart, bindStart);
        assertTrue(loadPlayer.contains("if (bindExistingSessionOnly)"));
        assertTrue(loadPlayer.contains("bindExistingPlaybackSession()"));
        int loadRepositoryStart = player.indexOf("private suspend fun <T> loadRepositoryValue", bindStart);
        String bindExisting = player.substring(bindStart, loadRepositoryStart);
        assertTrue(bindExisting.contains("connectController()"));
        assertFalse(bindExisting.contains("MediaSourceRepository.audioRequest("));
        assertFalse(bindExisting.contains("startService("));

        int detailMiniStart = detail.indexOf("audioDetailMiniPlayer.setOnClickListener");
        int detailRetryStart = detail.indexOf("audioDetailLoadingRetry.setOnClickListener", detailMiniStart);
        String detailMini = detail.substring(detailMiniStart, detailRetryStart);
        assertTrue(detailMini.contains("AudioPlayerActivity.openCurrent("));
        assertFalse(detailMini.contains("AudioPlayerActivity.startManualSelection("));
        assertFalse(detail.contains("AudioPlayerActivity.start("));

        int shelfMiniStart = bookshelf.indexOf("homeBookshelfAudioFloat?.setOnClickListener");
        int shelfPlayStart = bookshelf.indexOf("homeBookshelfAudioPlay?.setOnClickListener", shelfMiniStart);
        String shelfMini = bookshelf.substring(shelfMiniStart, shelfPlayStart);
        assertTrue(shelfMini.contains("AudioPlayerActivity.openCurrent("));
        assertFalse(shelfMini.contains("AudioPlayerActivity.startManualSelection("));
        assertFalse(bookshelf.contains("AudioPlayerActivity.start("));
    }

    @Test
    public void asyncErrorOfCommittedAutoEpisodeResumesOnlyItsUntriedCandidates() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        int playerErrorStart = service.indexOf("override fun onPlayerError(error: PlaybackException)");
        int playingChangedStart = service.indexOf("override fun onIsPlayingChanged", playerErrorStart);
        String playerError = service.substring(playerErrorStart, playingChangedStart);

        assertTrue(playerError.contains("continueAutoAdvanceAfterAsyncFailure(error)"));
        assertTrue(service.contains("transitionGate.rememberStagedAutoAdvance(ticket, result)"));
        assertTrue(service.contains("transitionGate.consumeStagedAutoAdvanceFailure(failedRouteId)"));
        assertTrue(service.contains("continuation.remainingCandidates"));
        assertTrue(service.contains("expectedCurrentRouteId = failedRouteId"));
    }

    @Test
    public void serviceAndControllerSharePersistedSpeedAndSleepLifecycle() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        String stateStore = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackStateStore.kt");

        assertTrue(service.contains("onPlaybackParametersChanged"));
        assertTrue(service.contains("restoreSleepTimerFromState()"));
        assertTrue(service.contains("clearServiceTransientState(this)"));
        assertTrue(stateStore.contains("playbackSpeed = carryOver.playbackSpeed"));
        assertTrue(stateStore.contains("sleepTimerEndAtMs = carryOver.sleepTimerEndAtMs"));
    }

    @Test
    public void serviceScopesMedia3OptInAndKeepsHeaderRetryLinear() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");

        assertTrue(service.contains("import androidx.annotation.OptIn"));
        assertTrue(service.contains("import androidx.media3.common.util.UnstableApi"));
        assertTrue(service.contains("@OptIn(UnstableApi::class)\n    private fun playCurrent("));
        assertTrue(service.contains("@OptIn(UnstableApi::class)\n    private fun audioCache("));
        assertTrue(service.contains("@OptIn(UnstableApi::class)\n        private var sharedAudioCache: SimpleCache?"));
        assertFalse(service.contains("@OptIn(UnstableApi::class)\nclass AudioPlaybackService"));
        assertTrue(service.contains(
                "        currentHeaders = retryHeaders\n"
                        + "        Log.i(TAG, \"Retrying audio playback without host-bound headers after HTTP $responseCode\")\n"
                        + "        playCurrent(retryHeaders)"));
    }

    @Test
    public void serviceConsumesForceStartBeforeHeaderFallbackCanReplayCurrentMedia() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        int playCurrentStart = service.indexOf("private fun playCurrent(headers: Map<String, String>)");
        int playbackEndedStart = service.indexOf("private fun handlePlaybackEnded()", playCurrentStart);
        String playCurrent = service.substring(playCurrentStart, playbackEndedStart);

        assertTrue(playCurrent.contains("val forceStart = forceStartCurrent"));
        assertTrue(playCurrent.contains("forceStartCurrent = false"));
        assertTrue(playCurrent.contains("val resumePosition = if (forceStart)"));
        assertTrue(playCurrent.indexOf("val forceStart = forceStartCurrent")
                < playCurrent.indexOf("forceStartCurrent = false"));
        assertTrue(playCurrent.indexOf("forceStartCurrent = false")
                < playCurrent.indexOf("val resumePosition = if (forceStart)"));
        assertTrue(playCurrent.indexOf("forceStartCurrent = false") < playCurrent.indexOf("prepare()"));
        assertTrue(playCurrent.contains("\"forceStart\" to forceStart"));
        assertFalse(playCurrent.contains("if (forceStartCurrent)"));
    }

    @Test
    public void serviceContainsSynchronousMediaFailuresAndPlayerRendersThemAsRetryable() throws IOException {
        String service = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackService.kt");
        String player = readSource("AudioPlayerActivity.kt");

        assertTrue(service.contains("AudioPlaybackCommandBoundary.execute"));
        assertTrue(service.contains("handlePlaybackCommandFailure("));
        assertTrue(service.contains("AudioPlaybackFailureStore.record("));
        assertTrue(service.contains("media_audio_playback_setup_failed"));
        assertTrue(service.contains("catch (cancelled: CancellationException)"));
        assertTrue(service.contains("catch (error: Exception)"));
        assertTrue(player.contains("AudioPlaybackFailureStore.current()"));
        assertTrue(player.contains("syncRouteFromPlaybackFailure("));
        assertTrue(player.contains("AudioPlaybackFailureCopy.messageForRecordedFailure(failure)"));
    }

    private static String readSource(String fileName) throws IOException {
        return readFile("src/main/java/com/ldp/reader/ui/activity/" + fileName);
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
