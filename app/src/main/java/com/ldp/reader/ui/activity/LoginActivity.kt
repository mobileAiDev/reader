package com.ldp.reader.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.CountDownTimer
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import cn.smssdk.EventHandler
import cn.smssdk.SMSSDK
import com.blankj.utilcode.util.LogUtils
//import com.didichuxing.doraemonkit.kit.core.DoKitServiceEnum
import com.ldp.reader.R
import com.ldp.reader.databinding.ActivityLoginBinding
import com.ldp.reader.model.bean.DirectLoginResultBean
import com.ldp.reader.model.bean.LoginResultBean
import com.ldp.reader.model.bean.SmsLoginBean
import com.ldp.reader.presenter.LoginPresenter
import com.ldp.reader.presenter.contract.LoginContract
import com.ldp.reader.ui.base.BaseMVPActivity
import com.ldp.reader.ui.home.BookshelfSyncRequest
import com.ldp.reader.utils.SharedPreUtils
import com.ldp.reader.utils.ToastUtils
import com.mob.pushsdk.MobPush

/**
 * Created by ldp on 17-4-24.
 */
class LoginActivity : LoginContract.View,
    BaseMVPActivity<LoginActivity, LoginContract.Presenter<LoginActivity>, ActivityLoginBinding>() {

    private val userName: String? = null

    var phoneNumber = ""
    var smsCode = ""
    private var smsEventHandler: EventHandler? = null
    private var smsCodeCountDownTimer: CountDownTimer? = null

    override fun initClick() {
        super.initClick()
        binding?.apply {
            ivLoginBack.setOnClickListener {
                finish()
            }
            btnUserLogin.setOnClickListener {
                phoneNumber = etUserPhone.getText().toString().trim()
                smsCode = etSmsCodeInput.getText().toString().trim()
                SMSSDK.submitVerificationCode("86", phoneNumber, smsCode)
            }
            btnGetSmsCode.setOnClickListener {
                requestSmsCode()
            }
            btnDirectLogin.setOnClickListener {
                mPresenter!!.directLogin()
            }
            btnUserLogout.setOnClickListener {
                userLogout()
            }

        }


    }

    override fun bindPresenter(): LoginContract.Presenter<LoginActivity> {
        return LoginPresenter() as LoginContract.Presenter<LoginActivity>

    }

    override fun initWidget() {
        super.initWidget()
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
                mPresenter!!.preDirectLogin()
            } else {
                Log.d(TAG, "processLogic: token " + SharedPreUtils.getInstance().getString("token"))
                llUserNotLogin.setVisibility(View.GONE)
                llUserLogin.setVisibility(View.VISIBLE)
                tvUserName.setText(SharedPreUtils.getInstance().getString("userName"))
            }
            regId
            registerSmsEventHandler()
        }

    }

    override fun finishLogin(loginResultBean: LoginResultBean) {
        if (200 == loginResultBean.code) {
            finishSuccessfulLogin("password", loginResultBean.data, userName.orEmpty())
        } else {
            ToastUtils.show(loginResultBean.message)
        }
    }

    override fun finishDirectLogin(loginResultBean: DirectLoginResultBean) {
        if (200 == loginResultBean.status) {
            finishSuccessfulLogin(
                "telecom",
                loginResultBean.res!!.mobileToken,
                loginResultBean.res!!.phone
            )
        } else {
            ToastUtils.show("登录失败" + loginResultBean.error)
        }
    }

    override fun finishSmsLogin(smsLoginBean: SmsLoginBean) {
        val loginPhone = smsLoginBean.phoneNumber.takeUnless { it.isNullOrEmpty() } ?: phoneNumber
        val loginToken = smsLoginBean.smsCode.takeUnless { it.isNullOrEmpty() } ?: smsCode
        finishSuccessfulLogin("telecom", loginToken, loginPhone)
    }

    override fun showError() {
        ToastUtils.show("登录失败")
    }

    override fun showDirectLoginError() {
        ToastUtils.show("一键登录失败，请使用验证码登录")
    }

    override fun complete() {


    }


    private fun userLogout() {
        binding?.apply {
            llUserNotLogin.setVisibility(View.VISIBLE)
            llUserLogin.setVisibility(View.GONE)
            SharedPreUtils.getInstance().putString("token", "")
            SharedPreUtils.getInstance().putString("userName", "")
        }

    }

    private fun registerSmsEventHandler() {
        if (smsEventHandler != null) {
            return
        }
        smsEventHandler = object : EventHandler() {
            override fun afterEvent(i: Int, i1: Int, o: Any) {
                when (i) {
                    SMSSDK.EVENT_GET_VERIFICATION_CODE -> runOnUiThread {
                        if (i1 == SMSSDK.RESULT_COMPLETE) {
                            ToastUtils.show("验证码已发送")
                        } else {
                            stopSmsCodeCountdown()
                            ToastUtils.show("验证码发送失败")
                        }
                    }
                    SMSSDK.EVENT_SUBMIT_VERIFICATION_CODE -> runOnUiThread {
                        if (i1 == SMSSDK.RESULT_COMPLETE) {
                            mPresenter!!.smsLogin(
                                phoneNumber,
                                smsCode,
                                registrationId
                            )
                        } else {
                            showError()
                        }
                    }
                }
                super.afterEvent(i, i1, o)
            }
        }
        SMSSDK.registerEventHandler(smsEventHandler)
    }

    private fun requestSmsCode() {
        phoneNumber = binding.etUserPhone.text.toString().trim()
        if (!isValidPhoneNumber(phoneNumber)) {
            ToastUtils.show("请输入正确手机号")
            return
        }
        startSmsCodeCountdown()
        SMSSDK.getVerificationCode("86", phoneNumber, null, null)
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        return phone.length == 11 && phone.all { it.isDigit() }
    }

    private fun startSmsCodeCountdown() {
        smsCodeCountDownTimer?.cancel()
        binding.btnGetSmsCode.isEnabled = false
        binding.btnGetSmsCode.text = "60s"
        smsCodeCountDownTimer = object : CountDownTimer(60_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1_000L).coerceAtLeast(1L)
                binding.btnGetSmsCode.text = "${secondsLeft}s"
            }

            override fun onFinish() {
                smsCodeCountDownTimer = null
                binding.btnGetSmsCode.isEnabled = true
                binding.btnGetSmsCode.text = "获取验证码"
            }
        }.start()
    }

    private fun stopSmsCodeCountdown() {
        smsCodeCountDownTimer?.cancel()
        smsCodeCountDownTimer = null
        binding.btnGetSmsCode.isEnabled = true
        binding.btnGetSmsCode.text = "获取验证码"
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
        stopSmsCodeCountdown()
        smsEventHandler?.let { SMSSDK.unregisterEventHandler(it) }
        smsEventHandler = null
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
