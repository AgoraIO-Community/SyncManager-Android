package io.agora.syncmanager.rtm.impl;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.util.NamedThreadFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLParameters;

import io.agora.common.annotation.NonNull;
import io.agora.syncmanager.rtm.IObject;
import io.agora.syncmanager.rtm.SyncManagerException;
import io.agora.syncmanager.rtm.utils.UUIDUtil;

public class RethinkSyncClient {
    private static final String LOG_TAG = "RethinkSyncClient";
    private static final String SOCKET_HOST_NAME = "rethinkdb-msg-overseas.agora.io/v2";
    private static final String SOCKET_URL = "wss://" + SOCKET_HOST_NAME;
    private static final String GET_ROOM_LIST_OBJ_TYPE = "room";
    private static final String DELETE_ROOM_OBJ_TYPE = "deleteRoom";

    static final int ERROR_OK = 0;
    static final int ERROR_URL_FORMAT = -1000;
    static final int ERROR_JSON_PARSE = -1001;
    static final int ERROR_SOCKET_CLOSED = -1002;
    static final int ERROR_SERVER_DATA = -1003;
    static final int ERROR_CALLBACK_EXPIRED = -1004;
    static final int ERROR_CONNECT_FAILED = -1999;
    static final int ERROR_ROOM_NOT_EXIST = -2000;

    static final int CONNECT_STATE_CONNECTING = 2001;
    static final int CONNECT_STATE_OPENED = 2002;
    static final int CONNECT_STATE_CLOSED = 2003;
    static final int CONNECT_STATE_FAILED = 2004;


    private static final int CONNECT_CLOSE_RETRY_WAIT_MS = 2000;// 2s
    private static final int CONNECT_CLOSE_RETRY_COUNT = 8 * 60 * 60 * 1000 / CONNECT_CLOSE_RETRY_WAIT_MS; // 8h内重连
    private volatile int connectRetryCount = 0;

    private String appId;
    private String channelName;
    private String sceneName;
    private boolean isRoomOwner = false;

    private WebSocketClient socketClient;

    private ScheduledExecutorService heartTimer;
    private ScheduledFuture<?> heartFuture;
    private final Object heartTimerLock = new Object();
    private volatile long heartLastPong = 0;

    private ScheduledExecutorService syncRoomTimer;
    private ScheduledFuture<?> syncRoomFuture;
    private final Object syncRoomTimerLock = new Object();
    private volatile long syncRoomLastPong = 0;

    public final Map<String, CallbackHandler> callbackHandlers = new ConcurrentHashMap<>();
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    private final static Gson gson = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .registerTypeAdapter(TypeToken.get(JSONObject.class).getType(), new TypeAdapter<JSONObject>() {

                @Override
                public void write(JsonWriter out, JSONObject value) throws IOException {
                    out.jsonValue(value.toString());
                }

                @Override
                public JSONObject read(JsonReader in) throws IOException {
                    return null;
                }
            })
            .enableComplexMapKeySerialization()
            .create();

    private ICallback<Integer> successCallback;
    private ICallback<Integer> failureCallback;
    private ICallback<Integer> connectStateCallback;

    public void init(String appId, String sceneName, ICallback<Integer> success, ICallback<Integer> failure) {
        this.appId = appId;
        this.sceneName = sceneName;
        this.successCallback = success;
        this.failureCallback = failure;
        connect(true);
    }

    public void setConnectStateCallback(ICallback<Integer> callback){
        connectStateCallback = callback;
    }

