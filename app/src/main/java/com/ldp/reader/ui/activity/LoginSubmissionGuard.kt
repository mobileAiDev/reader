package com.ldp.reader.ui.activity

/**
 * Owns SMS generations for the lifetime of [LoginViewModel]. SMSSDK exposes no
 * request id in afterEvent and its privacy page may switch threads, so each SDK
 * event type has one process-wide transport lease. A new ViewModel cannot send
 * a request until a previous ViewModel's terminal callback clears that lease.
 */
internal class LoginSubmissionGuard {

    private val ownerGeneration = synchronized(sdkTransportLock) {
        (++nextOwnerGeneration).also(activeOwnerGenerations::add)
    }
    private var activeSmsCodeAttempt: SmsCodeAttempt? = null
    private var activeSmsLoginAttempt: SmsLoginAttempt? = null
    private var smsLoginPhase: SmsLoginPhase? = null

    @Synchronized
    fun tryBeginSmsCodeRequest(
        phoneNumber: String,
        nowMs: Long,
        cooldownMs: Long
    ): SmsCodeStartDecision {
        if (activeSmsCodeAttempt != null) {
            return SmsCodeStartDecision(blockReason = SmsStartBlockReason.ACTIVE_ATTEMPT)
        }
        val attempt = synchronized(sdkTransportLock) {
            if (smsCodeSdkTransportAttempt != null) {
                return SmsCodeStartDecision(
                    blockReason = SmsStartBlockReason.PREVIOUS_SDK_REQUEST_ENDING
                )
            }
            SmsCodeAttempt(
                generation = ++nextGeneration,
                ownerGeneration = ownerGeneration,
                phoneNumber = phoneNumber,
                deadlineMs = nowMs + cooldownMs
            ).also { smsCodeSdkTransportAttempt = it }
        }
        activeSmsCodeAttempt = attempt
        return SmsCodeStartDecision(
            start = SmsCodeRequestStart(attempt, sdkAttemptToStart = attempt)
        )
    }

    @Synchronized
    fun onSmsCodeCooldownElapsed(attempt: SmsCodeAttempt, nowMs: Long): Boolean {
        if (activeSmsCodeAttempt != attempt || nowMs < attempt.deadlineMs) {
            return false
        }
        activeSmsCodeAttempt = null
        return true
    }

    @Synchronized
    fun onSmsCodeSdkResult(success: Boolean): SmsCodeSdkResolution {
        return finishSmsCodeSdkTransport(expectedAttempt = null, success = success)
    }

    @Synchronized
    fun onSmsCodeSdkLaunchFailed(attempt: SmsCodeAttempt): SmsCodeSdkResolution {
        return finishSmsCodeSdkTransport(expectedAttempt = attempt, success = false)
    }

    private fun finishSmsCodeSdkTransport(
        expectedAttempt: SmsCodeAttempt?,
        success: Boolean
    ): SmsCodeSdkResolution {
        val completedAttempt = synchronized(sdkTransportLock) {
            val current = smsCodeSdkTransportAttempt ?: return SmsCodeSdkResolution()
            if (expectedAttempt != null && current != expectedAttempt) {
                return SmsCodeSdkResolution()
            }
            if (
                current.ownerGeneration != ownerGeneration &&
                current.ownerGeneration in activeOwnerGenerations
            ) {
                return SmsCodeSdkResolution()
            }
            smsCodeSdkTransportAttempt = null
            current
        }
        val appliesToActiveAttempt = completedAttempt == activeSmsCodeAttempt
        val failedAttempt = completedAttempt.takeIf {
            appliesToActiveAttempt && !success
        }
        if (failedAttempt != null) {
            activeSmsCodeAttempt = null
        }
        return SmsCodeSdkResolution(
            completedAttempt = completedAttempt,
            appliesToActiveAttempt = appliesToActiveAttempt,
            success = success,
            failedAttempt = failedAttempt
        )
    }

