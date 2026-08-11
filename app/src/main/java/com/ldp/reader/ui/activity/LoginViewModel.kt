package com.ldp.reader.ui.activity

import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.smssdk.EventHandler
import cn.smssdk.SMSSDK
import com.ldp.reader.model.bean.DirectLoginResultBean
import com.ldp.reader.model.bean.LoginResultBean
import com.ldp.reader.model.bean.SmsLoginBean
import com.ldp.reader.model.remote.RemoteRepository
import com.ldp.reader.utils.SharedPreUtils
import com.mob.pushsdk.MobPush
import com.mob.pushsdk.MobPushCallback
import com.mob.secverify.OperationCallback
import com.mob.secverify.SecVerify
import com.mob.secverify.VerifyCallback
import com.mob.secverify.common.exception.VerifyException
import com.mob.secverify.datatype.VerifyResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {
    private val _loginResults = MutableLiveData<LoginResultBean>()
    private val _directLoginResults = MutableLiveData<DirectLoginResultBean>()
    private val _smsCodeAttempt = MutableLiveData<SmsCodeAttempt?>()
    private val _smsLoginAttempt = MutableLiveData<SmsLoginAttempt?>()
    private val _smsCodeFeedback = MutableLiveData<LoginUiEvent<SmsCodeFeedback>>()
    private val _smsLoginFeedback = MutableLiveData<LoginUiEvent<SmsLoginFeedback>>()
    private val _smsLoginResults = MutableLiveData<SmsLoginResult>()
    private val _loginErrors = MutableLiveData<Int>()
    private val _directLoginErrors = MutableLiveData<Int>()
    private val submissionGuard = LoginSubmissionGuard()
    private var smsCodeCooldownJob: Job? = null
    private var smsLoginTimeoutJob: Job? = null
    private var smsLoginTimeoutAttempt: SmsLoginAttempt? = null
    private var loginErrorVersion = 0
    private var directLoginErrorVersion = 0
    private var registrationId: String? = null

    private val smsEventHandler = object : EventHandler() {
        override fun afterEvent(event: Int, result: Int, data: Any) {
            when (event) {
                SMSSDK.EVENT_GET_VERIFICATION_CODE -> {
                    handleSmsCodeSdkResult(result == SMSSDK.RESULT_COMPLETE)
                }
                SMSSDK.EVENT_SUBMIT_VERIFICATION_CODE -> {
                    handleSmsLoginSdkResult(result == SMSSDK.RESULT_COMPLETE)
                }
            }
            super.afterEvent(event, result, data)
        }
    }

    val loginResults: LiveData<LoginResultBean> = _loginResults
    val directLoginResults: LiveData<DirectLoginResultBean> = _directLoginResults
    internal val smsCodeAttempt: LiveData<SmsCodeAttempt?> = _smsCodeAttempt
    internal val smsLoginAttempt: LiveData<SmsLoginAttempt?> = _smsLoginAttempt
    internal val smsCodeFeedback: LiveData<LoginUiEvent<SmsCodeFeedback>> = _smsCodeFeedback
    internal val smsLoginFeedback: LiveData<LoginUiEvent<SmsLoginFeedback>> = _smsLoginFeedback
    internal val smsLoginResults: LiveData<SmsLoginResult> = _smsLoginResults
    val loginErrors: LiveData<Int> = _loginErrors
    val directLoginErrors: LiveData<Int> = _directLoginErrors

    init {
        SmsSdkProcessDrainBroker.ensureRegistered()
        SMSSDK.registerEventHandler(smsEventHandler)
    }

    fun userLogin(userName: String?, passWord: String?) {
        viewModelScope.launch {
            try {
                _loginResults.value = RemoteRepository.getInstance().userLogin(userName, passWord)
            } catch (e: Throwable) {
                _loginErrors.value = ++loginErrorVersion
            }
        }
    }

    fun preDirectLogin() {
        Log.d(TAG, "preDirectLogin: registrationId")
        registrationId = SharedPreUtils.getInstance().getString("registrationId")
        Log.d(TAG, "onCallback: registrationId  $registrationId")
        if (TextUtils.isEmpty(registrationId)) {
            MobPush.getRegistrationId(object : MobPushCallback<String> {
                override fun onCallback(s: String?) {
                    Log.d(TAG, "onCallback: registrationId  $s")
                    registrationId = s
                    SharedPreUtils.getInstance().putString("registrationId", registrationId)
                }
            })
        }
        SecVerify.preVerify(object : OperationCallback<Void>() {
            override fun onComplete(data: Void?) {
                Log.d(TAG, "onComplete: $data")
            }

            override fun onFailure(e: VerifyException?) {
                Log.d(TAG, "onFailure: $e")
            }
        })
    }

    internal fun requestSmsCode(phoneNumber: String): Boolean {
        val decision = submissionGuard.tryBeginSmsCodeRequest(
            phoneNumber = phoneNumber,
            nowMs = SystemClock.elapsedRealtime(),
            cooldownMs = SMS_CODE_COOLDOWN_MS
        )
        val start = decision.start
        if (start == null) {
            if (decision.blockReason == SmsStartBlockReason.PREVIOUS_SDK_REQUEST_ENDING) {
                _smsCodeFeedback.value = LoginUiEvent(SmsCodeFeedback.PREVIOUS_REQUEST_ENDING)
            }
            return false
        }
        _smsCodeAttempt.value = start.attempt
        scheduleSmsCodeCooldown(start.attempt)
        launchSmsCodeSdk(start.sdkAttemptToStart)
        return true
    }

    internal fun onSmsCodeCountdownFinished(attempt: SmsCodeAttempt) {
        if (!submissionGuard.onSmsCodeCooldownElapsed(attempt, SystemClock.elapsedRealtime())) {
            return
        }
        smsCodeCooldownJob?.cancel()
        smsCodeCooldownJob = null
        _smsCodeAttempt.value = null
    }

    private fun scheduleSmsCodeCooldown(attempt: SmsCodeAttempt) {
        smsCodeCooldownJob?.cancel()
        smsCodeCooldownJob = viewModelScope.launch {
            waitUntilDeadline(attempt.deadlineMs)
            if (submissionGuard.onSmsCodeCooldownElapsed(attempt, SystemClock.elapsedRealtime())) {
                smsCodeCooldownJob = null
                _smsCodeAttempt.value = null
            }
        }
    }

    private fun launchSmsCodeSdk(attempt: SmsCodeAttempt) {
        try {
            SMSSDK.getVerificationCode("86", attempt.phoneNumber, null, null)
        } catch (_: RuntimeException) {
            applySmsCodeSdkResolution(submissionGuard.onSmsCodeSdkLaunchFailed(attempt))
        }
    }

    private fun handleSmsCodeSdkResult(success: Boolean) {
        val resolution = submissionGuard.onSmsCodeSdkResult(success)
        viewModelScope.launch {
            applySmsCodeSdkResolution(resolution)
        }
    }

    private fun applySmsCodeSdkResolution(resolution: SmsCodeSdkResolution) {
        resolution.failedAttempt?.let {
            smsCodeCooldownJob?.cancel()
            smsCodeCooldownJob = null
            _smsCodeAttempt.value = null
        }
        if (resolution.appliesToActiveAttempt) {
            val feedback = if (resolution.success) SmsCodeFeedback.SENT else SmsCodeFeedback.FAILED
            _smsCodeFeedback.value = LoginUiEvent(feedback)
        }
    }

    internal fun submitSmsLogin(
        phoneNumber: String,
        smsCode: String,
        registrationId: String
    ): Boolean {
        val decision = submissionGuard.tryBeginSmsLogin(
            phoneNumber = phoneNumber,
            smsCode = smsCode,
            registrationId = registrationId,
            nowMs = SystemClock.elapsedRealtime(),
            timeoutMs = SMS_LOGIN_TIMEOUT_MS
        )
        val start = decision.start
        if (start == null) {
            if (decision.blockReason == SmsStartBlockReason.PREVIOUS_SDK_REQUEST_ENDING) {
                _smsLoginFeedback.value = LoginUiEvent(SmsLoginFeedback.PREVIOUS_REQUEST_ENDING)
            }
            return false
        }
        _smsLoginAttempt.value = start.attempt
        scheduleSmsLoginTimeout(start.attempt)
        launchSmsLoginSdk(start.sdkAttemptToStart)
        return true
    }

    private fun scheduleSmsLoginTimeout(attempt: SmsLoginAttempt) {
        smsLoginTimeoutJob?.cancel()
        smsLoginTimeoutAttempt = attempt
        smsLoginTimeoutJob = viewModelScope.launch {
            waitUntilDeadline(attempt.deadlineMs)
            expireSmsLoginIfNeeded(attempt, SystemClock.elapsedRealtime())
        }
    }

    private fun cancelSmsLoginTimeout(attempt: SmsLoginAttempt) {
        if (smsLoginTimeoutAttempt != attempt) {
            return
        }
        smsLoginTimeoutJob?.cancel()
        smsLoginTimeoutJob = null
        smsLoginTimeoutAttempt = null
    }

    private fun launchSmsLoginSdk(attempt: SmsLoginAttempt) {
        try {
            SMSSDK.submitVerificationCode("86", attempt.phoneNumber, attempt.smsCode)
        } catch (_: RuntimeException) {
            applySmsLoginSdkResolution(
                submissionGuard.onSmsLoginSdkLaunchFailed(
                    attempt,
                    SystemClock.elapsedRealtime()
                )
            )
        }
    }

    private fun handleSmsLoginSdkResult(success: Boolean) {
        val resolution = submissionGuard.onSmsLoginSdkResult(
            success,
            SystemClock.elapsedRealtime()
        )
        viewModelScope.launch {
            applySmsLoginSdkResolution(resolution)
        }
    }

    private fun applySmsLoginSdkResolution(resolution: SmsLoginSdkResolution) {
        resolution.timedOutAttempt?.let { attempt ->
            cancelSmsLoginTimeout(attempt)
            _smsLoginAttempt.value = null
            _smsLoginFeedback.value = LoginUiEvent(SmsLoginFeedback.TIMED_OUT)
        }
        resolution.failedAttempt?.let { attempt ->
            cancelSmsLoginTimeout(attempt)
            _smsLoginAttempt.value = null
            _smsLoginFeedback.value = LoginUiEvent(SmsLoginFeedback.FAILED)
        }
        resolution.startBackendAttempt?.let { attempt ->
            if (
                !expireSmsLoginIfNeeded(attempt, SystemClock.elapsedRealtime()) &&
                submissionGuard.isSmsLoginBackendActive(attempt)
            ) {
                startSmsLoginBackend(attempt)
            }
        }
    }

    private fun startSmsLoginBackend(attempt: SmsLoginAttempt) {
        viewModelScope.launch {
            try {
                val response = RemoteRepository.getInstance().smsLogin(
                    attempt.phoneNumber,
                    attempt.smsCode,
                    attempt.registrationId
                )
                val nowMs = SystemClock.elapsedRealtime()
                if (expireSmsLoginIfNeeded(attempt, nowMs)) {
                    return@launch
                }
                if (submissionGuard.onSmsLoginBackendSucceeded(attempt)) {
                    cancelSmsLoginTimeout(attempt)
                    _smsLoginResults.value = SmsLoginResult(attempt, response)
                }
            } catch (throwable: Throwable) {
                throwable.printStackTrace()
                val nowMs = SystemClock.elapsedRealtime()
                if (expireSmsLoginIfNeeded(attempt, nowMs)) {
                    return@launch
                }
                if (submissionGuard.onSmsLoginBackendFailed(attempt)) {
                    cancelSmsLoginTimeout(attempt)
                    _smsLoginAttempt.value = null
                    _smsLoginFeedback.value = LoginUiEvent(SmsLoginFeedback.FAILED)
                }
            }
        }
    }

    private fun expireSmsLoginIfNeeded(attempt: SmsLoginAttempt, nowMs: Long): Boolean {
        if (!submissionGuard.onSmsLoginTimedOut(attempt, nowMs)) {
            return false
        }
        cancelSmsLoginTimeout(attempt)
        _smsLoginAttempt.value = null
        _smsLoginFeedback.value = LoginUiEvent(SmsLoginFeedback.TIMED_OUT)
        return true
    }

    private suspend fun waitUntilDeadline(deadlineMs: Long) {
        while (true) {
            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                return
            }
            delay(remainingMs)
        }
    }

    internal fun consumeSmsLoginSuccess(attempt: SmsLoginAttempt): Boolean {
        if (!submissionGuard.consumeSmsLoginSuccess(attempt)) {
            return false
        }
        _smsLoginAttempt.value = null
        return true
    }

    fun directLogin() {
        Log.d(TAG, "directLogin: ")
        registrationId = SharedPreUtils.getInstance().getString("registrationId")
        SecVerify.verify(object : VerifyCallback() {
            override fun onOtherLogin() {
                _directLoginErrors.postValue(++directLoginErrorVersion)
            }

            override fun onUserCanceled() {
            }

            override fun onComplete(data: VerifyResult?) {
                Log.e(TAG, "onComplete: ")
                Log.e(TAG, "onComplete: $data")
                Log.e(TAG, "onComplete: " + data!!.token)
                Log.e(TAG, "onComplete: registrationId $registrationId")

                viewModelScope.launch {
                    try {
                        _directLoginResults.value = RemoteRepository.getInstance().userDirectLogin(data, registrationId)
                    } catch (throwable: Throwable) {
                        Log.e(TAG, "accept: " + throwable.message + throwable.cause)
                        _loginErrors.value = ++loginErrorVersion
                    }
                }
            }

            override fun onFailure(e: VerifyException?) {
                Log.d(TAG, "onFailure: " + e.toString())
                _directLoginErrors.postValue(++directLoginErrorVersion)
            }
        })
    }

    override fun onCleared() {
        submissionGuard.onOwnerCleared()
        SMSSDK.unregisterEventHandler(smsEventHandler)
        // SmsSdkProcessDrainBroker intentionally remains registered for the
        // process lifetime so callbacks cannot be lost between login screens.
        super.onCleared()
    }

    companion object {
        private val TAG = LoginViewModel::class.java.simpleName
        private const val SMS_CODE_COOLDOWN_MS = 60_000L
        private const val SMS_LOGIN_TIMEOUT_MS = 60_000L
    }
}

