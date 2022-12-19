package io.agora.syncmanagerexample;

import android.app.Application;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import io.agora.syncmanager.rtm.Sync;
import io.agora.syncmanager.rtm.SyncManagerException;

import static android.content.ContentValues.TAG;

public class MyApplication extends Application {

    private static final String APP_ID = "appid";
    private static final String DEFAULT_CHANNEL_NAME = "defaultChannel";
    private static final String DEFAULT_SCENE_NAME_PARAM = "defaultScene";
    private static final String TOKEN = "token";

    @Override
    public void onCreate() {
        super.onCreate();
        //initialize sync manager
        Map<String, String> params = new HashMap<>();
        params.put(APP_ID, this.getString(R.string.rtm_appid));
        params.put(DEFAULT_CHANNEL_NAME, this.getString(R.string.default_channel_name));
        params.put(DEFAULT_SCENE_NAME_PARAM, this.getString(R.string.default_scene_name));
//        params.put(TOKEN, this.getString(R.string.rtm_token));
        Sync.Instance().init(this, params, new Sync.Callback() {
            @Override
            public void onSuccess() {

            }

            @Override
            public void onFail(SyncManagerException exception) {
                Log.d(TAG, "SyncManager init failed! " + exception);
            }
        });
    }
}
