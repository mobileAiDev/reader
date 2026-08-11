package com.ldp.reader.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.CountDownTimer
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import com.blankj.utilcode.util.LogUtils
//import com.didichuxing.doraemonkit.kit.core.DoKitServiceEnum
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityLoginBinding
import com.ldp.reader.model.bean.DirectLoginResultBean
import com.ldp.reader.model.bean.LoginResultBean
import com.ldp.reader.ui.base.BaseActivity
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils
import com.mob.pushsdk.MobPush

/**
 * Created by ldp on 17-4-24.
 */
class LoginActivity : BaseActivity<ActivityLoginBinding>() {

    private val userName: String? = null

    private var smsCodeCountDownTimer: CountDownTimer? = null
    private lateinit var viewModel: LoginViewModel

    override fun initClick() {
        super.initClick()
        binding?.apply {
            ivLoginBack.setOnClickListener {
                finish()
            }
            btnUserLogin.setOnClickListener {
                submitSmsLogin()
            }
            btnGetSmsCode.setOnClickListener {
                requestSmsCode()
            }
            btnDirectLogin.setOnClickListener {
                viewModel.directLogin()
            }
            btnUserLogout.setOnClickListener {
                userLogout()
            }

        }


    }

    override fun initWidget() {
        super.initWidget()
        viewModel = ViewModelProvider(this)[LoginViewModel::class.java]
        observeLoginState()
        applyLoginStatusBar()
        binding.loginRoot.setPadding(0, getStatusBarHeight(), 0, 0)
    }


    override fun processLogic() {
        super.processLogic()
        binding?.apply {
            if (TextUtils.isEmpty(SharedPreUtils.getInstance().getString("token"))) {
                Log.d(TAG, "processLogic: token空 ")
                llUserNotLogin.setVisibility(View.VISIBLE)
                llUserLogin.setVisibility(View.GONE)
                viewModel.preDirectLogin()
            } else {
                Log.d(TAG, "processLogic: token " + SharedPreUtils.getInstance().getString("token"))
                llUserNotLogin.setVisibility(View.GONE)
                llUserLogin.setVisibility(View.VISIBLE)
                tvUserName.setText(SharedPreUtils.getInstance().getString("userName"))
            }
            regId
        }

    }

    private fun observeLoginState() {
        viewModel.loginResults.observe(this) { loginResultBean -> finishLogin(loginResultBean) }
        viewModel.directLoginResults.observe(this) { loginResultBean ->
            finishDirectLogin(loginResultBean)
        }
        viewModel.smsCodeAttempt.observe(this) { attempt -> renderSmsCodeAttempt(attempt) }
        viewModel.smsLoginAttempt.observe(this) { attempt ->
            binding.btnUserLogin.isEnabled = attempt == null
        }
        viewModel.smsCodeFeedback.observe(this) { event ->
            when (event.consume()) {
                SmsCodeFeedback.SENT -> ToastUtils.show("验证码已发送")
                SmsCodeFeedback.FAILED -> ToastUtils.show("验证码发送失败")
                SmsCodeFeedback.PREVIOUS_REQUEST_ENDING -> {
                    ToastUtils.show("请求处理中，请稍后再试")
                }
                null -> Unit
            }
        }
        viewModel.smsLoginFeedback.observe(this) { event ->
            when (event.consume()) {
                SmsLoginFeedback.FAILED -> ToastUtils.show("登录失败")
                SmsLoginFeedback.TIMED_OUT -> ToastUtils.show("登录请求超时，请重试")
                SmsLoginFeedback.PREVIOUS_REQUEST_ENDING -> {
                    ToastUtils.show("请求处理中，请稍后再试")
                }
                null -> Unit
            }
        }
        viewModel.smsLoginResults.observe(this) { result -> finishSmsLogin(result) }
        viewModel.loginErrors.observe(this) { showError() }
        viewModel.directLoginErrors.observe(this) { showDirectLoginError() }
    }

    private fun finishLogin(loginResultBean: LoginResultBean) {
        if (200 == loginResultBean.code) {
            finishSuccessfulLogin("password", loginResultBean.data, userName.orEmpty())
        } else {
            ToastUtils.show(loginResultBean.message)
        }
    }

    private fun finishDirectLogin(loginResultBean: DirectLoginResultBean) {
        if (200 == loginResultBean.status) {
            finishSuccessfulLogin(
                "telecom",
                loginResultBean.res!!.mobileToken,
                loginResultBean.res!!.phone
            )
        } else {
            Log.w(
                TAG,
                "direct login rejected: status=${loginResultBean.status}, error=${loginResultBean.error}"
            )
            ToastUtils.show("一键登录失败，请使用验证码登录")
        }
    }

