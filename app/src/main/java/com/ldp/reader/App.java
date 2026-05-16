package com.ldp.reader;

import android.app.Application;
import android.content.Context;

import androidx.multidex.MultiDex;

//import com.didichuxing.doraemonkit.DoKit;
import com.mob.MobSDK;
import com.tencent.mmkv.MMKV;
import com.tencent.bugly.crashreport.CrashReport;


/**
 * Created by ldp on 17-4-15.
 */

public class App extends Application {
    private static Context sInstance;


    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        MMKV.initialize(this);
        CrashReport.initCrashReport(getApplicationContext(), "ab86f05cf4", true);

        MobSDK.submitPolicyGrantResult(true);
        if (BuildConfig.DEBUG) {
//            new DoKit.Builder(this).debug(true).build();
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    public static Context getContext() {
        return sInstance;
    }
}
