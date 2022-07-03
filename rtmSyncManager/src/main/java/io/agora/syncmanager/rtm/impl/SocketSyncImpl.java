package io.agora.syncmanager.rtm.impl;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
import io.agora.syncmanager.rtm.CollectionReference;
import io.agora.syncmanager.rtm.DocumentReference;
import io.agora.syncmanager.rtm.IObject;
import io.agora.syncmanager.rtm.ISyncManager;
import io.agora.syncmanager.rtm.Scene;
import io.agora.syncmanager.rtm.SceneReference;
import io.agora.syncmanager.rtm.Sync;
import io.agora.syncmanager.rtm.SyncManagerException;

public class SocketSyncImpl implements ISyncManager {

    private String appId;
    private String token;
    private static final String APP_ID = "appid";
    private static final String TOKEN = "token";
    private static final String DEFAULT_CHANNEL_NAME_PARAM = "defaultChannel";
    private String mDefaultChannel;
    private SocketClient client;

    private Map<String, String> majorChannels = new ConcurrentHashMap<>();
    private Map<String, Sync.EventListener> eventListeners = new ConcurrentHashMap<>();
    private Map<String, NamedChannelListener> channelListeners = new ConcurrentHashMap<>();
    private Map<String, List<SocketClient.SocketAttribute>> cachedAttrs = new ConcurrentHashMap<>();

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

    public SocketSyncImpl(Context context, Map<String, String> params, Sync.Callback callback) {
        appId = params.get(APP_ID);
        token = params.get(TOKEN);
        mDefaultChannel = params.get(DEFAULT_CHANNEL_NAME_PARAM);
        assert appId != null;
        assert mDefaultChannel != null;
        client = new SocketClient(appId);
        client.connect(
                open -> {
                    if (callback != null) {
                        callback.onSuccess();
                    }
                }, null, error -> {
                    if (callback != null) {
                        callback.onFail(new SyncManagerException(error));
                    }
                });
    }

    @Override
    public void destroy() {
        this.eventListeners.clear();
        this.majorChannels.clear();
        this.channelListeners.clear();
        this.cachedAttrs.clear();
        if(client != null){
            client.close();
            client = null;
        }
    }

    @Override
    public void createScene(@NonNull Scene room,@Nullable Sync.Callback callback) {
        String channelName = room.getId();
        assert channelName != null;

        SocketClient.SocketAttribute attribute = new SocketClient.SocketAttribute();
        attribute.key = channelName;
        attribute.value = room.toJson();
        List<SocketClient.SocketAttribute> list = new ArrayList<>();
        list.add(attribute);
        int ret = client.addOrUpdateProp(mDefaultChannel, list);
        if (callback != null) {
            if(ret == 0){
                callback.onSuccess();
            }else{
                callback.onFail(new SyncManagerException(ret, "client error"));
            }
        }
    }

    @Override
    public void joinScene(@NonNull String sceneId,@Nullable Sync.JoinSceneCallback callback) {
        if(!channelListeners.containsKey(mDefaultChannel)){
            NamedChannelListener defaultListener = new NamedChannelListener(mDefaultChannel);
            int ret = client.subscribe(mDefaultChannel, defaultListener);
            if(ret == 0){
                channelListeners.put(mDefaultChannel, defaultListener);
            }else{
                if (callback != null) {
                    callback.onFail(new SyncManagerException(-1, "subscribe channel failed!\n" + ret));
                }
            }
        }
        NamedChannelListener listener = new NamedChannelListener(sceneId);
        int ret = client.subscribe(sceneId, listener);
        if (ret == 0) {
            majorChannels.put(sceneId, sceneId);
            channelListeners.put(sceneId, listener);
            if (callback != null)
                callback.onSuccess(new SceneReference(SocketSyncImpl.this, sceneId, sceneId));
        } else {
            if (callback != null) {
                callback.onFail(new SyncManagerException(-1, "subscribe channel failed!\n" + ret));
            }
        }
    }

    @Override
    public void getScenes(Sync.DataListCallback callback) {
        int ret = client.getProps(mDefaultChannel, attributes -> {
            if(callback != null){
                List<IObject> list = new ArrayList<>();
                if (attributes != null && attributes.size() > 0) {
                    for (SocketClient.SocketAttribute attribute : attributes) {
                        list.add(new Attribute(attribute.key, String.valueOf(attribute.value)));
                    }
                }
                callback.onSuccess(list);
            }
        });
        if(ret != 0 && callback != null){
            callback.onFail(new SyncManagerException(ret, "error"));
        }
    }

