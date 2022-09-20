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

    private final List<RethinkSyncClient.Attribute> cacheData = new ArrayList<>();

    public RethinkSyncImpl(Context  context, Map<String, String> params, Sync.Callback callback) {
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
        client.add(mDefaultChannel, data, room.getId(), ret -> callback.onSuccess(), callback::onFail);
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
        client.query(mDefaultChannel, ret -> callback.onSuccess(new ArrayList<>(ret)), callback::onFail);
    }

    @Override
    public void get(DocumentReference reference, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();

        client.query(channel, ret -> callback.onSuccess(ret.size() > 0? ret.get(0): null), callback::onFail);
    }

    @Override
    public void get(DocumentReference reference, String key, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
        client.query(channel, ret -> callback.onSuccess(ret.size() > 0? ret.get(0): null), callback::onFail);
    }

    @Override
    public void get(CollectionReference reference, Sync.DataListCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.query(channel, ret -> callback.onSuccess(new ArrayList<>(ret)), callback::onFail);
    }

    @Override
    public void add(CollectionReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        add(reference, (Object) data, callback);
    }

    @Override
    public void add(CollectionReference reference, Object data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.add(channel, data, UUIDUtil.uuid(), callback::onSuccess, callback::onFail);
    }

    @Override
    public void delete(DocumentReference reference, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        if(reference.getId().equals(majorChannel)){
            String channel = mDefaultChannel;

            // remove the scene itself, remove it from scene list
            List<String> list = new ArrayList<>();
            list.add(majorChannel);
            client.delete(channel, list, ret -> callback.onSuccess(), callback::onFail);
            client.unsubscribe(mDefaultChannel, null);
        }
        else{
            // remove specific property
            String channel = majorChannel + reference.getId();

            List<String> list = new ArrayList<>();
            list.add(reference.getId());
            client.delete(channel, list, ret -> callback.onSuccess(), callback::onFail);
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
        client.delete(channel, list, ret -> callback.onSuccess(), callback::onFail);
    }

    @Override
    public void update(DocumentReference reference, String key, Object data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
        client.update(channel, data, channel, callback::onSuccess, callback::onFail);
    }

    @Override
    public void update(DocumentReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? mDefaultChannel : majorChannel + reference.getId();
        client.update(channel, data, reference.getId(), callback::onSuccess, callback::onFail);
    }

    @Override
    public void update(CollectionReference reference, String id, Object data, Sync.Callback callback) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.update(channel, data, id, ret -> callback.onSuccess(), callback::onFail);
    }

    @Override
    public void subscribe(DocumentReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String channel = reference.getId().equals(majorChannel) ? mDefaultChannel : majorChannel + reference.getId();
        client.subscribe(channel,
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
        String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
        client.subscribe(channel,
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
    public void subscribe(CollectionReference reference, Sync.EventListener listener) {
        String majorChannel = reference.getParent();
        String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
        client.subscribe(channel,
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
        client.unsubscribe(id, listener);
    }

    @Override
    public void destroy() {
        client.release();
    }


}