    @Synchronized
    fun tryBeginSmsLogin(
        phoneNumber: String,
        smsCode: String,
        registrationId: String,
        nowMs: Long,
        timeoutMs: Long
    ): SmsLoginStartDecision {
        if (activeSmsLoginAttempt != null) {
            return SmsLoginStartDecision(blockReason = SmsStartBlockReason.ACTIVE_ATTEMPT)
        }
        val attempt = synchronized(sdkTransportLock) {
            if (smsLoginSdkTransportAttempt != null) {
                return SmsLoginStartDecision(
                    blockReason = SmsStartBlockReason.PREVIOUS_SDK_REQUEST_ENDING
                )
            }
            SmsLoginAttempt(
                generation = ++nextGeneration,
                ownerGeneration = ownerGeneration,
                phoneNumber = phoneNumber,
                smsCode = smsCode,
                registrationId = registrationId,
                deadlineMs = nowMs + timeoutMs
            ).also { smsLoginSdkTransportAttempt = it }
        }
        activeSmsLoginAttempt = attempt
        smsLoginPhase = SmsLoginPhase.SDK
        return SmsLoginStartDecision(
            start = SmsLoginStart(attempt, sdkAttemptToStart = attempt)
        )
    }

    @Synchronized
    fun onSmsLoginSdkResult(success: Boolean, nowMs: Long): SmsLoginSdkResolution {
        return finishSmsLoginSdkTransport(
            expectedAttempt = null,
            success = success,
            nowMs = nowMs
        )
    }

    @Synchronized
    fun onSmsLoginSdkLaunchFailed(
        attempt: SmsLoginAttempt,
        nowMs: Long
    ): SmsLoginSdkResolution {
        return finishSmsLoginSdkTransport(
            expectedAttempt = attempt,
            success = false,
            nowMs = nowMs
        )
    }

    private fun finishSmsLoginSdkTransport(
        expectedAttempt: SmsLoginAttempt?,
        success: Boolean,
        nowMs: Long
    ): SmsLoginSdkResolution {
        val completedAttempt = synchronized(sdkTransportLock) {
            val current = smsLoginSdkTransportAttempt ?: return SmsLoginSdkResolution()
            if (expectedAttempt != null && current != expectedAttempt) {
                return SmsLoginSdkResolution()
            }
            if (
                current.ownerGeneration != ownerGeneration &&
                current.ownerGeneration in activeOwnerGenerations
            ) {
                return SmsLoginSdkResolution()
            }
            smsLoginSdkTransportAttempt = null
            current
        }

        var startBackendAttempt: SmsLoginAttempt? = null
        var failedAttempt: SmsLoginAttempt? = null
        var timedOutAttempt: SmsLoginAttempt? = null
        if (completedAttempt == activeSmsLoginAttempt && smsLoginPhase == SmsLoginPhase.SDK) {
            if (nowMs >= completedAttempt.deadlineMs) {
                activeSmsLoginAttempt = null
                smsLoginPhase = null
                timedOutAttempt = completedAttempt
            } else if (success) {
                smsLoginPhase = SmsLoginPhase.BACKEND
                startBackendAttempt = completedAttempt
            } else {
                activeSmsLoginAttempt = null
                smsLoginPhase = null
                failedAttempt = completedAttempt
            }
        }
        return SmsLoginSdkResolution(
            completedAttempt = completedAttempt,
            startBackendAttempt = startBackendAttempt,
            failedAttempt = failedAttempt,
            timedOutAttempt = timedOutAttempt
        )
    }

    @Synchronized
    fun onSmsLoginBackendSucceeded(attempt: SmsLoginAttempt): Boolean {
        if (activeSmsLoginAttempt != attempt || smsLoginPhase != SmsLoginPhase.BACKEND) {
            return false
        }
        smsLoginPhase = SmsLoginPhase.SUCCESS
        return true
    }

    @Synchronized
    fun isSmsLoginBackendActive(attempt: SmsLoginAttempt): Boolean {
        return activeSmsLoginAttempt == attempt && smsLoginPhase == SmsLoginPhase.BACKEND
    }

    @Synchronized
    fun consumeSmsLoginSuccess(attempt: SmsLoginAttempt): Boolean {
        if (activeSmsLoginAttempt != attempt || smsLoginPhase != SmsLoginPhase.SUCCESS) {
            return false
        }
        activeSmsLoginAttempt = null
        smsLoginPhase = null
        return true
    }

