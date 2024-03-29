package io.agora.syncmanager.rtm.impl;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.agora.common.annotation.NonNull;
import io.agora.common.annotation.Nullable;
import io.agora.rtm.ChannelAttributeOptions;
import io.agora.rtm.ErrorInfo;
import io.agora.rtm.ResultCallback;
import io.agora.rtm.RtmChannel;
import io.agora.rtm.RtmChannelAttribute;
import io.agora.rtm.RtmChannelListener;
import io.agora.rtm.RtmChannelMember;
import io.agora.rtm.RtmClient;
import io.agora.rtm.RtmClientListener;
import io.agora.rtm.RtmFileMessage;
import io.agora.rtm.RtmImageMessage;
import io.agora.rtm.RtmMediaOperationProgress;
import io.agora.rtm.RtmMessage;
import io.agora.syncmanager.rtm.CollectionReference;
import io.agora.syncmanager.rtm.DocumentReference;
import io.agora.syncmanager.rtm.IObject;
import io.agora.syncmanager.rtm.ISyncManager;
import io.agora.syncmanager.rtm.Scene;
import io.agora.syncmanager.rtm.SceneReference;
import io.agora.syncmanager.rtm.Sync;
import io.agora.syncmanager.rtm.SyncManagerException;

public class RtmSyncImpl implements ISyncManager {

    private String appId;
    private String token;
    private static final String APP_ID = "appid";
    private static final String TOKEN = "token";
    private static final String UID = "uid";
    private static final String DEFAULT_CHANNEL_NAME_PARAM = "defaultChannel";
    private String mDefaultChannel;
    private RtmClient client;
    private Map<String, RtmChannel> majorChannels = new ConcurrentHashMap<>();
    private String uid;
    private Map<String, Sync.EventListener> eventListeners = new ConcurrentHashMap<>();
    private Map<String, NamedChannelListener> channelListeners = new ConcurrentHashMap<>();
    private Map<String, List<RtmChannelAttribute>> cachedAttrs = new ConcurrentHashMap<>();

    private Gson gson = new GsonBuilder()
            .create();


