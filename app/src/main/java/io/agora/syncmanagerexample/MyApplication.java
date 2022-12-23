package io.agora.syncmanagerexample;

import android.app.Application;
import android.util.Log;
import io.agora.syncmanager.rtm.RethinkConfig;
import io.agora.syncmanager.rtm.Sync;
import io.agora.syncmanager.rtm.SyncManagerException;

import static android.content.ContentValues.TAG;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        //initialize sync manager
        Sync.Instance().init(this, new RethinkConfig(
                this.getString(R.string.rtm_appid), this.getString(R.string.default_scene_name)), new Sync.Callback() {
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
