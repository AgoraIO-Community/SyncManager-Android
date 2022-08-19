package io.agora.syncmanager.rtm.impl;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.util.NamedThreadFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLParameters;

import io.agora.common.annotation.NonNull;
import io.agora.syncmanager.rtm.IObject;
import io.agora.syncmanager.rtm.SyncManagerException;
import io.agora.syncmanager.rtm.utils.UUIDUtil;

public class RethinkSyncClient {
    private static final String LOG_TAG = "RethinkSyncClient";
    private static final String SOCKET_HOST_NAME = "rethinkdb-msg.bj2.agoralab.co";
    private static final String SOCKET_URL = "wss://" + SOCKET_HOST_NAME;

    private static final int ERROR_JSON_PARSE = -1001;
    private static final int ERROR_SOCKET_CLOSED = -1002;
    private static final int ERROR_SERVER_DATA = -1003;
    private static final int ERROR_CALLBACK_EXPIRED = -1004;

    private String appId;
    private String channelName;

    private WebSocketClient socketClient;

    private ScheduledExecutorService heartTimer;
    private ScheduledFuture<?> heartFuture;
    private final Object heartTimerLock = new Object();
    private volatile long heartLastPong = 0;

    public final Map<String, CallbackHandler> callbackHandlers = new ConcurrentHashMap<>();

    private final static Gson gson = new Gson();

    public void init(String appId, String channelName, ICallback<Integer> complete) {
        this.appId = appId;
        this.channelName = channelName;
        connect(complete, true);
    }

    public void release() {
        disconnect();
        synchronized (callbackHandlers) {
            callbackHandlers.clear();
        }
    }

