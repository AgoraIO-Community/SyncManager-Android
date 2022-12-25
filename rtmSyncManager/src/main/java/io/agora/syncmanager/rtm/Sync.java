package io.agora.syncmanager.rtm;

import android.content.Context;

import java.util.HashMap;
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

    public void init(RethinkConfig config, Callback callback) {
        mISyncManager = new RethinkSyncImpl(config, callback);
    }

//    public void init(Context context, Map<String, String> params, Callback callback) {
//        mISyncManager = new RtmSyncImpl(context, params, callback);
//    }

    public void destroy(){
        mISyncManager.destroy();
    }

    public void createScene(@NonNull Scene room, @NonNull Callback callback){
        mISyncManager.createScene(room, callback);
    }

    public void joinScene(@NonNull String sceneId, @Nullable JoinSceneCallback callback) {
        mISyncManager.joinScene(sceneId, callback);
    }

    public void getScenes(DataListCallback callback) {
        mISyncManager.getScenes(callback);
    }

    public void deleteScene(String sceneId, Callback callback) {
        mISyncManager.deleteScene(sceneId, callback);
    }

    public void get(DocumentReference reference, Sync.DataItemCallback callback) {
        mISyncManager.get(reference, callback);
    }

    public void get(DocumentReference reference, String key, Sync.DataItemCallback callback) {
        mISyncManager.get(reference, key, callback);
    }

    public void get(CollectionReference reference, Sync.DataListCallback callback) {
        mISyncManager.get(reference, callback);
    }

    public void add(CollectionReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        mISyncManager.add(reference, data, callback);
    }

    public void add(CollectionReference reference, Object data, Sync.DataItemCallback callback) {
        mISyncManager.add(reference, data, callback);
    }

    public void delete(DocumentReference reference, Sync.Callback callback) {
        mISyncManager.delete(reference, callback);
    }

    public void delete(CollectionReference reference, Sync.Callback callback) {
        mISyncManager.delete(reference, callback);
    }

    public void delete(CollectionReference reference, String id, Sync.Callback callback) {
        mISyncManager.delete(reference, id, callback);
    }

    public void update(DocumentReference reference, String key, Object data, Sync.DataItemCallback callback) {
        mISyncManager.update(reference, key, data, callback);
    }

    public void update(DocumentReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        mISyncManager.update(reference, data, callback);
    }

    public void update(CollectionReference reference, String id, Object data, Sync.Callback callback) {
        mISyncManager.update(reference, id, data, callback);
    }

    public void subscribe(DocumentReference reference, Sync.EventListener listener) {
        mISyncManager.subscribe(reference, listener);
    }

    public void subscribe(DocumentReference reference, String key, Sync.EventListener listener) {
        mISyncManager.subscribe(reference, key, listener);
    }

    public void subscribe(CollectionReference reference, Sync.EventListener listener) {
        mISyncManager.subscribe(reference, listener);
    }

    public void unsubscribe(String id, Sync.EventListener listener) {
        mISyncManager.unsubscribe(id, listener);
    }

    public void subscribeScene(SceneReference reference, Sync.EventListener listener) {
        mISyncManager.subscribeScene(reference, listener);
    }

    public void unsubscribeScene(SceneReference reference, Sync.EventListener listener) {
        mISyncManager.unsubscribeScene(reference, listener);
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