    String uuid(){
        String[] hexArray = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F"};
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(UUID.randomUUID().toString().getBytes());
            byte[] rawBit = md.digest();
            String outputMD5 = " ";
            for (int i = 0; i < 16; i++) {
                outputMD5 = outputMD5 + hexArray[rawBit[i] >>> 4 & 0x0f];
                outputMD5 = outputMD5 + hexArray[rawBit[i] & 0x0f];
            }
            return outputMD5.trim();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, e.getMessage());
        }
        return null;
    }

    public RtmSyncImpl(Context context, Map<String, String> params, Sync.Callback callback) {
        try {
            appId = params.get(APP_ID);
            token = params.get(TOKEN);
            mDefaultChannel = params.get(DEFAULT_CHANNEL_NAME_PARAM);
            assert appId != null;
            assert mDefaultChannel != null;
            client = RtmClient.createInstance(context, appId, iEventListener);
            client.setLogFile(new File(context.getExternalCacheDir(), "agorartm.log").getAbsolutePath());
            uid = params.containsKey(UID) ? params.get(UID) : uuid();
            client.login(token, uid, new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    Log.d(TAG, "on rtm login successful! ");
                    callback.onSuccess();
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    Log.d(TAG, "on rtm login failed! ");
                    callback.onFail(new SyncManagerException(-1, errorInfo.toString()));
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void destroy() {
        this.eventListeners.clear();
        this.majorChannels.clear();
        this.channelListeners.clear();
        this.cachedAttrs.clear();
        if(client != null){
            client.logout(new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    client.release();
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {

                }
            });
        }
    }

    @Override
    public void createScene(@NonNull Scene room,@Nullable Sync.Callback callback) {
        String channelName = room.getId();
        assert channelName != null;

        RtmChannelAttribute attribute = new RtmChannelAttribute();
        attribute.setKey(channelName);
        attribute.setValue(room.toJson());
        ChannelAttributeOptions options = new ChannelAttributeOptions();
        List<RtmChannelAttribute> list = new ArrayList<>();
        list.add(attribute);
        client.addOrUpdateChannelAttributes(mDefaultChannel, list, options, new ResultCallback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                if(callback!=null) callback.onSuccess();
            }

            @Override
            public void onFailure(ErrorInfo errorInfo) {
                if(callback!=null) callback.onFail(new SyncManagerException(-1, errorInfo.toString()));
            }
        });

    }

    @Override
    public void joinScene(@NonNull String sceneId,@Nullable Sync.JoinSceneCallback callback) {
        if(!channelListeners.containsKey(mDefaultChannel)){
            NamedChannelListener defaultListener = new NamedChannelListener(mDefaultChannel);
            RtmChannel defaultChannel = client.createChannel(mDefaultChannel, defaultListener);
            defaultChannel.join(new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    channelListeners.put(mDefaultChannel, defaultListener);
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    if (callback != null)
                        callback.onFail(new SyncManagerException(-1, "join rtm channel failed!\n"+errorInfo.getErrorDescription()));
                }
            });
        }
        NamedChannelListener listener = new NamedChannelListener(sceneId);
        RtmChannel rtmChannel = client.createChannel(sceneId, listener);
        rtmChannel.join(new ResultCallback<Void>() {
            @Override
            public void onSuccess(Void unused) {
                majorChannels.put(sceneId, rtmChannel);
                channelListeners.put(sceneId, listener);
                if(callback!=null) callback.onSuccess(new SceneReference(RtmSyncImpl.this, sceneId, sceneId));
            }

            @Override
            public void onFailure(ErrorInfo errorInfo) {
                if (callback != null)
                    callback.onFail(new SyncManagerException(-1, "join rtm channel failed!\n"+errorInfo.getErrorDescription()));
            }
        });
    }

    @Override
    public void getScenes(Sync.DataListCallback callback) {
        client.getChannelAttributes(mDefaultChannel, new ResultCallback<List<RtmChannelAttribute>>() {
            @Override
            public void onSuccess(List<RtmChannelAttribute> rtmChannelAttributes) {
                if (rtmChannelAttributes != null && rtmChannelAttributes.size() > 0) {
                    List<IObject> list = new ArrayList<>();
                    for (RtmChannelAttribute attribute : rtmChannelAttributes) {
                        list.add(new Attribute(attribute.getKey(), attribute.getValue()));
                    }
                    callback.onSuccess(list);
                } else {
                    callback.onFail(new SyncManagerException(-1, "empty scene"));
                }
            }

            @Override
            public void onFailure(ErrorInfo errorInfo) {
                callback.onFail(new SyncManagerException(-1, errorInfo.getErrorDescription()));
            }
        });
    }

    @Override
    public void get(DocumentReference reference, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();
            client.getChannelAttributes(channel, new ResultCallback<List<RtmChannelAttribute>>() {
                @Override
                public void onSuccess(List<RtmChannelAttribute> rtmChannelAttributes) {
                    if (rtmChannelAttributes != null && rtmChannelAttributes.size() > 0) {
                        callback.onSuccess(new Attribute(rtmChannelAttributes.get(0).getKey(), rtmChannelAttributes.get(0).getValue()));
                    } else {
                        callback.onFail(new SyncManagerException(-1, "empty attributes"));
                    }
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    callback.onFail(new SyncManagerException(-1, errorInfo.getErrorDescription()));
                }
            });
        } else {
            callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void get(DocumentReference reference, String key, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
            client.getChannelAttributes(channel, new ResultCallback<List<RtmChannelAttribute>>() {
                @Override
                public void onSuccess(List<RtmChannelAttribute> rtmChannelAttributes) {
                    if (rtmChannelAttributes != null && rtmChannelAttributes.size() > 0) {
                        RtmChannelAttribute attribute = rtmChannelAttributes.get(0);
                        callback.onSuccess(new Attribute(attribute.getKey(), attribute.getValue()));
                    } else {
                        callback.onFail(new SyncManagerException(-1, "empty attributes"));
                    }
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    callback.onFail(new SyncManagerException(-1, errorInfo.getErrorDescription()));
                }
            });
        } else {
            callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void get(CollectionReference reference, Sync.DataListCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
            client.getChannelAttributes(channel, new ResultCallback<List<RtmChannelAttribute>>() {
                @Override
                public void onSuccess(List<RtmChannelAttribute> rtmChannelAttributes) {
                    if (rtmChannelAttributes != null && rtmChannelAttributes.size() > 0) {
                        cachedAttrs.put(channel, rtmChannelAttributes);
                        List<IObject> res = new ArrayList<>();
                        for (RtmChannelAttribute attribute : rtmChannelAttributes) {
                            res.add(new Attribute(attribute.getKey(), attribute.getValue()));
                        }
                        callback.onSuccess(res);
                    } else {
                        callback.onFail(new SyncManagerException(-1, "empty attributes"));
                    }
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    callback.onFail(new SyncManagerException(-1, errorInfo.getErrorDescription()));
                }
            });
            NamedChannelListener listener;
            if (channelListeners.containsKey(channel)) {
                listener = channelListeners.get(channel);
            } else {
                listener = new NamedChannelListener(channel);
                channelListeners.put(channel, listener);
            }
            RtmChannel rtmChannel = client.createChannel(channel, listener);
            rtmChannel.join(null);
        } else {
            callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void add(CollectionReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        add(reference, (Object) data, callback);
    }

    @Override
    public void add(CollectionReference reference, Object data, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
            if (!channelListeners.containsKey(channel)) {
                NamedChannelListener listener = new NamedChannelListener(channel);
                RtmChannel rtmChannel = client.createChannel(channel, listener);
                rtmChannel.join(null);
                channelListeners.put(channel, listener);
            }
            RtmChannelAttribute attribute = new RtmChannelAttribute();
            attribute.setKey(uuid());
            String json = gson.toJson(data);
            attribute.setValue(json);
            ChannelAttributeOptions options = new ChannelAttributeOptions();
            options.setEnableNotificationToChannelMembers(true);
            List<RtmChannelAttribute> list = new ArrayList<>();
            list.add(attribute);
            client.addOrUpdateChannelAttributes(channel, list, options, new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    List<RtmChannelAttribute> rtmChannelAttributes = cachedAttrs.get(channel);
                    if(rtmChannelAttributes != null){
                        rtmChannelAttributes.add(attribute);
                    }
                    else{
                        rtmChannelAttributes = new ArrayList<>();
                        rtmChannelAttributes.add(attribute);
                        cachedAttrs.put(channel, rtmChannelAttributes);
                    }
                    IObject item = new Attribute(attribute.getKey(), json);
                    Sync.EventListener listener = eventListeners.get(channel);
                    if(listener !=null){
                        listener.onCreated(item);
                    }
                    if(callback!=null) callback.onSuccess(item);
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
                }
            });
        } else {
            if(callback!=null) callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void delete(DocumentReference reference, Sync.Callback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            if(reference.getId().equals(majorChannel)){
                // remove the scene itself, remove it from scene list
                List<String> list = new ArrayList<>();
                list.add(majorChannel);
                ChannelAttributeOptions options = new ChannelAttributeOptions();
                options.setEnableNotificationToChannelMembers(true);
                client.deleteChannelAttributesByKeys(mDefaultChannel, list, options, new ResultCallback<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        if(callback!=null) callback.onSuccess();
                    }

                    @Override
                    public void onFailure(ErrorInfo errorInfo) {
                        if(callback!=null) callback.onFail(new SyncManagerException(-1, "remove scene failed!"));
                    }
                });
                unsubscribe(majorChannel, null);
            }
            else{
                // remove specific property
                String channel = majorChannel + reference.getId();
                ChannelAttributeOptions options = new ChannelAttributeOptions();
                options.setEnableNotificationToChannelMembers(true);
                List<String> list = new ArrayList<>();
                List<RtmChannelAttribute> attrs = cachedAttrs.get(channel);
                if(attrs!=null){
                    for(RtmChannelAttribute item : attrs){
                        if(item.getValue().contains(reference.getId())){
                            list.add(item.getKey());
                        }
                    }
                    client.deleteChannelAttributesByKeys(channel, list, options, new ResultCallback<Void>() {
                        @Override
                        public void onSuccess(Void unused) {
                            List<RtmChannelAttribute> rtmChannelAttributes = cachedAttrs.get(channel);
                            if(rtmChannelAttributes != null){
                                for(RtmChannelAttribute item : rtmChannelAttributes){
                                    if(item.getKey().equals(reference.getId())){
                                        rtmChannelAttributes.remove(item);
                                        break;
                                    }
                                }
                            }
                            Sync.EventListener listener = eventListeners.get(channel);
                            if(listener !=null){
                                listener.onDeleted(new Attribute(reference.getId(), list.get(0)));
                            }
                            if(callback!=null) callback.onSuccess();
                        }

                        @Override
                        public void onFailure(ErrorInfo errorInfo) {
                            if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
                        }
                    });
                }
            }
        } else {
            if(callback!=null) callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void delete(CollectionReference reference, Sync.Callback callback) {
        if(callback!=null) callback.onFail(new SyncManagerException(-1, "not supported yet"));
    }

    @Override
    public void delete(CollectionReference reference, String id, Sync.Callback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
            List<String> list = new ArrayList<>();
            list.add(id);
            ChannelAttributeOptions options = new ChannelAttributeOptions();
            options.setEnableNotificationToChannelMembers(true);
            client.deleteChannelAttributesByKeys(channel, list, options, new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    callback.onSuccess();
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    callback.onFail(new SyncManagerException(-1, "delete collection element failed!"));
                }
            });
        }
    }

    @Override
    public void update(CollectionReference reference, String id, Object data, Sync.Callback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
            RtmChannelAttribute attribute = new RtmChannelAttribute();
            attribute.setKey(id);
            String json = gson.toJson(data);
            attribute.setValue(json);
            ChannelAttributeOptions options = new ChannelAttributeOptions();
            options.setEnableNotificationToChannelMembers(true);
            List<RtmChannelAttribute> list = new ArrayList<>();
            list.add(attribute);
            client.addOrUpdateChannelAttributes(channel, list, options, new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    if (callback != null) {
                        callback.onSuccess();
                    }
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    callback.onFail(new SyncManagerException(-1, "update collection element failed!"));
                }
            });
        }
    }

    @Override
    public void update(DocumentReference reference, String key, Object data, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
            RtmChannelAttribute attribute = new RtmChannelAttribute();
            attribute.setKey(channel);
            String json = gson.toJson(data);
            attribute.setValue(json);
            ChannelAttributeOptions options = new ChannelAttributeOptions();
            options.setEnableNotificationToChannelMembers(true);
            List<RtmChannelAttribute> list = new ArrayList<>();
            list.add(attribute);
            client.addOrUpdateChannelAttributes(channel, list, options, new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    IObject item = new Attribute(key, json);
                    if(callback!=null) callback.onSuccess(item);
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
                }
            });
        } else {
            if(callback!=null) callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void update(DocumentReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();
            RtmChannelAttribute attribute = new RtmChannelAttribute();
            attribute.setKey(reference.getId());
            String json = gson.toJson(data);
            attribute.setValue(json);
            ChannelAttributeOptions options = new ChannelAttributeOptions();
            options.setEnableNotificationToChannelMembers(true);
            List<RtmChannelAttribute> list = new ArrayList<>();
            list.add(attribute);
            client.addOrUpdateChannelAttributes(channel, list, options, new ResultCallback<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    if(callback!=null) callback.onSuccess(new Attribute(reference.getId(), json));
                }

                @Override
                public void onFailure(ErrorInfo errorInfo) {
                    if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
                }
            });
        } else {
            if(callback!=null) callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void subscribe(DocumentReference reference, Sync.EventListener listener) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();
            eventListeners.put(channel, listener);
        }
        else{
            listener.onSubscribeError(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void subscribe(DocumentReference reference, String key, Sync.EventListener listener) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
            if (!channelListeners.containsKey(channel)) {
                NamedChannelListener namedChannelListener = new NamedChannelListener(channel);
                RtmChannel rtmChannel = client.createChannel(channel, namedChannelListener);
                rtmChannel.join(null);
                channelListeners.put(channel, namedChannelListener);
            }
            eventListeners.put(channel, listener);
        }
        else{
            listener.onSubscribeError(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void subscribe(CollectionReference reference, Sync.EventListener eventListener) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
            eventListeners.put(channel, eventListener);
            // for generate cachedAttrs
            get(reference, new Sync.DataListCallback() {
                @Override
                public void onSuccess(List<IObject> result) {

                }

                @Override
                public void onFail(SyncManagerException exception) {

                }
            });
        }
        else{
            eventListener.onSubscribeError(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void unsubscribe(String id, @Nullable Sync.EventListener listener) {
        // 1. leave related rtm channel
        List<String> toDeleted = new ArrayList<>();
        for (String s : channelListeners.keySet()) {
            if(s.startsWith(id)){
                client.createChannel(s, channelListeners.get(s)).leave(null);
                toDeleted.add(s);
            }
        }
        for (String s : toDeleted){
            channelListeners.remove(s);
        }

        // 2. clean all event Listeners
        if(listener!=null && eventListeners.containsValue(listener)){
            for(Map.Entry<String, Sync.EventListener> entry : eventListeners.entrySet()){
                if(listener == entry.getValue()){
                    eventListeners.remove(entry.getKey());
                    return;
                }
            }
        }

        // 3. move joined channels cache.
        majorChannels.remove(id);
    }

    private RtmClientListener iEventListener = new RtmClientListener() {
        @Override
        public void onConnectionStateChanged(int i, int i1) {
            Log.d(TAG, "on rtm ConnectionStateChanged: " + i + ", reason: "+i1);
        }

        @Override
        public void onMessageReceived(RtmMessage rtmMessage, String s) {

        }

        @Override
        public void onImageMessageReceivedFromPeer(RtmImageMessage rtmImageMessage, String s) {

        }

        @Override
        public void onFileMessageReceivedFromPeer(RtmFileMessage rtmFileMessage, String s) {

        }

        @Override
        public void onMediaUploadingProgress(RtmMediaOperationProgress rtmMediaOperationProgress, long l) {

        }

        @Override
        public void onMediaDownloadingProgress(RtmMediaOperationProgress rtmMediaOperationProgress, long l) {

        }

        @Override
        public void onTokenExpired() {

        }

        @Override
        public void onPeersOnlineStatusChanged(Map<String, Integer> map) {

        }
    };

    class NamedChannelListener implements RtmChannelListener {

        private final String channelName;

        NamedChannelListener(String name) {
            channelName = name;
        }

        @Override
        public void onMemberCountUpdated(int i) {

        }

        @Override
        public void onAttributesUpdated(List<RtmChannelAttribute> list) {
            // 业务逻辑:
            // 根据channel, 判断出是哪种类型的更新 1. room属性 2. collection 3. roomList(暂不支持)
            // room属性有一个listener对象, 每一个collection也有一个listener对象, 存放在一个map中
            // map的key是 channel名 或者是collection的classname
            if(mDefaultChannel.equals(channelName)){
                // 如果订阅的scene被删除，通知订阅者
                List<String> foundSceneList = new ArrayList<>();
                for(RtmChannelAttribute attribute : list){
                    if(majorChannels.containsKey(attribute.getKey())){
                        foundSceneList.add(attribute.getKey());
                    }
                }
                for(String scene:majorChannels.keySet()){
                    if(!foundSceneList.contains(scene) && channelListeners.containsKey(scene)){
                        Sync.EventListener callback = eventListeners.get(scene);
                        if(callback!=null){
                            callback.onDeleted(new Attribute(scene, scene));
                        }
                        channelListeners.remove(scene);
                        majorChannels.remove(scene);
                    }
                }
            }
            else if(eventListeners.containsKey(channelName)){
                Sync.EventListener callback = eventListeners.get(channelName);
                if(cachedAttrs.containsKey(channelName)){
                    List<RtmChannelAttribute> cache = cachedAttrs.get(channelName);
                    List<IObject> onlyA = new ArrayList<>();
                    List<IObject> onlyB = new ArrayList<>();
                    List<IObject> both = new ArrayList<>();
                    Map<String, RtmChannelAttribute> temp = new HashMap<>();
                    assert cache != null;
                    for(RtmChannelAttribute item : cache){
                        temp.put(item.getKey(), item);
                    }
                    for(RtmChannelAttribute b : list){
                        if(temp.containsKey(b.getKey())){
                            if(!b.getValue().equals(temp.get(b.getKey()).getValue())){
                                both.add(new Attribute(b.getKey(), b.getValue()));
                            }
                            temp.remove(b.getKey());
                        }
                        else {
                            onlyB.add(new Attribute(b.getKey(), b.getValue()));
                        }
                    }
                    for(RtmChannelAttribute i : temp.values()){
                        onlyA.add(new Attribute(i.getKey(), i.getValue()));
                    }
                    for(IObject i : both){
                        callback.onUpdated(i);
                    }
                    for(IObject i : onlyB){
                        callback.onCreated(i);
                    }
                    for(IObject i : onlyA){
                        callback.onDeleted(i);
                    }
                    cachedAttrs.put(channelName, list);
                }
                else {
                    // 这里是scene property 的回调
                    RtmChannelAttribute rtmChannelAttribute = list.get(0);
                    assert callback != null;
                    callback.onUpdated(new Attribute(rtmChannelAttribute.getKey(), rtmChannelAttribute.getValue()));
                }
            }

        }

        @Override
        public void onMessageReceived(RtmMessage rtmMessage, RtmChannelMember rtmChannelMember) {

        }

        @Override
        public void onImageMessageReceived(RtmImageMessage rtmImageMessage, RtmChannelMember rtmChannelMember) {

        }

        @Override
        public void onFileMessageReceived(RtmFileMessage rtmFileMessage, RtmChannelMember rtmChannelMember) {

        }

        @Override
        public void onMemberJoined(RtmChannelMember rtmChannelMember) {
            Log.d(TAG, "on rtm MemberJoined: "+rtmChannelMember.getUserId());
        }

        @Override
        public void onMemberLeft(RtmChannelMember rtmChannelMember) {

        }
    }

    class Attribute implements IObject {

        private final String key;
        private final String value;

        Attribute(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public <T> T toObject(@NonNull Class<T> valueType) {
            return gson.fromJson(value, valueType);
        }

        @Override
        public String getId() {
            return key;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
