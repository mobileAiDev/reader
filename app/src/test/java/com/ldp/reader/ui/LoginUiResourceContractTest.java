package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.R;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class LoginUiResourceContractTest {

    @Test
    public void loginLayoutKeepsPolishedSurfaceContract() {
        assertNotEquals(0, R.layout.activity_login);
        assertNotEquals(0, R.id.login_root);
        assertNotEquals(0, R.id.login_scroll);
        assertNotEquals(0, R.id.login_header_title);
        assertNotEquals(0, R.id.login_header_subtitle);
        assertNotEquals(0, R.id.login_form_title);
        assertNotEquals(0, R.id.login_form_subtitle);
        assertNotEquals(0, R.id.login_phone_row);
        assertNotEquals(0, R.id.login_code_row);
        assertNotEquals(0, R.id.login_state_title);
        assertNotEquals(0, R.id.login_state_subtitle);
        assertNotEquals(0, R.id.iv_login_back);
        assertNotEquals(0, R.id.et_user_phone);
        assertNotEquals(0, R.id.et_sms_code_input);
        assertNotEquals(0, R.id.btn_get_sms_code);
        assertNotEquals(0, R.id.btn_user_login);
        assertNotEquals(0, R.id.btn_direct_login);
        assertNotEquals(0, R.id.btn_user_logout);
        assertNotEquals(0, R.drawable.ic_login_phone_24);
        assertNotEquals(0, R.drawable.ic_login_code_24);
        assertNotEquals(0, R.drawable.bg_login_state_avatar);
    }

    @Test
    public void smsLoginKeepsLifecycleAndSuccessfulSyncContract() throws IOException {
        String loginActivity = readFile("src/main/java/com/ldp/reader/ui/activity/LoginActivity.kt");

        assertTrue(loginActivity.contains("private var smsEventHandler: EventHandler? = null"));
        assertTrue(loginActivity.contains("private var smsCodeCountDownTimer: CountDownTimer? = null"));
        assertTrue(loginActivity.contains("registerSmsEventHandler()"));
        assertTrue(loginActivity.contains("SMSSDK.unregisterEventHandler(it)"));
        assertTrue(loginActivity.contains("startSmsCodeCountdown()"));
        assertTrue(loginActivity.contains("stopSmsCodeCountdown()"));
        assertTrue(loginActivity.contains("SMSSDK.EVENT_GET_VERIFICATION_CODE"));
        assertTrue(loginActivity.contains("finishSuccessfulLogin(\"telecom\", loginToken, loginPhone)"));
        assertTrue(loginActivity.contains("token.orEmpty()"));
        assertTrue(loginActivity.contains("name.orEmpty()"));
        assertTrue(loginActivity.contains("RxBus.getInstance().post(BookSyncEvent())"));
        assertFalse(loginActivity.contains("intent.getBooleanExtra"));

        String loginPresenter = readFile("src/main/java/com/ldp/reader/presenter/LoginPresenter.java");
        int preLoginStart = loginPresenter.indexOf("public void preDirectLogin()");
        int smsLoginStart = loginPresenter.indexOf("public void smsLogin", preLoginStart);
        int autoDirectLogin = loginPresenter.indexOf("directLogin();", preLoginStart);
        assertFalse(autoDirectLogin >= 0 && autoDirectLogin < smsLoginStart);
        assertTrue(loginPresenter.contains("if (mView == null)"));
    }

    @Test
    public void loginPageUsesImmersiveStatusAndDirectLoginFailurePrompt() throws IOException {
        String loginLayout = readFile("src/main/res/layout/activity_login.xml");
        assertFalse(loginLayout.contains("android:fitsSystemWindows=\"true\""));

        String loginActivity = readFile("src/main/java/com/ldp/reader/ui/activity/LoginActivity.kt");
        assertTrue(loginActivity.contains("applyLoginStatusBar()"));
        assertTrue(loginActivity.contains("Color.TRANSPARENT"));
        assertTrue(loginActivity.contains("View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN"));
        assertTrue(loginActivity.contains("binding.loginRoot.setPadding(0, getStatusBarHeight(), 0, 0)"));
        assertTrue(loginActivity.contains("override fun showDirectLoginError()"));
        assertTrue(loginActivity.contains("一键登录失败，请使用验证码登录"));

        String loginContract = readFile("src/main/java/com/ldp/reader/presenter/contract/LoginContract.java");
        assertTrue(loginContract.contains("void showDirectLoginError();"));

        String loginPresenter = readFile("src/main/java/com/ldp/reader/presenter/LoginPresenter.java");
        assertTrue(loginPresenter.contains("mView.showDirectLoginError();"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()));
    }
}
