package com.leaves.app.shareme;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;

import io.realm.Realm;

/**
 * Created by leaves on 17-1-23.
 */
public class SApplication extends Application {
    @SuppressLint("StaticFieldLeak")
    private static Context sContext;

    public SApplication() {
    }


    @Override
    public void onCreate() {
        super.onCreate();
        sContext = this;
        Realm.init(this);
    }

    public static Context getContext() {
        return sContext;
    }

}