    @Synchronized
    fun onSmsLoginBackendFailed(attempt: SmsLoginAttempt): Boolean {
        if (activeSmsLoginAttempt != attempt || smsLoginPhase != SmsLoginPhase.BACKEND) {
            return false
        }
        activeSmsLoginAttempt = null
        smsLoginPhase = null
        return true
    }

    @Synchronized
    fun onSmsLoginTimedOut(attempt: SmsLoginAttempt, nowMs: Long): Boolean {
        if (
            activeSmsLoginAttempt != attempt ||
            smsLoginPhase == SmsLoginPhase.SUCCESS ||
            nowMs < attempt.deadlineMs
        ) {
            return false
        }
        activeSmsLoginAttempt = null
        smsLoginPhase = null
        return true
    }

    fun onOwnerCleared() {
        synchronized(sdkTransportLock) {
            activeOwnerGenerations.remove(ownerGeneration)
        }
    }

    private enum class SmsLoginPhase {
        SDK,
        BACKEND,
        SUCCESS
    }

    companion object {
        private val sdkTransportLock = Any()
        private var nextOwnerGeneration = 0L
        private var nextGeneration = 0L
        private val activeOwnerGenerations = mutableSetOf<Long>()
        private var smsCodeSdkTransportAttempt: SmsCodeAttempt? = null
        private var smsLoginSdkTransportAttempt: SmsLoginAttempt? = null

        /**
         * Drains a terminal SDK callback that arrived while its ViewModel owner
         * was already cleared. The owner check and lease clear are deliberately
         * atomic: a process-level handler must never consume a live owner's
         * callback.
         */
        internal fun drainSmsCodeSdkTransportIfOwnerInactive(): Boolean {
            return synchronized(sdkTransportLock) {
                val current = smsCodeSdkTransportAttempt ?: return@synchronized false
                if (current.ownerGeneration in activeOwnerGenerations) {
                    return@synchronized false
                }
                smsCodeSdkTransportAttempt = null
                true
            }
        }

        internal fun drainSmsLoginSdkTransportIfOwnerInactive(): Boolean {
            return synchronized(sdkTransportLock) {
                val current = smsLoginSdkTransportAttempt ?: return@synchronized false
                if (current.ownerGeneration in activeOwnerGenerations) {
                    return@synchronized false
                }
                smsLoginSdkTransportAttempt = null
                true
            }
        }
    }
}

internal enum class SmsStartBlockReason {
    ACTIVE_ATTEMPT,
    PREVIOUS_SDK_REQUEST_ENDING
}

internal data class SmsCodeAttempt(
    val generation: Long,
    val ownerGeneration: Long,
    val phoneNumber: String,
    val deadlineMs: Long
)

internal data class SmsCodeRequestStart(
    val attempt: SmsCodeAttempt,
    val sdkAttemptToStart: SmsCodeAttempt
)

internal data class SmsCodeStartDecision(
    val start: SmsCodeRequestStart? = null,
    val blockReason: SmsStartBlockReason? = null
)

internal data class SmsCodeSdkResolution(
    val completedAttempt: SmsCodeAttempt? = null,
    val appliesToActiveAttempt: Boolean = false,
    val success: Boolean = false,
    val failedAttempt: SmsCodeAttempt? = null
)

internal data class SmsLoginAttempt(
    val generation: Long,
    val ownerGeneration: Long,
    val phoneNumber: String,
    val smsCode: String,
    val registrationId: String,
    val deadlineMs: Long
)

internal data class SmsLoginStart(
    val attempt: SmsLoginAttempt,
    val sdkAttemptToStart: SmsLoginAttempt
)

internal data class SmsLoginStartDecision(
    val start: SmsLoginStart? = null,
    val blockReason: SmsStartBlockReason? = null
)

internal data class SmsLoginSdkResolution(
    val completedAttempt: SmsLoginAttempt? = null,
    val startBackendAttempt: SmsLoginAttempt? = null,
    val failedAttempt: SmsLoginAttempt? = null,
    val timedOutAttempt: SmsLoginAttempt? = null
)

internal class LoginUiEvent<out T>(private val value: T) {
    private var consumed = false

    @Synchronized
    fun consume(): T? {
        if (consumed) {
            return null
        }
        consumed = true
        return value
    }
}