internal data class SmsLoginResult(
    val attempt: SmsLoginAttempt,
    val response: SmsLoginBean
)

internal enum class SmsCodeFeedback {
    SENT,
    FAILED,
    PREVIOUS_REQUEST_ENDING
}

internal enum class SmsLoginFeedback {
    FAILED,
    TIMED_OUT,
    PREVIOUS_REQUEST_ENDING
}

/**
 * Process-lifetime SMSSDK callback sink. It owns no Activity or ViewModel and
 * only clears transport leases whose ViewModel owner has already been cleared.
 * Live owners keep exclusive responsibility for applying their callback.
 */
private object SmsSdkProcessDrainBroker {
    private val registrationLock = Any()

    @Volatile
    private var registered = false

    private val eventHandler = object : EventHandler() {
        override fun afterEvent(event: Int, result: Int, data: Any) {
            when (event) {
                SMSSDK.EVENT_GET_VERIFICATION_CODE -> {
                    LoginSubmissionGuard.drainSmsCodeSdkTransportIfOwnerInactive()
                }
                SMSSDK.EVENT_SUBMIT_VERIFICATION_CODE -> {
                    LoginSubmissionGuard.drainSmsLoginSdkTransportIfOwnerInactive()
                }
            }
            super.afterEvent(event, result, data)
        }
    }

    fun ensureRegistered() {
        if (registered) {
            return
        }
        synchronized(registrationLock) {
            if (registered) {
                return
            }
            SMSSDK.registerEventHandler(eventHandler)
            registered = true
        }
    }
}
