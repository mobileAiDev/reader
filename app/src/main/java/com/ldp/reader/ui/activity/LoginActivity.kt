package com.ldp.reader.ui.activity

import android.text.TextUtils
import android.os.Build
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import cn.smssdk.EventHandler
import cn.smssdk.SMSSDK
import com.blankj.utilcode.util.LogUtils
//import com.didichuxing.doraemonkit.kit.core.DoKitServiceEnum
import com.ldp.reader.R
import com.ldp.reader.RxBus
import com.ldp.reader.databinding.ActivityLoginBinding
import com.ldp.reader.event.BookSyncEvent
import com.ldp.reader.model.bean.DirectLoginResultBean
import com.ldp.reader.model.bean.LoginResultBean
import com.ldp.reader.model.bean.SmsLoginBean
import com.ldp.reader.presenter.LoginPresenter
import com.ldp.reader.presenter.contract.LoginContract
import com.ldp.reader.ui.base.BaseMVPActivity
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
                SMSSDK.getVerificationCode(
                    "86",
                    etUserPhone.getText().toString().trim(),
                    null,
                    null
                )
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
        window.statusBarColor = resources.getColor(R.color.home_bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
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
            SMSSDK.registerEventHandler(object : EventHandler() {
                override fun afterEvent(i: Int, i1: Int, o: Any) {
                    if (i == SMSSDK.EVENT_SUBMIT_VERIFICATION_CODE && i1 == SMSSDK.RESULT_COMPLETE) {
                        runOnUiThread {
                            mPresenter!!.smsLogin(
                                phoneNumber,
                                smsCode,
                                registrationId
                            )
                        }
                    }
                    super.afterEvent(i, i1, o)
                }
            })
        }

    }

    override fun finishLogin(loginResultBean: LoginResultBean) {
        if (200 == loginResultBean.code) {
            SharedPreUtils.getInstance().putString("loginType", "password")
            SharedPreUtils.getInstance().putString("token", loginResultBean.data)
            SharedPreUtils.getInstance().putString("userName", userName)
            ToastUtils.show("登录成功")
            finish()
        } else {
            ToastUtils.show(loginResultBean.message)
        }
    }

    override fun finishDirectLogin(loginResultBean: DirectLoginResultBean) {
        if (200 == loginResultBean.status) {
            SharedPreUtils.getInstance().putString("loginType", "telecom")
            SharedPreUtils.getInstance().putString("token", loginResultBean.res.mobileToken)
            SharedPreUtils.getInstance().putString("userName", loginResultBean.res.phone)
            ToastUtils.show("登录成功")
            RxBus.getInstance().post(BookSyncEvent())
            finish()
        } else {
            ToastUtils.show("登录失败" + loginResultBean.error)
        }
    }

    override fun finishSmsLogin(smsLoginBean: SmsLoginBean) {
        SharedPreUtils.getInstance().putString("loginType", "telecom")
        SharedPreUtils.getInstance().putString("token", smsLoginBean.smsCode)
        SharedPreUtils.getInstance().putString("userName", smsLoginBean.phoneNumber)
        ToastUtils.show("登录成功")
        RxBus.getInstance().post(BookSyncEvent())
        finish()
    }

    override fun showError() {
        ToastUtils.show("登录失败")
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

    companion object {
        private val TAG = LoginActivity::class.java.simpleName
    }
}
