package io.agora.syncmanager.rtm.impl;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.agora.syncmanager.rtm.CollectionReference;
import io.agora.syncmanager.rtm.DocumentReference;
import io.agora.syncmanager.rtm.ISyncManager;
import io.agora.syncmanager.rtm.RethinkConfig;
import io.agora.syncmanager.rtm.Scene;
import io.agora.syncmanager.rtm.SceneReference;
import io.agora.syncmanager.rtm.Sync;
import io.agora.syncmanager.rtm.SyncManagerException;
import io.agora.syncmanager.rtm.utils.UUIDUtil;

public class RethinkSyncImpl implements ISyncManager {

    private static final String GET_ROOM_LIST_OBJ_TYPE = "room";

    private String appId;
    private String mSceneName;

    private RethinkSyncClient client;

    private final List<RethinkSyncClient.Attribute> cacheData = new ArrayList<>();

    public RethinkSyncImpl(RethinkConfig config, Sync.Callback callback) {
        appId = config.appId;
        mSceneName = config.sceneName;
        assert appId != null;
        assert mSceneName != null;
        client = new RethinkSyncClient();
        client.init(appId, mSceneName, ret -> {
            callback.onSuccess();
        }, ret -> {
            callback.onFail(new SyncManagerException(ret, "RethinkSyncClient init error"));
        });
    }

    @Override
    public void createScene(Scene room, Sync.Callback callback) {
        client.add(room.getId(), GET_ROOM_LIST_OBJ_TYPE, room.toJson(), room.getId(), ret -> callback.onSuccess(), callback::onFail);
    }

    @Override
    public void joinScene(String sceneId, Sync.JoinSceneCallback callback) {
//        client.subscribe(mDefaultChannel, null, null, ret -> {
//            // 騒操作？
//            List<RethinkSyncClient.CallbackHandler> notifyHandlers = new ArrayList<>();
//            for (String requestId : client.callbackHandlers.keySet()) {
//                RethinkSyncClient.CallbackHandler handler = client.callbackHandlers.get(requestId);
//                if(handler != null && ret.contains(handler.channelName)){
//                    notifyHandlers.add(handler);
//                }
//            }
//            for (RethinkSyncClient.CallbackHandler handler : notifyHandlers) {
//                handler.handleLocalDelete(new RethinkSyncClient.Attribute(handler.channelName, ""));
//            }
//
//        }, null, null);
        callback.onSuccess(new SceneReference(this, sceneId, sceneId));
    }

    @Override
    public void getScenes(Sync.DataListCallback callback) {
        client.getRoomList(ret -> callback.onSuccess(new ArrayList<>(ret)), callback::onFail);
    }

    @Override
    public void deleteScene(String sceneId, Sync.Callback callback) {
        client.deleteRoom(sceneId, ret -> callback.onSuccess(), callback::onFail);
    }

    @Override
    public void get(DocumentReference reference, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();

        client.query(majorChannel, objType, ret -> callback.onSuccess(ret.size() > 0? ret.get(0): null), callback::onFail);
    }

    @Override
    public void get(DocumentReference reference, String key, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
        client.query(majorChannel, objType, ret -> callback.onSuccess(ret.size() > 0? ret.get(0): null), callback::onFail);
    }

    @Override
    public void get(CollectionReference reference, Sync.DataListCallback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.query(majorChannel, objType, ret -> callback.onSuccess(new ArrayList<>(ret)), callback::onFail);
    }

    @Override
    public void add(CollectionReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        add(reference, (Object) data, callback);
    }

    @Override
    public void add(CollectionReference reference, Object data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.add(majorChannel, objType, data, UUIDUtil.uuid(), callback::onSuccess, callback::onFail);
    }

    @Override
    public void delete(DocumentReference reference, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        if(reference.getId().equals(majorChannel)){
            // remove the scene itself, remove it from scene list
            List<String> list = new ArrayList<>();
            list.add(majorChannel);
            client.deleteRoom(majorChannel, ret -> callback.onSuccess(), callback::onFail);
        }
        else{
            // remove specific property
            String objType = majorChannel + reference.getId();

            List<String> list = new ArrayList<>();
            list.add(reference.getId());
            client.delete(majorChannel, objType, list, ret -> callback.onSuccess(), callback::onFail);
        }
    }