    public void release() {
        disconnect();
        successCallback = failureCallback = connectStateCallback = null;
        synchronized (callbackHandlers) {
            callbackHandlers.clear();
        }
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public void setIsRoomOwner(boolean isRoomOwner) {
        this.isRoomOwner = isRoomOwner;
    }

    public void add(String roomId,
                    String objType,
                    Object data,
                    String objectId,
                    ICallback<Attribute> onSuccess,
                    ICallback<SyncManagerException> onError) {
        String uuid = UUIDUtil.uuid();
        writeData(roomId, uuid, objType, data, objectId, SocketType.send, true,
                new CallbackHandler(uuid, SocketType.send) {
                    @Override
                    boolean handleResult(int code, String message) {
                        if (code != 0) {
                            if (onError != null) {
                                onError.onCallback(new SyncManagerException(code, message));
                            }
                        } else {
                            if (onSuccess != null) {
                                onSuccess.onCallback(new Attribute(propsId, propsValue));
                            }
                        }
                        return true;
                    }

                    @Override
                    boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes) {
                        return true;
                    }
                });
    }

    public void update(String roomId,
                       String objType,
                       Object data,
                       String objectId,
                       ICallback<Attribute> onSuccess,
                       ICallback<SyncManagerException> onError
    ) {
        String uuid = UUIDUtil.uuid();
        writeData(roomId, uuid, objType, data, objectId, SocketType.send, false,
                new CallbackHandler(uuid, SocketType.send) {
                    @Override
                    boolean handleResult(int code, String message) {
                        if (code != 0) {
                            if (onError != null) {
                                onError.onCallback(new SyncManagerException(code, message));
                            }
                        } else {
                            if (onSuccess != null) {
                                onSuccess.onCallback(new Attribute(propsId, propsValue));
                            }
                        }
                        return true;
                    }

                    @Override
                    boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes) {
                        return true;
                    }
                });
    }


    public void query(String roomId,
                      String objType,
                      ICallback<List<Attribute>> onSuccess,
                      ICallback<SyncManagerException> onError) {
        String uuid = UUIDUtil.uuid();
        writeData(roomId, uuid, objType, null, "", SocketType.query, false,
                new CallbackHandler(uuid, SocketType.query) {
                    @Override
                    boolean handleResult(int code, String message) {
                        if (code != 0) {
                            if (onError != null) {
                                onError.onCallback(new SyncManagerException(code, message));
                            }
                            return true;
                        }
                        return false;
                    }

                    @Override
                    boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes) {
                        // 如果当前房间不存在， 返回错误信息
                        if (objType.equals(GET_ROOM_LIST_OBJ_TYPE) && attributes.size() == 0 && data.optString("id").equals("")) {
                            onError.onCallback(new SyncManagerException(ERROR_ROOM_NOT_EXIST, "This room is already expire!"));
                            return true;
                        }
                        if (onSuccess != null) {
                            onSuccess.onCallback(attributes);
                        }
                        return true;
                    }
                });
    }

    public void subscribe(String roomId,
                          String objType,
                          ICallback<Attribute> onCreate,
                          ICallback<List<Attribute>> onUpdate,
                          ICallback<List<String>> onDelete,
                          ICallback<SyncManagerException> onError,
                          Object tag) {
        String requestId = UUIDUtil.uuid();
        writeData(roomId, requestId, objType, null, "", SocketType.subscribe, false,
                new CallbackHandler(requestId, SocketType.subscribe, tag, -1) {
                    @Override
                    void handleLocalCreate(Attribute attribute) {
                        super.handleLocalCreate(attribute);
                        if (onCreate != null) {
                            onCreate.onCallback(attribute);
                        }
                    }

                    @Override
                    void handleLocalDelete(Attribute attribute) {
                        super.handleLocalDelete(attribute);
                        if (onDelete != null) {
                            onDelete.onCallback(Collections.singletonList(attribute.key));
                        }
                    }

                    @Override
                    boolean handleResult(int code, String message) {
                        if (code != 0 && code != ERROR_SERVER_DATA) {
                            if (onError != null) {
                                onError.onCallback(new SyncManagerException(code, message));
                            }
                            return true;
                        }
                        return false;
                    }

                    @Override
                    boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes) {
                        if (type == SocketType.send) {
                            // TODO workaround: isDeleted
                            if (data.has("isDeleted")) {
                                boolean isDeleted = data.optBoolean("isDeleted");
                                String roomId = data.optString("roomId");
                                if (isDeleted && !TextUtils.isEmpty(roomId) && onDelete != null) {
                                    Log.d(LOG_TAG, "Room is deleted:" + roomId);
                                    if (Objects.equals(channelName, roomId)) {
                                        Log.d(LOG_TAG, "Local Room is deleted:" + roomId);
                                        isRoomOwner = false;
                                    }

                                    List<String> objIds = new ArrayList<>();
                                    objIds.add(roomId);
                                    onDelete.onCallback(objIds);
                                    return false;
                                }
                            }

                            String propsUpdate = data.optString("propsUpdate");
                            if (!TextUtils.isEmpty(propsUpdate) && onUpdate != null) {
                                try {
                                    JSONObject jsonObject = new JSONObject(propsUpdate);
                                    List<Attribute> ret = new ArrayList<>();

                                    JSONArray names = jsonObject.names();
                                    if (names != null) {
                                        for (int i = 0; i < names.length(); i++) {
                                            String key = names.optString(i);
                                            String value = jsonObject.optString(key);
                                            ret.add(new Attribute(key, value));
                                        }
                                    }
                                    onUpdate.onCallback(ret);
                                } catch (JSONException e) {
                                    handleResult(ERROR_JSON_PARSE, "propsUpdate parse error");
                                }
                            }
                        } else if (type == SocketType.deleteProp) {
                            JSONArray propsDel = data.optJSONArray("propsDel");
                            if (propsDel != null && onDelete != null) {
                                List<String> objIds = new ArrayList<>();
                                for (int i = 0; i < propsDel.length(); i++) {
                                    objIds.add(propsDel.optString(i));
                                }
                                onDelete.onCallback(objIds);
                            }
                        }
                        return false;
                    }
                });
    }

    public void unsubscribe(String roomId,
                            String objType,
                            Object tag) {
        List<String> keys = new ArrayList<>();
        List<String> objTypes = new ArrayList<>();

        synchronized (callbackHandlers) {
            for (String key : callbackHandlers.keySet()) {
                CallbackHandler handler = callbackHandlers.get(key);
                if (handler == null
                        || (!TextUtils.isEmpty(handler.objType) && handler.objType.startsWith(objType))
                        || (tag != null && handler.tag == tag)) {
                    keys.add(key);
                }
            }

            for (String key : keys) {
                CallbackHandler remove = callbackHandlers.remove(key);
                if (remove != null) {
                    objTypes.add(remove.objType);
                }
            }
        }


        for (String type : objTypes) {
            String requestId = UUIDUtil.uuid();
            writeData(roomId, requestId, type, null, "", SocketType.unsubscribe, false, null);
        }
    }

    public void delete(String roomId,
                       String objType,
                       List<String> objectIds,
                       ICallback<Void> onSuccess,
                       ICallback<SyncManagerException> onError) {

        String requestId = UUIDUtil.uuid();

        Map<String, Object> socketMsg = new HashMap<>();
        socketMsg.put("appId", appId);
        socketMsg.put("sceneName", sceneName);
        socketMsg.put("roomId", roomId);
        socketMsg.put("objVal", "");
        socketMsg.put("objType", objType);
        socketMsg.put("action", SocketType.deleteProp.name());
        socketMsg.put("requestId", requestId);
        socketMsg.put("props", objectIds);

        if (socketClient != null && socketClient.isOpen()) {
            CallbackHandler handler = new CallbackHandler(requestId, SocketType.deleteProp) {
                @Override
                boolean handleResult(int code, String message) {
                    if (code != 0) {
                        if (onError != null) {
                            onError.onCallback(new SyncManagerException(code, message));
                        }
                    } else {
                        if (onSuccess != null) {
                            onSuccess.onCallback(null);
                        }
                    }
                    return true;
                }

                @Override
                boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes) {
                    return true;
                }
            };
            handler.objType = objType;
            synchronized (callbackHandlers) {
                callbackHandlers.put(requestId, handler);
            }

            String text = gson.toJson(socketMsg);
            Log.d(LOG_TAG, "WebSocketClient send message=" + text);
            socketClient.send(text);
        } else {
            if (onError != null) {
                onError.onCallback(new SyncManagerException(ERROR_SOCKET_CLOSED, "socket client is closed. " + socketClient));
            }
        }
    }

    public void getRoomList(ICallback<List<Attribute>> onSuccess,
                            ICallback<SyncManagerException> onError) {
        String requestId = UUIDUtil.uuid();

        Map<String, Object> socketMsg = new HashMap<>();
        socketMsg.put("appId", appId);
        socketMsg.put("sceneName", sceneName);
        socketMsg.put("action", SocketType.getRoomList.name());
        socketMsg.put("requestId", requestId);

        if (socketClient != null && socketClient.isOpen()) {
            CallbackHandler handler = new CallbackHandler(requestId, SocketType.getRoomList) {
                @Override
                boolean handleResult(int code, String message) {
                    if (code != 0) {
                        if (onError != null) {
                            onError.onCallback(new SyncManagerException(code, message));
                        }
                        return true;
                    }
                    return false;
                }

                @Override
                boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes) {
                    if (onSuccess != null) {
                        onSuccess.onCallback(attributes);
                    }
                    return true;
                }
            };
            handler.objType = GET_ROOM_LIST_OBJ_TYPE;
            synchronized (callbackHandlers) {
                callbackHandlers.put(requestId, handler);
            }

            String text = gson.toJson(socketMsg);
            Log.d(LOG_TAG, "WebSocketClient send message=" + text);
            socketClient.send(text);
        } else {
            if (onError != null) {
                onError.onCallback(new SyncManagerException(ERROR_SOCKET_CLOSED, "socket client is closed. " + socketClient));
            }
        }
    }

    public void deleteRoom(String roomId,
                           ICallback<Void> onSuccess,
                           ICallback<SyncManagerException> onError) {
        isRoomOwner = false;
        String requestId = UUIDUtil.uuid();

        Map<String, Object> socketMsg = new HashMap<>();
        socketMsg.put("appId", appId);
        socketMsg.put("sceneName", sceneName);
        socketMsg.put("roomId", roomId);
        socketMsg.put("action", SocketType.deleteRoom.name());
        socketMsg.put("requestId", requestId);

        if (socketClient != null && socketClient.isOpen()) {
            CallbackHandler handler = new CallbackHandler(requestId, SocketType.deleteRoom) {
                @Override
                boolean handleResult(int code, String message) {
                    if (code != 0) {
                        if (onError != null) {
                            onError.onCallback(new SyncManagerException(code, message));
                        }
                    } else {
                        if (onSuccess != null) {
                            onSuccess.onCallback(null);
                        }
                    }
                    return true;
                }

                @Override
                boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes) {
                    return true;
                }
            };
            handler.objType = DELETE_ROOM_OBJ_TYPE;
            synchronized (callbackHandlers) {
                callbackHandlers.put(requestId, handler);
            }

            String text = gson.toJson(socketMsg);
            Log.d(LOG_TAG, "WebSocketClient send message=" + text);
            socketClient.send(text);
        } else {
            if (onError != null) {
                onError.onCallback(new SyncManagerException(ERROR_SOCKET_CLOSED, "socket client is closed. " + socketClient));
            }
        }
    }

    ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(5);

    private void connect(boolean lock) {
        disconnect();

        URI msgUri = null;
        try {
            msgUri = new URI(SOCKET_URL);
        } catch (URISyntaxException e) {
            if (failureCallback != null) {
                failureCallback.onCallback(ERROR_URL_FORMAT);
            }
            return;
        }

        if (connectStateCallback != null) {
            connectStateCallback.onCallback(CONNECT_STATE_CONNECTING);
        }

        CountDownLatch latch = new CountDownLatch(1);

        socketClient = new WebSocketClient(msgUri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                Log.d(LOG_TAG, "WebSocketClient onOpen status=" + handshakedata.getHttpStatus());
                startHeartTimer(30);
                startSyncRoom(15);

                synchronized (callbackHandlers) {
                    for (String key : callbackHandlers.keySet()) {
                        CallbackHandler handler = callbackHandlers.get(key);
                        if (handler != null && handler.type == SocketType.subscribe && channelName != null) {
                            writeData(channelName, handler.requestId, handler.objType, null, "", SocketType.subscribe, false, handler);
                        }
                    }
                }

                synchronized (reconnectHandler) {
                    connectRetryCount = 0;
                }

                if (successCallback != null) {
                    successCallback.onCallback(ERROR_OK);
                    successCallback = null;
                }

                if (connectStateCallback != null) {
                    connectStateCallback.onCallback(CONNECT_STATE_OPENED);
                }

                if (latch.getCount() != 0) {
                    latch.countDown();
                }
            }

            @Override
            public void onMessage(String message) {
                Log.d(LOG_TAG, "WebSocketClient onMessage message=" + message);

                try {
                    dealSocketMessage(message);
                } catch (JSONException e) {
                    Log.e(LOG_TAG, "", e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                stopHeartTimer();
                stopSyncRoom();
                Log.d(LOG_TAG, "onClose code=" + code + ", reason=" + reason + ", remote=" + remote);
                if (code != CloseFrame.NORMAL) {
                    synchronized (reconnectHandler) {
                        connectRetryCount++;
                        if (connectRetryCount > CONNECT_CLOSE_RETRY_COUNT) {
                            if (failureCallback != null) {
                                failureCallback.onCallback(ERROR_CONNECT_FAILED);
                            }
                            if (connectStateCallback != null) {
                                connectStateCallback.onCallback(CONNECT_STATE_FAILED);
                            }
                            return;
                        }
                        reconnectHandler.removeCallbacksAndMessages(null);
                        reconnectHandler.postDelayed(() -> {
                            Log.d(LOG_TAG, "onClose reconnecting " + connectRetryCount + "...");
                            RethinkSyncClient.this.connect(false);
                        }, CONNECT_CLOSE_RETRY_WAIT_MS);
                    }
                } else {
                    if (connectStateCallback != null) {
                        connectStateCallback.onCallback(CONNECT_STATE_CLOSED);
                    }
                }
            }

            @Override
            public void onError(Exception ex) {
                stopHeartTimer();
                stopSyncRoom();
                Log.e(LOG_TAG, ex.toString());
                if (latch.getCount() != 0) {
                    latch.countDown();
                }
            }

            @Override
            protected void onSetSSLParameters(SSLParameters sslParameters) {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
                    super.onSetSSLParameters(sslParameters);
                }
            }
        };
        // close webclient inner heart detect
        socketClient.setConnectionLostTimeout(-1);
        socketClient.connect();
        Log.d(LOG_TAG, "WebSocketClient connect url=" + SOCKET_URL);

        executor.execute(() -> {
            if (lock) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    if (failureCallback != null) {
                        failureCallback.onCallback(-11);
                    }
                }
            }
        });
    }

    private void disconnect() {
        stopHeartTimer();
        stopSyncRoom();
        synchronized (reconnectHandler) {
            connectRetryCount = 0;
            reconnectHandler.removeCallbacksAndMessages(null);
        }
        if (socketClient != null) {
            socketClient.closeConnection(CloseFrame.NORMAL, "");
            socketClient = null;
        }
    }

    private void dealSocketMessage(String message) throws JSONException {
        JSONObject dict = new JSONObject(message);
        String action = dict.optString("action");
        String objType;
        if (action.equals(SocketType.ping.name())) {
            synchronized (heartTimerLock) {
                heartLastPong = System.nanoTime();
            }
            return;
        } else if (action.equals(SocketType.syncRoom.name())) {
            synchronized (syncRoomTimerLock) {
                syncRoomLastPong = System.nanoTime();
            }
            return;
        } else if (action.equals(SocketType.getRoomList.name())) {
            objType = GET_ROOM_LIST_OBJ_TYPE;
        } else if (action.equals(SocketType.deleteRoom.name())) {
            objType = DELETE_ROOM_OBJ_TYPE;
        } else {
            objType = dict.optString("objType");
        }

        String requestId = dict.optString("requestId");
        int code = dict.optInt("code");
        String msg = dict.optString("msg");

        List<CallbackHandler> handlers = new ArrayList<>();

        synchronized (callbackHandlers) {
            CallbackHandler cb = callbackHandlers.get(requestId);
            if (cb != null && objType.equals(cb.objType)) {
                handlers.add(cb);
            } else {
                for (String key : callbackHandlers.keySet()) {
                    CallbackHandler h = callbackHandlers.get(key);
                    if (h != null && objType.equals(h.objType) && h.type == SocketType.subscribe) {
                        handlers.add(h);
                    }
                }
            }
        }

        for (CallbackHandler handler : handlers) {

            if (handler.handleResult(code, msg)) {
                synchronized (callbackHandlers) {
                    callbackHandlers.remove(handler.requestId);
                }
                continue;
            }
            if (handler.isExpired() && handler.handleResult(ERROR_CALLBACK_EXPIRED, "callback has been expired")) {
                synchronized (callbackHandlers) {
                    callbackHandlers.remove(handler.requestId);
                }
                continue;
            }

            if (objType.equals(GET_ROOM_LIST_OBJ_TYPE) && action.equals(SocketType.getRoomList.name())) {
                JSONArray data = dict.optJSONArray("data");
                if (data == null) {
                    if (handler.handleResult(ERROR_SERVER_DATA, "The room list is empty.")) {
                        synchronized (callbackHandlers) {
                            callbackHandlers.remove(handler.requestId);
                        }
                    }
                    continue;
                }

                JSONArray props = new JSONArray();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject room = data.getJSONObject(i);
                    JSONObject prop = room.optJSONObject("props");
                    if (props != null) {
                        props.put(prop);
                    }
                }

                List<Attribute> attributes = new ArrayList<>();
                for (int i = 0; i < props.length(); i++) {
                    JSONObject prop = props.getJSONObject(i);
                    Iterator<String> keys = prop.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = prop.optString(key);
                        if (!TextUtils.isEmpty(value)) {
                            attributes.add(new Attribute(key, value));
                        }
                    }
                }

                if (handler.handleAttrs(SocketType.send, new JSONObject(), attributes)) {
                    synchronized (callbackHandlers) {
                        callbackHandlers.remove(handler.requestId);
                    }
                }

            } else {
                JSONObject data = dict.optJSONObject("data");

                if (data == null) {
                    if (handler.handleResult(ERROR_SERVER_DATA, "server not data return. msg: " + message)) {
                        synchronized (callbackHandlers) {
                            callbackHandlers.remove(handler.requestId);
                        }
                    }
                    continue;
                }

                JSONObject props = data.optJSONObject("props");
                if (props == null) {
                    if (handler.handleResult(ERROR_SERVER_DATA, "server not data props return. msg: " + message)) {
                        synchronized (callbackHandlers) {
                            callbackHandlers.remove(handler.requestId);
                        }
                    }
                    continue;
                }

                List<Attribute> attributes = new ArrayList<>();
                Iterator<String> keys = props.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = props.optString(key);
                    if (!TextUtils.isEmpty(value)) {
                        attributes.add(new Attribute(key, value));
                    }
                }

                String realAction = data.optString("action");


                SocketType type = null;
                try {
                    type = SocketType.valueOf(realAction);
                } catch (IllegalArgumentException e) {
                    type = SocketType.send;
                }

                if (handler.handleAttrs(type, data, attributes)) {
                    synchronized (callbackHandlers) {
                        callbackHandlers.remove(handler.requestId);
                    }
                }
            }
        }
    }

    private void writeData(String roomId,
                           String requestId,
                           String objType,
                           Object params,
                           String objectId,
                           SocketType type,
                           boolean isAdd,
                           CallbackHandler handler) {
        String propsId = objectId;
        String propsValues = "";

        if (params != null) {
            try {
                Map<String, Object> propsValuesMap = new HashMap<>();
                JSONObject jo = new JSONObject(gson.toJson(params));
                Iterator<String> keys = jo.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    propsValuesMap.put(key, jo.opt(key));
                }

                if (TextUtils.isEmpty(objectId)) {
                    boolean contain = propsValuesMap.containsKey("objectId");
                    if (!contain) {
                        propsValuesMap.put("objectId", objType);
                    }
                    propsId = objType;
                }
                propsValues = gson.toJson(propsValuesMap);


            } catch (JSONException e) {
                if (params instanceof String) {
                    if (TextUtils.isEmpty(objectId)) {
                        propsId = objType;
                    }
                    propsValues = (String) params;
                } else {
                    if (handler != null) {
                        handler.handleResult(ERROR_JSON_PARSE, "Json parse error, params=" + params);
                    }
                    return;
                }
            }
        }

        Map<String, Object> socketMsg = new HashMap<>();
        socketMsg.put("appId", appId);
        socketMsg.put("sceneName", sceneName);
        socketMsg.put("roomId", roomId);
        socketMsg.put("objVal", "");
        socketMsg.put("objType", objType);
        socketMsg.put("action", type.name());
        socketMsg.put("requestId", requestId);

        if (!TextUtils.isEmpty(propsValues)) {
            Map<String, Object> props = new HashMap<>();
            props.put(propsId, propsValues);
            socketMsg.put("props", props);
        }

        // remove subscribe data
        if (type == SocketType.subscribe
                || type == SocketType.unsubscribe
                || type == SocketType.query) {
            socketMsg.remove("props");
        }

        if (socketClient != null && socketClient.isOpen()) {
            if (handler != null) {
                handler.objType = objType;
                handler.propsId = propsId;
                handler.propsValue = propsValues;
                synchronized (callbackHandlers) {
                    callbackHandlers.put(requestId, handler);
                }
            }
            String text = gson.toJson(socketMsg);
            Log.d(LOG_TAG, "WebSocketClient send message=" + text);
            socketClient.send(text);

            if (isAdd) {
                synchronized (callbackHandlers) {
                    for (String key : callbackHandlers.keySet()) {
                        CallbackHandler ch = callbackHandlers.get(key);
                        if (ch != null && objType.equals(ch.objType)) {
                            ch.handleLocalCreate(new Attribute(propsId, propsValues));
                        }
                    }
                }
            }

        } else {
            if (handler != null) {
                handler.handleResult(ERROR_SOCKET_CLOSED, "socketClient status error : " + socketClient);
            }
        }


    }

    private void startHeartTimer(long intervalS) {
        long connectionLostTimeout = TimeUnit.SECONDS.toNanos(intervalS);
        synchronized (heartTimerLock) {
            stopHeartTimer();
            heartLastPong = System.nanoTime();
            heartTimer = Executors
                    .newSingleThreadScheduledExecutor(new NamedThreadFactory("connectionLostChecker"));
            heartFuture = heartTimer
                    .scheduleAtFixedRate(
                            () -> {
                                long minimumPongTime;
                                synchronized (heartTimerLock) {
                                    minimumPongTime = (long) (System.nanoTime() - (connectionLostTimeout * 1.5));
                                }
                                if (socketClient == null) {
                                    return;
                                }
                                if (heartLastPong < minimumPongTime) {
                                    socketClient.closeConnection(CloseFrame.ABNORMAL_CLOSE,
                                            "The connection was closed because the other endpoint did not respond with a pong in time.");
                                } else {
                                    if (socketClient.isOpen()) {
                                        HashMap<Object, Object> params = new HashMap<>();
                                        params.put("action", SocketType.ping.name());
                                        params.put("appId", appId);
                                        params.put("roomId", channelName);
                                        params.put("sceneName", sceneName);
                                        params.put("requestId", UUID.randomUUID().toString());

                                        String text = gson.toJson(params);
                                        Log.d(LOG_TAG, "WebSocketClient send message=" + text);
                                        socketClient.send(text);
                                    } else {
                                        Log.e(LOG_TAG, "Trying to ping a non open connection: {}");
                                    }
                                }
                            },
                            connectionLostTimeout,
                            connectionLostTimeout,
                            TimeUnit.NANOSECONDS);

        }
    }

    private void stopHeartTimer() {
        if (heartTimer != null) {
            heartTimer.shutdownNow();
            heartTimer = null;
        }
        if (heartFuture != null) {
            heartFuture.cancel(false);
            heartFuture = null;
        }
    }

    private void startSyncRoom(long intervalS) {
        long connectionLostTimeout = TimeUnit.SECONDS.toNanos(intervalS);
        synchronized (syncRoomTimerLock) {
            stopSyncRoom();
            syncRoomLastPong = System.nanoTime();
            syncRoomTimer = Executors
                    .newSingleThreadScheduledExecutor(new NamedThreadFactory("connectionLostChecker_syncRoom"));
            syncRoomFuture = syncRoomTimer
                    .scheduleAtFixedRate(
                            () -> {
                                if (socketClient == null || (!isRoomOwner) || (channelName == null)) {
                                    return;
                                }
                                if (socketClient.isOpen()) {
                                    HashMap<Object, Object> params = new HashMap<>();
                                    params.put("action", SocketType.syncRoom.name());
                                    params.put("appId", appId);
                                    params.put("roomId", channelName);
                                    params.put("sceneName", sceneName);
                                    params.put("requestId", UUID.randomUUID().toString());

                                    String text = gson.toJson(params);
                                    Log.d(LOG_TAG, "WebSocketClient send message=" + text);
                                    socketClient.send(text);
                                }
                            },
                            connectionLostTimeout,
                            connectionLostTimeout,
                            TimeUnit.NANOSECONDS);

        }
    }

    private void stopSyncRoom() {
        if (syncRoomTimer != null) {
            syncRoomTimer.shutdownNow();
            syncRoomTimer = null;
        }
        if (syncRoomFuture != null) {
            syncRoomFuture.cancel(false);
            syncRoomFuture = null;
        }
    }

    static abstract class CallbackHandler {

        final String requestId;

        final SocketType type;

        // save tag in order to unsubscribe
        final Object tag;

        // use to check if the callback is expired or not
        final long ts;

        String objType, propsId, propsValue;

        CallbackHandler(String requestId, SocketType type) {
            this(requestId, type, null);
        }

        CallbackHandler(String requestId, SocketType type, Object tag) {
            this(requestId, type, tag, System.currentTimeMillis());
        }

        CallbackHandler(String requestId, SocketType type, Object tag, long ts) {
            this.requestId = requestId;
            this.type = type;
            this.tag = tag;
            this.ts = ts;
        }

        abstract boolean handleResult(int code, String message);

        abstract boolean handleAttrs(SocketType type, JSONObject data, List<Attribute> attributes);

        void handleLocalCreate(Attribute attribute) {
        }

        void handleLocalDelete(Attribute attribute) {
        }

        boolean isExpired() {
            return ts > 0 && (System.currentTimeMillis() - ts) > 2 * 60 * 1000; // 2min
        }
    }

    interface ICallback<T> {
        void onCallback(T ret);
    }

    abstract static class NameCallback<T> implements ICallback<T> {
        private String objectId;
    }

    enum SocketType {
        send, subscribe, unsubscribe, query, deleteProp, ping, syncRoom, getRoomList, deleteRoom;
    }

    static class Attribute implements IObject {

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