    @Override
    public void get(DocumentReference reference, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();
            int ret = client.getProps(channel, attributes -> {
                if(callback != null){
                    if (attributes != null && attributes.size() > 0) {
                        SocketClient.SocketAttribute attribute = attributes.get(0);
                        callback.onSuccess(new Attribute(attribute.key, String.valueOf(attribute.value)));
                    } else {
                        callback.onFail(new SyncManagerException(-1, "empty attributes"));
                    }
                }
            });
            if(ret != 0 && callback != null){
                callback.onFail(new SyncManagerException(-1, "getProps error"));
            }
        } else {
            callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void get(DocumentReference reference, String key, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
            int ret = client.getProps(channel, attributes -> {
                if(callback != null){
                    if (attributes != null && attributes.size() > 0) {
                        SocketClient.SocketAttribute attribute = attributes.get(0);
                        callback.onSuccess(new Attribute(attribute.key, String.valueOf(attribute.value)));
                    } else {
                        callback.onFail(new SyncManagerException(-1, "empty attributes"));
                    }
                }
            });
            if(ret != 0 && callback != null){
                callback.onFail(new SyncManagerException(-1, "getProps error"));
            }
        } else {
            callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void get(CollectionReference reference, Sync.DataListCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
            int ret = client.getProps(channel, attributes -> {
                if (attributes != null && attributes.size() > 0) {
                    cachedAttrs.put(channel, attributes);
                    List<IObject> res = new ArrayList<>();
                    for (SocketClient.SocketAttribute attribute : attributes) {
                        res.add(new Attribute(attribute.key, String.valueOf(attribute.value)));
                    }
                    callback.onSuccess(res);
                } else {
                    callback.onFail(new SyncManagerException(-1, "empty attributes"));
                }
            });
            if(ret != 0){
                callback.onFail(new SyncManagerException(ret, "getProps error"));
                return;
            }

            NamedChannelListener listener;
            if (channelListeners.containsKey(channel)) {
                listener = channelListeners.get(channel);
            } else {
                listener = new NamedChannelListener(channel);
                channelListeners.put(channel, listener);
            }
            client.subscribe(channel, listener);
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
                client.subscribe(channel, listener);
                channelListeners.put(channel, listener);
            }
            SocketClient.SocketAttribute attribute = new SocketClient.SocketAttribute();
            attribute.key = uuid();
            String json = gson.toJson(data);
            attribute.value = json;
            List<SocketClient.SocketAttribute> list = new ArrayList<>();
            list.add(attribute);
            int ret = client.addOrUpdateProp(channel, list);
            if(ret == 0){
                List<SocketClient.SocketAttribute> rtmChannelAttributes = cachedAttrs.get(channel);
                if(rtmChannelAttributes != null){
                    rtmChannelAttributes.add(attribute);
                }
                else{
                    rtmChannelAttributes = new ArrayList<>();
                    rtmChannelAttributes.add(attribute);
                    cachedAttrs.put(channel, rtmChannelAttributes);
                }
                IObject item = new Attribute(attribute.key, json);
                Sync.EventListener listener = eventListeners.get(channel);
                if(listener !=null){
                    listener.onCreated(item);
                }
                if(callback!=null) callback.onSuccess(item);
            }else{
                if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
            }
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
                int ret = client.deleteProps(mDefaultChannel, list);
                if(ret == 0){
                    if(callback!=null) callback.onSuccess();
                }else{
                    if(callback!=null) callback.onFail(new SyncManagerException(-1, "remove scene failed!"));
                }
                unsubscribe(majorChannel, null);
            }
            else{
                // remove specific property
                String channel = majorChannel + reference.getId();
                List<String> list = new ArrayList<>();
                List<SocketClient.SocketAttribute> attrs = cachedAttrs.get(channel);
                if(attrs!=null){
                    for(SocketClient.SocketAttribute item : attrs){
                        if(item.value.contains(reference.getId())){
                            list.add(item.key);
                        }
                    }
                    int ret = client.deleteProps(channel, list);
                    if(ret == 0){
                        List<SocketClient.SocketAttribute> rtmChannelAttributes = cachedAttrs.get(channel);
                        if(rtmChannelAttributes != null){
                            for(SocketClient.SocketAttribute item : rtmChannelAttributes){
                                if(item.key.equals(reference.getId())){
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
                    }else{
                        if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
                    }
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
            int ret = client.deleteProps(channel, list);
            if(ret == 0){
                callback.onSuccess();
            }else{
                callback.onFail(new SyncManagerException(-1, "delete collection element failed!"));
            }
        }
    }

    @Override
    public void update(CollectionReference reference, String id, Object data, Sync.Callback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getKey().equals(majorChannel) ? majorChannel : majorChannel + reference.getKey();
            SocketClient.SocketAttribute attribute = new SocketClient.SocketAttribute();
            attribute.key = id;
            String json = gson.toJson(data);
            attribute.value = json;
            List<SocketClient.SocketAttribute> list = new ArrayList<>();
            list.add(attribute);
            int ret = client.addOrUpdateProp(channel, list);
            if(ret == 0){
                callback.onSuccess();
            }else{
                callback.onFail(new SyncManagerException(-1, "update collection element failed!"));
            }
        }
    }

    @Override
    public void update(DocumentReference reference, String key, Object data, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel + key : majorChannel + reference.getId();
            SocketClient.SocketAttribute attribute = new SocketClient.SocketAttribute();
            attribute.key = channel;
            String json = gson.toJson(data);
            attribute.value = json;
            List<SocketClient.SocketAttribute> list = new ArrayList<>();
            list.add(attribute);
            int ret = client.addOrUpdateProp(channel, list);
            if(ret == 0){
                if(callback!=null) callback.onSuccess(new Attribute(attribute.key, attribute.value));
            }else{
                if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
            }
        } else {
            if(callback!=null) callback.onFail(new SyncManagerException(-1, "yet join channel"));
        }
    }

    @Override
    public void update(DocumentReference reference, HashMap<String, Object> data, Sync.DataItemCallback callback) {
        if (this.majorChannels.containsKey(reference.getParent())) {
            String majorChannel = reference.getParent();
            String channel = reference.getId().equals(majorChannel) ? majorChannel : majorChannel + reference.getId();
            SocketClient.SocketAttribute attribute = new SocketClient.SocketAttribute();
            attribute.key = reference.getId();
            String json = gson.toJson(data);
            attribute.value = json;
            List<SocketClient.SocketAttribute> list = new ArrayList<>();
            list.add(attribute);
            int ret = client.addOrUpdateProp(channel, list);
            if(ret == 0){
                if(callback!=null) callback.onSuccess(new Attribute(reference.getId(), json));
            }else{
                if(callback!=null) callback.onFail(new SyncManagerException(-1, "add attribute failed!"));
            }
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
                client.subscribe(channel, namedChannelListener);
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
                client.unSubscribe(s);
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

    class NamedChannelListener implements SocketClient.ResultListListener<SocketClient.SocketAttribute> {

        private final String channelName;

        NamedChannelListener(String name) {
            channelName = name;
        }

        @Override
        public void onResult(List<SocketClient.SocketAttribute> list) {
            // 业务逻辑:
            // 根据channel, 判断出是哪种类型的更新 1. room属性 2. collection 3. roomList(暂不支持)
            // room属性有一个listener对象, 每一个collection也有一个listener对象, 存放在一个map中
            // map的key是 channel名 或者是collection的classname
            if(mDefaultChannel.equals(channelName)){
                // 如果订阅的scene被删除，通知订阅者
                List<String> foundSceneList = new ArrayList<>();
                for(SocketClient.SocketAttribute attribute : list){
                    if(majorChannels.containsKey(attribute.key)){
                        foundSceneList.add(attribute.key);
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
                    List<SocketClient.SocketAttribute> cache = cachedAttrs.get(channelName);
                    List<IObject> onlyA = new ArrayList<>();
                    List<IObject> onlyB = new ArrayList<>();
                    List<IObject> both = new ArrayList<>();
                    Map<String, SocketClient.SocketAttribute> temp = new HashMap<>();
                    assert cache != null;
                    for(SocketClient.SocketAttribute item : cache){
                        temp.put(item.key, item);
                    }
                    for(SocketClient.SocketAttribute b : list){
                        if(temp.containsKey(b.key)){
                            if(!b.value.equals(temp.get(b.key).value)){
                                both.add(new Attribute(b.key, String.valueOf(b.value)));
                            }
                            temp.remove(b.key);
                        }
                        else {
                            onlyB.add(new Attribute(b.key, String.valueOf(b.value)));
                        }
                    }
                    for(SocketClient.SocketAttribute i : temp.values()){
                        onlyA.add(new Attribute(i.key, String.valueOf(i.value)));
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
                    SocketClient.SocketAttribute rtmChannelAttribute = list.get(0);
                    assert callback != null;
                    callback.onUpdated(new Attribute(rtmChannelAttribute.key, String.valueOf(rtmChannelAttribute.value)));
                }
            }
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
