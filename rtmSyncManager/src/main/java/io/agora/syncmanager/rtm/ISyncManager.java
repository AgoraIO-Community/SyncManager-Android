package io.agora.syncmanager.rtm;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;


public interface ISyncManager {
    void joinScene(@NonNull String sceneId, @Nullable Sync.JoinSceneCallback callback);

    void joinScene(boolean isRoomOwner, boolean isMainRoom,  @NonNull String sceneId,@Nullable Sync.JoinSceneCallback callback);

    void createScene(@NonNull Scene room, @Nullable Sync.Callback callback);

    void getScenes(Sync.DataListCallback callback);

    void deleteScene(String sceneId, Sync.Callback callback);

    void get(DocumentReference reference, Sync.DataItemCallback callback);

    void get(DocumentReference reference, String key, Sync.DataItemCallback callback);

    void get(CollectionReference reference, Sync.DataListCallback callback);

    void add(CollectionReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback);

    void add(CollectionReference reference, Object data, Sync.DataItemCallback callback);

    void delete(DocumentReference reference, Sync.Callback callback);

    void delete(CollectionReference reference, Sync.Callback callback);

    void delete(CollectionReference reference, String id, Sync.Callback callback);

    void update(DocumentReference reference, String key, Object data, Sync.DataItemCallback callback);

    void update(DocumentReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback);

    void update(CollectionReference reference, String id, Object data, Sync.Callback callback);

    void subscribe(DocumentReference reference, Sync.EventListener listener);

    void subscribe(DocumentReference reference, String key, Sync.EventListener listener);

    void subscribe(CollectionReference reference, Sync.EventListener listener);

    void unsubscribe(String id, Sync.EventListener listener);

    void subscribeScene(SceneReference reference, Sync.EventListener listener);

    void unsubscribeScene(SceneReference reference, Sync.EventListener listener);

    void subscribeConnectState(Sync.ConnectionStateCallback callback);

    void destroy();

}
