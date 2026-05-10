package com.ldp.reader.ui;

import static org.junit.Assert.assertNotEquals;

import com.ldp.reader.R;

import org.junit.Test;

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
}
