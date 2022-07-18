package io.agora.syncmanager.rtm;

import android.content.Context;

import java.util.List;
import java.util.Map;

import io.agora.common.annotation.NonNull;
import io.agora.common.annotation.Nullable;
import io.agora.syncmanager.rtm.impl.RethinkSyncImpl;
import io.agora.syncmanager.rtm.impl.RtmSyncImpl;

/**
 * 房间状态同步
 */
public final class Sync {

    private volatile static Sync instance;
    private static final String PARAM_IS_USE_RTM = "isUseRtm";

    private Sync() {
    }

    public static Sync Instance() {
        if (instance == null) {
            synchronized (Sync.class) {
                if (instance == null)
                    instance = new Sync();
            }
        }
        return instance;
    }

    private ISyncManager mISyncManager;

    public void init(Context context, Map<String, String> params, Callback callback) {
        String isUseRtm = params.get(PARAM_IS_USE_RTM);
        if ("true".equals(isUseRtm)) {
            mISyncManager = new RtmSyncImpl(context, params, callback);
        } else {
            mISyncManager = new RethinkSyncImpl(context, params, callback);
        }
    }

    public void destroy(){
        mISyncManager.destroy();
    }

    public void joinScene(@NonNull String sceneId, @Nullable JoinSceneCallback callback) {
        mISyncManager.joinScene(sceneId, callback);
    }

    public void createScene(@NonNull Scene room, @NonNull Callback callback){
        mISyncManager.createScene(room, callback);
    }

    public void getScenes(DataListCallback callback) {
        mISyncManager.getScenes(callback);
    }

    public interface EventListener {
        void onCreated(IObject item);

        void onUpdated(IObject item);

        void onDeleted(IObject item);

        void onSubscribeError(SyncManagerException ex);
    }

    public interface JoinSceneCallback {
        void onSuccess(SceneReference sceneReference);

        void onFail(SyncManagerException exception);
    }

    public interface Callback {
        void onSuccess();

        void onFail(SyncManagerException exception);
    }

    public interface DataItemCallback {
        void onSuccess(IObject result);

        void onFail(SyncManagerException exception);
    }

    public interface DataListCallback {
        void onSuccess(List<IObject> result);

        void onFail(SyncManagerException exception);
    }
}
