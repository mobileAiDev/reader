package com.ldp.reader.presenter.contract;

import com.ldp.reader.model.bean.DirectLoginResultBean;
import com.ldp.reader.model.bean.LoginResultBean;
import com.ldp.reader.model.bean.SmsLoginBean;
import com.ldp.reader.ui.base.BaseContract;

/**
 * Created by ldp on 17-6-2.
 */

public interface LoginContract extends BaseContract {

    interface View extends BaseView{
        void finishLogin(LoginResultBean loginResultBean);
        void finishDirectLogin(DirectLoginResultBean loginResultBean);

        void finishSmsLogin(SmsLoginBean smsLoginBean);

        void showDirectLoginError();
    }

    interface Presenter<T extends BaseView> extends BasePresenter<T>{
        void userLogin(String userName,String passWord);
        void preDirectLogin();

        void smsLogin(String phoneNumber, String smsCode, String registrationId);

        void directLogin();
    }
}
