package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.R;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoginUiResourceContractTest {

    @Test
    public void loginCloseActionHasLocalizedAccessibleName() throws IOException {
        String loginLayout = readFile("src/main/res/layout/activity_login.xml");
        String closeAction = viewTag(loginLayout, "iv_login_back");
        String simplifiedStrings = readFile("src/main/res/values/strings.xml");
        String traditionalStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertTrue(closeAction.contains("android:contentDescription=\"@string/login_close\""));
        assertTrue(simplifiedStrings.contains("<string name=\"login_close\">关闭登录页面</string>"));
        assertTrue(traditionalStrings.contains("<string name=\"login_close\">關閉登入頁面</string>"));
    }

    @Test
    public void loginActionsKeepFortyEightDpTouchTargets() throws IOException {
        String loginLayout = readFile("src/main/res/layout/activity_login.xml");
        String closeAction = viewTag(loginLayout, "iv_login_back");
        String smsCodeAction = viewTag(loginLayout, "btn_get_sms_code");

        assertTrue(closeAction.contains("android:layout_width=\"48dp\""));
        assertTrue(closeAction.contains("android:layout_height=\"48dp\""));
        assertTrue(closeAction.contains("android:padding=\"12dp\""));
        assertTrue(smsCodeAction.contains("style=\"@style/Widget.Ink.Button.Text\""));
        assertTrue(smsCodeAction.contains("android:layout_height=\"48dp\""));
    }

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
        assertNotEquals(0, R.drawable.ic_ink_phone);
        assertNotEquals(0, R.drawable.ic_ink_lock);
        assertNotEquals(0, R.drawable.bg_login_input);
    }

    @Test
    public void smsLoginKeepsLifecycleAndSuccessfulSyncContract() throws IOException {
        String loginActivity = readFile("src/main/java/com/ldp/reader/ui/activity/LoginActivity.kt");
        String loginViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/LoginViewModel.kt");

        assertTrue(loginActivity.contains("private var smsCodeCountDownTimer: CountDownTimer? = null"));
        assertTrue(loginActivity.contains("renderSmsCodeAttempt(attempt)"));
        assertTrue(loginActivity.contains("viewModel.requestSmsCode(phoneNumber)"));
        assertTrue(loginActivity.contains("viewModel.submitSmsLogin(enteredPhoneNumber, enteredSmsCode, registrationId)"));
        assertTrue(loginActivity.contains("finishSuccessfulLogin(\"telecom\", loginToken, loginPhone)"));
        assertTrue(loginActivity.contains("token.orEmpty()"));
        assertTrue(loginActivity.contains("name.orEmpty()"));
        assertTrue(loginActivity.contains("ViewModelProvider(this)[LoginViewModel::class.java]"));
        assertTrue(loginActivity.contains("setResult(Activity.RESULT_OK, BookshelfSyncRequest.resultIntent())"));
        assertTrue(loginActivity.contains("fun shouldRequestBookShelfSync(data: Intent?): Boolean"));
        assertFalse(loginActivity.contains("EventHandler"));
        assertFalse(loginActivity.contains("SMSSDK"));
        assertFalse(loginActivity.contains("submissionGuard"));
        assertFalse(loginActivity.contains("RxBus"));
        assertFalse(loginActivity.contains("BookSyncEvent"));
        assertFalse(loginActivity.contains("intent.getBooleanExtra"));

        int preLoginStart = loginViewModel.indexOf("fun preDirectLogin()");
        int smsLoginStart = loginViewModel.indexOf("fun submitSmsLogin", preLoginStart);
        int autoDirectLogin = loginViewModel.indexOf("directLogin()", preLoginStart);
        assertFalse(autoDirectLogin >= 0 && autoDirectLogin < smsLoginStart);
        assertTrue(loginViewModel.contains("SMSSDK.registerEventHandler(smsEventHandler)"));
        assertTrue(loginViewModel.contains("SmsSdkProcessDrainBroker.ensureRegistered()"));
        assertTrue(loginViewModel.contains("private object SmsSdkProcessDrainBroker"));
        assertTrue(loginViewModel.contains("drainSmsCodeSdkTransportIfOwnerInactive()"));
        assertTrue(loginViewModel.contains("drainSmsLoginSdkTransportIfOwnerInactive()"));
        assertTrue(loginViewModel.contains("submissionGuard.onOwnerCleared()"));
        assertTrue(loginViewModel.contains("SMSSDK.unregisterEventHandler(smsEventHandler)"));
        assertTrue(loginViewModel.contains("internal val smsLoginResults: LiveData<SmsLoginResult>"));
    }

    @Test
    public void loginPageUsesImmersiveStatusAndDirectLoginFailurePrompt() throws IOException {
        String loginLayout = readFile("src/main/res/layout/activity_login.xml");
        assertFalse(loginLayout.contains("android:fitsSystemWindows=\"true\""));

        String loginActivity = readFile("src/main/java/com/ldp/reader/ui/activity/LoginActivity.kt");
        assertTrue(loginActivity.contains("InkSystemBars.apply(this)"));
        assertFalse(loginActivity.contains("View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR"));
        assertTrue(loginActivity.contains("private fun showDirectLoginError()"));
        assertTrue(loginActivity.contains("一键登录失败，请使用验证码登录"));

        String loginViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/LoginViewModel.kt");
        assertTrue(loginViewModel.contains("val directLoginErrors: LiveData<Int>"));
        assertTrue(loginViewModel.contains("_directLoginErrors.postValue(++directLoginErrorVersion)"));
    }

    @Test
    public void smsLoginCallbacksUseGenerationBoundImmutableCredentials() throws IOException {
        String loginActivity = readFile("src/main/java/com/ldp/reader/ui/activity/LoginActivity.kt");
        String loginViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/LoginViewModel.kt");
        String submissionGuard = readFile("src/main/java/com/ldp/reader/ui/activity/LoginSubmissionGuard.kt");

        assertTrue(loginActivity.contains("attempt.phoneNumber"));
        assertTrue(loginActivity.contains("attempt.smsCode"));
        assertTrue(loginActivity.contains("val attempt = result.attempt"));
        assertTrue(loginActivity.contains("viewModel.consumeSmsLoginSuccess(attempt)"));
        assertFalse(loginActivity.contains("\n    var phoneNumber ="));
        assertFalse(loginActivity.contains("\n    var smsCode ="));

        assertTrue(loginViewModel.contains("private val submissionGuard = LoginSubmissionGuard()"));
        assertTrue(loginViewModel.contains("override fun afterEvent(event: Int, result: Int, data: Any)"));
        assertFalse(loginViewModel.contains("override fun beforeEvent"));
        assertFalse(loginViewModel.contains("Thread.currentThread()"));
        assertTrue(loginViewModel.contains("attempt.registrationId"));
        assertTrue(loginViewModel.contains("_smsLoginResults.value = SmsLoginResult(attempt, response)"));
        assertTrue(submissionGuard.contains("var smsLoginSdkTransportAttempt: SmsLoginAttempt? = null"));
        assertTrue(submissionGuard.contains("PREVIOUS_SDK_REQUEST_ENDING"));
        assertTrue(loginActivity.contains("请求处理中，请稍后再试"));
        assertFalse(loginActivity.contains("上一登录请求仍在结束中"));
        assertFalse(loginActivity.contains("上一验证码请求仍在结束中"));
    }

    @Test
    public void loginFailuresUsePlainActionableCopyWithoutBackendDetails() throws IOException {
        String loginActivity = readFile("src/main/java/com/ldp/reader/ui/activity/LoginActivity.kt");

        assertTrue(loginActivity.contains("SmsCodeFeedback.PREVIOUS_REQUEST_ENDING"));
        assertTrue(loginActivity.contains("SmsLoginFeedback.PREVIOUS_REQUEST_ENDING"));
        assertTrue(loginActivity.contains("ToastUtils.show(\"请求处理中，请稍后再试\")"));

        int directLoginStart = loginActivity.indexOf("private fun finishDirectLogin");
        int smsLoginStart = loginActivity.indexOf("private fun finishSmsLogin", directLoginStart);
        String directLoginBody = loginActivity.substring(directLoginStart, smsLoginStart);
        assertTrue(directLoginBody.contains("ToastUtils.show(\"一键登录失败，请使用验证码登录\")"));
        assertFalse(directLoginBody.contains("ToastUtils.show(\"登录失败\" + loginResultBean.error)"));
    }

    @Test
    public void smsLoginTimeoutCoversSdkAndBackendAcrossRecreation() throws IOException {
        String loginActivity = readFile("src/main/java/com/ldp/reader/ui/activity/LoginActivity.kt");
        String loginViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/LoginViewModel.kt");

        assertTrue(loginViewModel.contains("private var smsLoginTimeoutJob: Job? = null"));
        assertTrue(loginViewModel.contains("scheduleSmsLoginTimeout(start.attempt)"));
        assertTrue(loginViewModel.contains("waitUntilDeadline(attempt.deadlineMs)"));
        assertTrue(loginViewModel.contains("submissionGuard.onSmsLoginTimedOut(attempt, nowMs)"));
        int sdkApplyStart = loginViewModel.indexOf("private fun applySmsLoginSdkResolution");
        int acceptedSdkStart = loginViewModel.indexOf("resolution.startBackendAttempt", sdkApplyStart);
        int backendStart = loginViewModel.indexOf("private fun startSmsLoginBackend", sdkApplyStart);
        String acceptedSdkBody = loginViewModel.substring(acceptedSdkStart, backendStart);
        assertFalse(acceptedSdkBody.contains("cancelSmsLoginTimeout(attempt)"));
        assertFalse(loginActivity.contains("activeSmsLoginAttempt"));
        assertFalse(loginActivity.contains("finishSmsLoginAttempt"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String viewTag(String layout, String id) {
        Pattern pattern = Pattern.compile(
                "<[^>]+android:id=\"@\\+id/" + Pattern.quote(id) + "\"[^>]*>",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(layout);
        assertTrue("Missing view @+id/" + id, matcher.find());
        return matcher.group();
    }
}
