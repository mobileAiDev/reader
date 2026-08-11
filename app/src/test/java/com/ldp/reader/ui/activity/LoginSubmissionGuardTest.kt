package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSubmissionGuardTest {

    @Test
    fun `view model owned login attempt survives recreation and success is consumed once`() {
        val guardRetainedByViewModel = LoginSubmissionGuard()
        val first = guardRetainedByViewModel.tryBeginSmsLogin(
            phoneNumber = "13800138000",
            smsCode = "123456",
            registrationId = "push-a",
            nowMs = 1_000L,
            timeoutMs = 60_000L
        ).start!!

        val duplicate = guardRetainedByViewModel.tryBeginSmsLogin(
            phoneNumber = "13800138000",
            smsCode = "123456",
            registrationId = "push-b",
            nowMs = 2_000L,
            timeoutMs = 60_000L
        )
        assertNull(duplicate.start)
        assertEquals(SmsStartBlockReason.ACTIVE_ATTEMPT, duplicate.blockReason)

        val sdkResult = guardRetainedByViewModel.onSmsLoginSdkResult(success = true, nowMs = 2_000L)
        assertSame(first.attempt, sdkResult.startBackendAttempt)
        assertTrue(guardRetainedByViewModel.onSmsLoginBackendSucceeded(first.attempt))
        assertNull(
            guardRetainedByViewModel.tryBeginSmsLogin(
                phoneNumber = "13900139000",
                smsCode = "654321",
                registrationId = "push-c",
                nowMs = 3_000L,
                timeoutMs = 60_000L
            ).start
        )
        assertTrue(guardRetainedByViewModel.consumeSmsLoginSuccess(first.attempt))
        assertFalse(guardRetainedByViewModel.consumeSmsLoginSuccess(first.attempt))
    }

    @Test
    fun `timed out sdk lease blocks honest retry across a new view model until old callback is discarded`() {
        val oldViewModelGuard = LoginSubmissionGuard()
        val first = oldViewModelGuard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-a",
            nowMs = 0L,
            timeoutMs = 60_000L
        ).start!!
        assertSame(first.attempt, first.sdkAttemptToStart)
        assertTrue(oldViewModelGuard.onSmsLoginTimedOut(first.attempt, nowMs = 60_000L))

        val newViewModelGuard = LoginSubmissionGuard()
        val blocked = newViewModelGuard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-b",
            nowMs = 61_000L,
            timeoutMs = 60_000L
        )
        assertNull(blocked.start)
        assertEquals(SmsStartBlockReason.PREVIOUS_SDK_REQUEST_ENDING, blocked.blockReason)

        oldViewModelGuard.onOwnerCleared()
        var oldCallback: SmsLoginSdkResolution? = null
        val privacyAlertWorker = Thread {
            oldCallback = newViewModelGuard.onSmsLoginSdkResult(success = true, nowMs = 61_000L)
        }
        privacyAlertWorker.start()
        privacyAlertWorker.join()
        assertNull(oldCallback!!.startBackendAttempt)

        val retry = newViewModelGuard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-b",
            nowMs = 62_000L,
            timeoutMs = 60_000L
        ).start!!
        assertSame(retry.attempt, retry.sdkAttemptToStart)
        assertSame(
            retry.attempt,
            newViewModelGuard.onSmsLoginSdkResult(true, nowMs = 62_000L).startBackendAttempt
        )
        assertTrue(newViewModelGuard.onSmsLoginBackendFailed(retry.attempt))
    }

    @Test
    fun `process broker drains login callback with no replacement view model`() {
        val oldGuard = LoginSubmissionGuard()
        oldGuard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-a",
            nowMs = 0L,
            timeoutMs = 60_000L
        ).start!!

        assertFalse(LoginSubmissionGuard.drainSmsLoginSdkTransportIfOwnerInactive())
        oldGuard.onOwnerCleared()
        assertTrue(LoginSubmissionGuard.drainSmsLoginSdkTransportIfOwnerInactive())
        assertFalse(LoginSubmissionGuard.drainSmsLoginSdkTransportIfOwnerInactive())

        val reopenedGuard = LoginSubmissionGuard()
        val retry = reopenedGuard.tryBeginSmsLogin(
            "13900139000",
            "654321",
            "push-b",
            nowMs = 1_000L,
            timeoutMs = 60_000L
        ).start
        assertTrue(retry != null)
        reopenedGuard.onSmsLoginSdkResult(success = false, nowMs = 1_000L)
        reopenedGuard.onOwnerCleared()
    }

    @Test
    fun `another live view model handler cannot steal the sdk callback from its owner`() {
        val ownerGuard = LoginSubmissionGuard()
        val ownerAttempt = ownerGuard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-a",
            nowMs = 0L,
            timeoutMs = 60_000L
        ).start!!.attempt
        val otherLiveGuard = LoginSubmissionGuard()

        assertNull(
            otherLiveGuard.onSmsLoginSdkResult(success = true, nowMs = 1_000L).completedAttempt
        )
        assertSame(
            ownerAttempt,
            ownerGuard.onSmsLoginSdkResult(success = true, nowMs = 1_000L).startBackendAttempt
        )
        assertTrue(ownerGuard.onSmsLoginBackendFailed(ownerAttempt))
    }

    @Test
    fun `old sms code callback cannot cancel retry cooldown or create a fake queued request`() {
        val oldGuard = LoginSubmissionGuard()
        val first = oldGuard.tryBeginSmsCodeRequest(
            phoneNumber = "13800138000",
            nowMs = 0L,
            cooldownMs = 60_000L
        ).start!!
        assertTrue(oldGuard.onSmsCodeCooldownElapsed(first.attempt, nowMs = 60_000L))

        val newGuard = LoginSubmissionGuard()
        val blocked = newGuard.tryBeginSmsCodeRequest(
            phoneNumber = "13900139000",
            nowMs = 60_000L,
            cooldownMs = 60_000L
        )
        assertNull(blocked.start)
        assertEquals(SmsStartBlockReason.PREVIOUS_SDK_REQUEST_ENDING, blocked.blockReason)

        oldGuard.onOwnerCleared()
        val oldCallback = newGuard.onSmsCodeSdkResult(success = false)
        assertFalse(oldCallback.appliesToActiveAttempt)

        val second = newGuard.tryBeginSmsCodeRequest(
            phoneNumber = "13900139000",
            nowMs = 61_000L,
            cooldownMs = 60_000L
        ).start!!
        assertFalse(newGuard.onSmsCodeCooldownElapsed(second.attempt, nowMs = 120_999L))
        assertEquals(
            SmsStartBlockReason.ACTIVE_ATTEMPT,
            newGuard.tryBeginSmsCodeRequest(
                phoneNumber = "13700137000",
                nowMs = 120_999L,
                cooldownMs = 60_000L
            ).blockReason
        )
        assertTrue(newGuard.onSmsCodeCooldownElapsed(second.attempt, nowMs = 121_000L))
        newGuard.onSmsCodeSdkResult(success = true)
    }

    @Test
    fun `process broker drains code callback with no replacement view model`() {
        val oldGuard = LoginSubmissionGuard()
        oldGuard.tryBeginSmsCodeRequest(
            phoneNumber = "13800138000",
            nowMs = 0L,
            cooldownMs = 60_000L
        ).start!!

        assertFalse(LoginSubmissionGuard.drainSmsCodeSdkTransportIfOwnerInactive())
        oldGuard.onOwnerCleared()
        assertTrue(LoginSubmissionGuard.drainSmsCodeSdkTransportIfOwnerInactive())
        assertFalse(LoginSubmissionGuard.drainSmsCodeSdkTransportIfOwnerInactive())

        val reopenedGuard = LoginSubmissionGuard()
        val retry = reopenedGuard.tryBeginSmsCodeRequest(
            phoneNumber = "13900139000",
            nowMs = 1_000L,
            cooldownMs = 60_000L
        ).start
        assertTrue(retry != null)
        reopenedGuard.onSmsCodeSdkResult(success = false)
        reopenedGuard.onOwnerCleared()
    }

    @Test
    fun `current sms code failure releases cooldown while stale failure cannot release a new attempt`() {
        val guard = LoginSubmissionGuard()
        val first = guard.tryBeginSmsCodeRequest(
            phoneNumber = "13800138000",
            nowMs = 0L,
            cooldownMs = 60_000L
        ).start!!

        val failure = guard.onSmsCodeSdkLaunchFailed(first.attempt)
        assertSame(first.attempt, failure.failedAttempt)

        val retry = guard.tryBeginSmsCodeRequest(
            phoneNumber = "13800138000",
            nowMs = 1_000L,
            cooldownMs = 60_000L
        ).start
        assertTrue(retry != null)
        guard.onSmsCodeSdkResult(success = true)
        guard.onSmsCodeCooldownElapsed(retry!!.attempt, nowMs = 61_000L)
    }

    @Test
    fun `login timeout covers sdk and backend without resetting the deadline`() {
        val guard = LoginSubmissionGuard()
        val first = guard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-a",
            nowMs = 10_000L,
            timeoutMs = 60_000L
        ).start!!

        val sdkResult = guard.onSmsLoginSdkResult(success = true, nowMs = 20_000L)
        assertSame(first.attempt, sdkResult.startBackendAttempt)
        assertEquals(70_000L, first.attempt.deadlineMs)
        assertFalse(guard.onSmsLoginTimedOut(first.attempt, nowMs = 69_999L))
        assertTrue(guard.onSmsLoginTimedOut(first.attempt, nowMs = 70_000L))
        assertFalse(guard.onSmsLoginBackendSucceeded(first.attempt))

        val retry = guard.tryBeginSmsLogin(
            "13900139000",
            "654321",
            "push-b",
            nowMs = 71_000L,
            timeoutMs = 60_000L
        ).start
        assertTrue(retry != null)
        guard.onSmsLoginSdkResult(success = false, nowMs = 71_000L)
    }

    @Test
    fun `sdk result delivered at the deadline is timeout and never starts backend`() {
        val guard = LoginSubmissionGuard()
        val attempt = guard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-a",
            nowMs = 0L,
            timeoutMs = 60_000L
        ).start!!.attempt

        val lateResult = guard.onSmsLoginSdkResult(success = true, nowMs = 60_000L)

        assertSame(attempt, lateResult.timedOutAttempt)
        assertNull(lateResult.startBackendAttempt)
        assertFalse(guard.onSmsLoginBackendSucceeded(attempt))
    }

    @Test
    fun `attempt credentials remain immutable across generations`() {
        val guard = LoginSubmissionGuard()
        val first = guard.tryBeginSmsLogin(
            "13800138000",
            "123456",
            "push-a",
            nowMs = 0L,
            timeoutMs = 60_000L
        ).start!!
        guard.onSmsLoginSdkResult(success = true, nowMs = 1_000L)
        assertTrue(guard.onSmsLoginBackendFailed(first.attempt))
        val second = guard.tryBeginSmsLogin(
            "13900139000",
            "654321",
            "push-b",
            nowMs = 61_000L,
            timeoutMs = 60_000L
        ).start!!

        assertEquals("13800138000", first.attempt.phoneNumber)
        assertEquals("123456", first.attempt.smsCode)
        assertEquals("push-a", first.attempt.registrationId)
        assertEquals("13900139000", second.attempt.phoneNumber)
        assertEquals("654321", second.attempt.smsCode)
        assertEquals("push-b", second.attempt.registrationId)
        guard.onSmsLoginSdkResult(success = false, nowMs = 62_000L)
    }
}
