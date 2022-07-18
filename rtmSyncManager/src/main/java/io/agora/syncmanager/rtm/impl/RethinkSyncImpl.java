package io.agora.syncmanager.rtm.impl;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.agora.syncmanager.rtm.CollectionReference;
import io.agora.syncmanager.rtm.DocumentReference;
import io.agora.syncmanager.rtm.ISyncManager;
import io.agora.syncmanager.rtm.Scene;
import io.agora.syncmanager.rtm.SceneReference;
import io.agora.syncmanager.rtm.Sync;
import io.agora.syncmanager.rtm.SyncManagerException;
import io.agora.syncmanager.rtm.utils.UUIDUtil;

public class RethinkSyncImpl implements ISyncManager {

    private static final String APP_ID = "appid";
    private static final String DEFAULT_CHANNEL_NAME_PARAM = "defaultChannel";

    private String appId;
    private String mDefaultChannel;

    private RethinkSyncClient client;

    public RethinkSyncImpl(Context context, Map<String, String> params, Sync.Callback callback) {
        appId = params.get(APP_ID);
        mDefaultChannel = params.get(DEFAULT_CHANNEL_NAME_PARAM);
        assert appId != null;
        assert mDefaultChannel != null;
        client = new RethinkSyncClient();
        client.init(appId, mDefaultChannel, ret -> {
            if(ret == 0){
                callback.onSuccess();
            }else{
                callback.onFail(new SyncManagerException(ret, "RethinkSyncClient init error"));
            }
        });
    }

    @Override
    public void createScene(Scene room, Sync.Callback callback) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("objectId", room.getId());
        try {
            String sceneInfo = room.toJson();
            JSONObject jsScene = new JSONObject(sceneInfo);
            Iterator<String> keys = jsScene.keys();
            while (keys.hasNext()){
                String key = keys.next();
                data.put(key, jsScene.opt(key));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        client.onSuccessCallbacksVoid.put(mDefaultChannel, ret -> callback.onSuccess());
        client.onFailCallbacks.put(mDefaultChannel, callback::onFail);
        client.add(mDefaultChannel, data, room.getId());
    }

    @Override
    public void joinScene(String sceneId, Sync.JoinSceneCallback callback) {
        callback.onSuccess(new SceneReference(this, sceneId, sceneId));
    }

    @Override
    public void getScenes(Sync.DataListCallback callback) {
        client.onSuccessCallbacks.put(mDefaultChannel, callback::onSuccess);
        client.onFailCallbacks.put(mDefaultChannel, callback::onFail);
        client.query(mDefaultChannel);
    }

    @Override
    public void get(DocumentReference reference, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();

        client.onSuccessCallbacksObj.put(channel, callback::onSuccess);
        client.onFailCallbacks.put(channel, callback::onFail);

        client.query(channel);
    }

    @Override
    public void get(DocumentReference reference, String key, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();

        client.onSuccessCallbacksObj.put(channel, callback::onSuccess);
        client.onFailCallbacks.put(channel, callback::onFail);
        client.query(channel);
    }

    @Override
    public void get(CollectionReference reference, Sync.DataListCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();

        client.onSuccessCallbacks.put(channel, callback::onSuccess);
        client.onFailCallbacks.put(channel, callback::onFail);
        client.query(channel);
    }

    @Override
    public void add(CollectionReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        add(reference, (Object) data, callback);
    }

    @Override
    public void add(CollectionReference reference, Object data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.onSuccessCallbacksObj.put(channel, callback::onSuccess);
        client.onFailCallbacks.put(channel, callback::onFail);
        client.add(channel, data, UUIDUtil.uuid());
    }

    @Override
    public void delete(DocumentReference reference, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        if(reference.getId().equals(majorChannel)){
            String channel = mDefaultChannel;

            client.onSuccessCallbacksVoid.put(channel, ret -> callback.onSuccess());
            client.onFailCallbacks.put(channel, callback::onFail);

            // remove the scene itself, remove it from scene list
            List<String> list = new ArrayList<>();
            list.add(majorChannel);
            client.delete(channel, list);
        }
        else{
            // remove specific property
            String channel = majorChannel + reference.getId();

            client.onSuccessCallbacksVoid.put(channel, ret -> callback.onSuccess());
            client.onFailCallbacks.put(channel, callback::onFail);

            List<String> list = new ArrayList<>();
            list.add(reference.getId());
            client.delete(channel, list);
        }
    }

    @Override
    public void delete(CollectionReference reference, Sync.Callback callback) {
        if(callback!=null) callback.onFail(new SyncManagerException(-1, "not supported yet"));
    }

    @Override
    public void delete(CollectionReference reference, String id, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        List<String> list = new ArrayList<>();
        list.add(id);
        client.onSuccessCallbacksVoid.put(channel, ret -> callback.onSuccess());
        client.onFailCallbacks.put(channel, callback::onFail);

        client.delete(channel, list);
    }

    @Override
    public void update(DocumentReference reference, String key, Object data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
        client.onSuccessCallbacksObj.put(channel, callback::onSuccess);
        client.onFailCallbacks.put(channel, callback::onFail);
        client.update(channel, data, channel);
    }

    @Override
    public void update(DocumentReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();
        client.onSuccessCallbacksObj.put(channel, callback::onSuccess);
        client.onFailCallbacks.put(channel, callback::onFail);
        client.update(channel, data, reference.getId());
    }

    @Override
    public void update(CollectionReference reference, String id, Object data, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.onSuccessCallbacksVoid.put(channel, ret -> callback.onSuccess());
        client.onFailCallbacks.put(channel, callback::onFail);
        client.update(channel, data, id);
    }

    @Override
    public void subscribe(DocumentReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();
        client.onCreateCallbacks.put(channel, listener::onCreated);
        client.onUpdateCallbacks.put(channel, listener::onUpdated);
        client.onDeletedCallbacks.put(channel, listener::onDeleted);
        client.onFailCallbacks.put(channel, listener::onSubscribeError);
        client.subscribe(channel);
    }

    @Override
    public void subscribe(DocumentReference reference, String key, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
        client.onCreateCallbacks.put(channel, listener::onCreated);
        client.onUpdateCallbacks.put(channel, listener::onUpdated);
        client.onDeletedCallbacks.put(channel, listener::onDeleted);
        client.onFailCallbacks.put(channel, listener::onSubscribeError);
        client.subscribe(channel);
    }

    @Override
    public void subscribe(CollectionReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.onCreateCallbacks.put(channel, listener::onCreated);
        client.onUpdateCallbacks.put(channel, listener::onUpdated);
        client.onDeletedCallbacks.put(channel, listener::onDeleted);
        client.onFailCallbacks.put(channel, listener::onSubscribeError);
        client.subscribe(channel);
    }

    @Override
    public void unsubscribe(String id, Sync.EventListener listener) {
        client.onCreateCallbacks.remove(id);
        client.onUpdateCallbacks.remove(id);
        client.onDeletedCallbacks.remove(id);
        client.onFailCallbacks.remove(id);
        client.unsubscribe(id);
    }

    @Override
    public void destroy() {
        client.release();
    }


}