    private fun finishSmsLogin(result: SmsLoginResult) {
        val attempt = result.attempt
        if (!viewModel.consumeSmsLoginSuccess(attempt)) {
            return
        }
        val smsLoginBean = result.response
        val loginPhone = smsLoginBean.phoneNumber.takeUnless { it.isNullOrEmpty() }
            ?: attempt.phoneNumber
        val loginToken = smsLoginBean.smsCode.takeUnless { it.isNullOrEmpty() }
            ?: attempt.smsCode
        finishSuccessfulLogin("telecom", loginToken, loginPhone)
    }

    private fun showError() {
        ToastUtils.show("登录失败")
    }

    private fun showDirectLoginError() {
        ToastUtils.show("一键登录失败，请使用验证码登录")
    }


    private fun userLogout() {
        binding?.apply {
            llUserNotLogin.setVisibility(View.VISIBLE)
            llUserLogin.setVisibility(View.GONE)
            SharedPreUtils.getInstance().putString("token", "")
            SharedPreUtils.getInstance().putString("userName", "")
        }

    }

    private fun requestSmsCode() {
        val phoneNumber = binding.etUserPhone.text.toString().trim()
        if (!LoginInputValidator.isMainlandMobile(phoneNumber)) {
            ToastUtils.show("请输入正确手机号")
            return
        }
        viewModel.requestSmsCode(phoneNumber)
    }

    private fun submitSmsLogin() {
        val enteredPhoneNumber = binding.etUserPhone.text.toString().trim()
        val enteredSmsCode = binding.etSmsCodeInput.text.toString().trim()
        if (!LoginInputValidator.isMainlandMobile(enteredPhoneNumber)) {
            ToastUtils.show("请输入正确手机号")
            return
        }
        if (!LoginInputValidator.isSmsCode(enteredSmsCode)) {
            ToastUtils.show("请输入4至8位数字验证码")
            return
        }
        viewModel.submitSmsLogin(enteredPhoneNumber, enteredSmsCode, registrationId)
    }

    private fun renderSmsCodeAttempt(attempt: SmsCodeAttempt?) {
        smsCodeCountDownTimer?.cancel()
        smsCodeCountDownTimer = null
        if (attempt == null) {
            binding.btnGetSmsCode.isEnabled = true
            binding.btnGetSmsCode.text = "获取验证码"
            return
        }
        val remainingMs = attempt.deadlineMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) {
            viewModel.onSmsCodeCountdownFinished(attempt)
            return
        }
        binding.btnGetSmsCode.isEnabled = false
        binding.btnGetSmsCode.text = "${((remainingMs + 999L) / 1_000L)}s"
        smsCodeCountDownTimer = object : CountDownTimer(remainingMs, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = ((millisUntilFinished + 999L) / 1_000L).coerceAtLeast(1L)
                binding.btnGetSmsCode.text = "${secondsLeft}s"
            }

            override fun onFinish() {
                smsCodeCountDownTimer = null
                viewModel.onSmsCodeCountdownFinished(attempt)
            }
        }.start()
    }

    private fun finishSuccessfulLogin(loginType: String, token: String?, name: String?) {
        SharedPreUtils.getInstance().putString("loginType", loginType)
        SharedPreUtils.getInstance().putString("token", token.orEmpty())
        SharedPreUtils.getInstance().putString("userName", name.orEmpty())
        ToastUtils.show("登录成功")
        setResult(Activity.RESULT_OK, BookshelfSyncRequest.resultIntent())
        finish()
    }

    var registrationId = ""
    private val regId: Unit
        private get() {
            LogUtils.d(TAG, "preDirectLogin: registrationId")
            registrationId = SharedPreUtils.getInstance().getString("registrationId")
            LogUtils.d(TAG, "onCallback: registrationId  $registrationId")
            if (TextUtils.isEmpty(registrationId)) {
                MobPush.getRegistrationId { s ->
                    LogUtils.e(TAG, "onCallback: registrationId  $s")
                    registrationId = s
                    SharedPreUtils.getInstance().putString("registrationId", registrationId)
                }
            }
        }

    override fun getViewBinding(): ActivityLoginBinding {
        return ActivityLoginBinding.inflate(layoutInflater)
    }

    override fun onDestroy() {
        smsCodeCountDownTimer?.cancel()
        smsCodeCountDownTimer = null
        super.onDestroy()
    }

    private fun applyLoginStatusBar() {
        window.statusBarColor = Color.TRANSPARENT
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    companion object {
        private val TAG = LoginActivity::class.java.simpleName
        fun syncIntent(context: Context): Intent {
            return Intent(context, LoginActivity::class.java)
        }

        fun shouldRequestBookShelfSync(data: Intent?): Boolean {
            return BookshelfSyncRequest.isRequested(data)
        }
    }
}