    @Override
    public void delete(CollectionReference reference, Sync.Callback callback) {
        if(callback!=null) callback.onFail(new SyncManagerException(-1, "not supported yet"));
    }

    @Override
    public void delete(CollectionReference reference, String id, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        List<String> list = new ArrayList<>();
        list.add(id);
        client.delete(majorChannel, objType, list, ret -> callback.onSuccess(), callback::onFail);
    }

    @Override
    public void update(DocumentReference reference, String key, Object data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getId().equals(majorChannel) ? mSceneName : majorChannel + reference.getId();
        Map<String, Object> wData = new HashMap<>();
        wData.put(key, data);
        client.update(majorChannel, objType, wData, majorChannel, callback::onSuccess, callback::onFail);
    }

    @Override
    public void update(DocumentReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getId().equals(majorChannel) ? mSceneName : majorChannel + reference.getId();
        client.update(majorChannel, objType, data, reference.getId(), callback::onSuccess, callback::onFail);
    }

    @Override
    public void update(CollectionReference reference, String id, Object data, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        String objType = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.update(majorChannel, objType, data, id, ret -> callback.onSuccess(), callback::onFail);
    }

    @Override
    public void subscribe(DocumentReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String objType = reference.getId().equals(majorChannel) ? mSceneName : majorChannel + reference.getId();
        client.subscribe(majorChannel, objType,
                listener::onCreated,
                ret -> {
                    for (RethinkSyncClient.Attribute attribute : ret) {
                        listener.onUpdated(attribute);
                    }
                },
                ret -> {
                    for (String objectId : ret) {
                        listener.onDeleted(new RethinkSyncClient.Attribute(objectId, ""));
                    }
                },
                listener::onSubscribeError,
                listener);
    }

    @Override
    public void subscribe(DocumentReference reference, String key, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String objType = reference.getId().equals(majorChannel) ? mSceneName : majorChannel + reference.getId();
        client.subscribe(majorChannel, objType,
                ret ->{
                    if(ret.getId().equals(majorChannel) && ret.toString().contains(key)){
                        listener.onCreated(ret);
                    }
                },
                ret -> {
                    for (RethinkSyncClient.Attribute attribute : ret) {
                        if(attribute.getId().equals(majorChannel) && attribute.toString().contains(key)){
                            listener.onUpdated(attribute);
                        }
                    }
                },
                ret -> {
                    for (String objectId : ret) {
                        if(objectId.equals(majorChannel)){
                            listener.onDeleted(new RethinkSyncClient.Attribute(objectId, ""));
                        }
                    }
                },
                listener::onSubscribeError,
                listener);
    }

    @Override
    public void subscribe(CollectionReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String objType = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.subscribe(majorChannel, objType,
                listener::onCreated,
                ret -> {
                    for (RethinkSyncClient.Attribute attribute : ret) {
                        listener.onUpdated(attribute);
                    }
                },
                ret -> {
                    for (String objectId : ret) {
                        listener.onDeleted(new RethinkSyncClient.Attribute(objectId, ""));
                    }
                },
                listener::onSubscribeError,
                listener);
    }

    @Override
    public void unsubscribe(String id, Sync.EventListener listener) {
        client.unsubscribe(id, id, listener);
    }

    @Override
    public void subscribeScene(SceneReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        client.subscribe(majorChannel, GET_ROOM_LIST_OBJ_TYPE,
                listener::onCreated,
                ret -> {
                    for (RethinkSyncClient.Attribute attribute : ret) {
                        listener.onUpdated(attribute);
                    }
                },
                ret -> {
                    for (String objectId : ret) {
                        listener.onDeleted(new RethinkSyncClient.Attribute(objectId, ""));
                    }
                },
                listener::onSubscribeError,
                listener);
    }

    @Override
    public void unsubscribeScene(SceneReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        client.unsubscribe(majorChannel, GET_ROOM_LIST_OBJ_TYPE, listener);
    }

    @Override
    public void destroy() {
        client.release();
    }


}