    public void add(String channelName,
                    Object data,
                    String objectId,
                    ICallback<Attribute> onSuccess,
                    ICallback<SyncManagerException> onError) {
        String uuid = UUIDUtil.uuid();
        writeData(uuid, channelName, data, objectId, SocketType.send, true,
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

    public void update(String channelName,
                       Object data,
                       String objectId,
                       ICallback<Attribute> onSuccess,
                       ICallback<SyncManagerException> onError
    ) {
        String uuid = UUIDUtil.uuid();
        writeData(uuid, channelName, data, objectId, SocketType.send, false,
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


    public void query(String channelName,
                      ICallback<List<Attribute>> onSuccess,
                      ICallback<SyncManagerException> onError) {
        String uuid = UUIDUtil.uuid();
        writeData(uuid, channelName, null, "", SocketType.query, false,
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
                        if (onSuccess != null) {
                            onSuccess.onCallback(attributes);
                        }
                        return true;
                    }
                });
    }

    public void subscribe(String channelName,
                          ICallback<Attribute> onCreate,
                          ICallback<List<Attribute>> onUpdate,
                          ICallback<List<String>> onDelete,
                          ICallback<SyncManagerException> onError,
                          Object tag) {
        String requestId = UUIDUtil.uuid();
        writeData(requestId, channelName, null, "", SocketType.subscribe, false,
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

    public void unsubscribe(String channelName, Object tag) {


        List<String> keys = new ArrayList<>();
        List<String> channelNames = new ArrayList<>();

        synchronized (callbackHandlers) {
            for (String key : callbackHandlers.keySet()) {
                CallbackHandler handler = callbackHandlers.get(key);
                if (handler != null && !TextUtils.isEmpty(handler.channelName) && handler.channelName.startsWith(channelName)) {
                    keys.add(key);
                }
            }
            if (keys.size() == 0) {
                for (String key : callbackHandlers.keySet()) {
                    CallbackHandler handler = callbackHandlers.get(key);
                    if (handler != null && handler.tag == tag) {
                        keys.add(key);
                    }
                }
            }


            for (String key : keys) {
                CallbackHandler remove = callbackHandlers.remove(key);
                if (remove != null) {
                    channelNames.add(remove.channelName);
                }
            }
        }


        for (String name : channelNames) {
            String requestId = UUIDUtil.uuid();
            writeData(requestId, name, null, "", SocketType.unsubsribe, false, null);
        }
    }

    public void delete(String channelName,
                       List<String> objectIds,
                       ICallback<Void> onSuccess,
                       ICallback<SyncManagerException> onError) {

        String requestId = UUIDUtil.uuid();

        Map<String, Object> socketMsg = new HashMap<>();
        socketMsg.put("appId", appId);
        socketMsg.put("channelName", channelName);
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
            handler.channelName = channelName;
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


    private void connect(ICallback<Integer> complete, boolean lock) {
        disconnect();

        URI msgUri = null;
        try {
            msgUri = new URI(SOCKET_URL);
        } catch (URISyntaxException e) {
            if (complete != null) {
                complete.onCallback(-1);
            }
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);

        socketClient = new WebSocketClient(msgUri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                Log.d(LOG_TAG, "WebSocketClient onOpen status=" + handshakedata.getHttpStatus());
                //startHeartTimer(10);

                synchronized (callbackHandlers) {
                    for (String key : callbackHandlers.keySet()) {
                        CallbackHandler handler = callbackHandlers.get(key);
                        if (handler != null && handler.type == SocketType.subscribe) {
                            writeData(handler.requestId, handler.channelName, null, "", SocketType.subscribe, false, handler);
                        }
                    }
                }

                if (complete != null) {
                    complete.onCallback(0);
                }

                if(latch.getCount() != 0){
                    latch.countDown();
                }
            }

            @Override
            public void onMessage(String message) {
                if (!message.contains("ping")) {
                    Log.d(LOG_TAG, "WebSocketClient onMessage message=" + message);
                }

                try {
                    dealSocketMessage(message);
                } catch (JSONException e) {
                    Log.e(LOG_TAG, "", e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                stopHeartTimer();
                if (code == CloseFrame.ABNORMAL_CLOSE) {
                    RethinkSyncClient.this.connect(complete, false);
                }
            }

            @Override
            public void onError(Exception ex) {
                stopHeartTimer();
                Log.e(LOG_TAG, "", ex);
                if(latch.getCount() != 0){
                    latch.countDown();
                }
                RethinkSyncClient.this.connect(complete, false);
            }

            @Override
            protected void onSetSSLParameters(SSLParameters sslParameters) {
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.N){
                    super.onSetSSLParameters(sslParameters);
                }
            }
        };
        // close webclient inner heart detect
        socketClient.setConnectionLostTimeout(-1);
        socketClient.connect();
        Log.d(LOG_TAG, "WebSocketClient connect url=" + SOCKET_URL);

        if (lock) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                if (complete != null) {
                    complete.onCallback(-11);
                }
            }
        }
    }

    private void disconnect() {
        stopHeartTimer();
        if (socketClient != null) {
            socketClient.closeConnection(CloseFrame.NORMAL, "");
            socketClient = null;
        }
    }

    private void dealSocketMessage(String message) throws JSONException {
        JSONObject dict = new JSONObject(message);
        String action = dict.optString("action");

        if (action.equals(SocketType.ping.name())) {
            synchronized (heartTimerLock){
                heartLastPong = System.nanoTime();
            }
            return;
        }

        String requestId = dict.optString("requestId");
        String channelName = dict.optString("channelName");
        int code = dict.optInt("code");
        String msg = dict.optString("msg");

        List<CallbackHandler> handlers = new ArrayList<>();

        synchronized (callbackHandlers) {
            CallbackHandler cb = callbackHandlers.get(requestId);
            if (cb != null && channelName.equals(cb.channelName) ) {
                handlers.add(cb);
            } else {
                for (String key : callbackHandlers.keySet()) {
                    CallbackHandler h = callbackHandlers.get(key);
                    if (h != null && channelName.equals(h.channelName) && h.type == SocketType.subscribe) {
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

    private void writeData(String requestId,
                           String channelName,
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
                        propsValuesMap.put("objectId", channelName);
                    }
                    propsId = channelName;
                }
                propsValues = gson.toJson(propsValuesMap);


            } catch (JSONException e) {
                if (params instanceof String) {
                    if (TextUtils.isEmpty(objectId)) {
                        propsId = channelName;
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
        socketMsg.put("channelName", channelName);
        socketMsg.put("action", type.name());
        socketMsg.put("requestId", requestId);

        if (!TextUtils.isEmpty(propsValues)) {
            Map<String, Object> props = new HashMap<>();
            props.put(propsId, propsValues);
            socketMsg.put("props", props);
        }

        // remove subscribe data
        if (type == SocketType.subscribe
                || type == SocketType.unsubsribe
                || type == SocketType.query) {
            socketMsg.remove("props");
        }

        if (socketClient != null && socketClient.isOpen()) {
            if (handler != null) {
                handler.channelName = channelName;
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
                        if (ch != null && channelName.equals(ch.channelName)) {
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
                                        params.put("channelName", channelName);
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

    static abstract class CallbackHandler {

        final String requestId;

        final SocketType type;

        // save tag in order to unsubscribe
        final Object tag;

        // use to check if the callback is expired or not
        final long ts;

        String channelName, propsId, propsValue;

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
        send, subscribe, unsubsribe, query, deleteProp, ping;
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
