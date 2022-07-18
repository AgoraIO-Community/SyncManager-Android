package io.agora.syncmanager.rtm.impl;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.util.NamedThreadFactory;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.agora.common.annotation.NonNull;
import io.agora.syncmanager.rtm.IObject;
import io.agora.syncmanager.rtm.SyncManagerException;

public class RethinkSyncClient {
    private static final String LOG_TAG = "RethinkSyncClient";
    private static final String SOCKET_URL = "wss://test-rethinkdb-msg.bj2.agoralab.co";

    private String appId;
    private String channelName;

    private WebSocketClient socketClient;

    private ScheduledExecutorService heartTimer;
    private ScheduledFuture<?> heartFuture;
    private final Object heartTimerLock = new Object();
    private volatile long heartLastPong = 0;

    // callback map : key=channelName, value=ICallback
    public final Map<String, ICallback<List<IObject>>> onSuccessCallbacks = new LinkedHashMap<>();
    public final Map<String, ICallback<Void>> onSuccessCallbacksVoid = new LinkedHashMap<>();
    public final Map<String, ICallback<IObject>> onSuccessCallbacksObj = new LinkedHashMap<>();
    public final Map<String, ICallback<SyncManagerException>> onFailCallbacks = new LinkedHashMap<>();

    public final Map<String, ICallback<IObject>> onCreateCallbacks = new LinkedHashMap<>();
    public final Map<String, ICallback<IObject>> onUpdateCallbacks = new LinkedHashMap<>();
    public final Map<String, ICallback<IObject>> onDeletedCallbacks = new LinkedHashMap<>();

    private final Gson gson = new Gson();


    public void init(String appId, String channelName, ICallback<Integer> complete) {
        this.appId = appId;
        this.channelName = channelName;
        connect(complete);
    }

    public void release() {
        disconnect();
        onSuccessCallbacks.clear();
        onSuccessCallbacksVoid.clear();
        onFailCallbacks.clear();
        onCreateCallbacks.clear();
        onUpdateCallbacks.clear();
        onDeletedCallbacks.clear();
    }

    public void add(String channelName, Object data, String objectId) {
        writeData(channelName, data, objectId, SocketType.send, true);
    }

    public void delete(String channelName, List<String> objectIds) {
        Map<String, Object> socketMsg = new HashMap<>();
        socketMsg.put("appId", appId);
        socketMsg.put("channelName", channelName);
        socketMsg.put("action", SocketType.deleteProp.name());
        socketMsg.put("requestId", UUID.randomUUID().toString());
        socketMsg.put("props", objectIds);
        if (socketClient != null) {
            String text = gson.toJson(socketMsg);
            Log.d(LOG_TAG, "WebSocketClient send message=" + text);
            socketClient.send(text);
        }
    }

    public void update(String channelName, Object data, String objectId) {
        writeData(channelName, data, objectId, SocketType.send, false);
    }

    public void query(String channelName) {
        writeData(channelName, null, "", SocketType.query, false);
    }

    public void subscribe(String channelName) {
        writeData(channelName, null, "", SocketType.subscribe, false);
    }

    public void unsubscribe(String channelName) {
        writeData(channelName, null, "", SocketType.unsubsribe, false);
    }


    private void connect(ICallback<Integer> complete) {
        asset(!TextUtils.isEmpty(appId) && !TextUtils.isEmpty(channelName));
        asset(socketClient == null);

        URI msgUri = null;
        try {
            msgUri = new URI(SOCKET_URL);
        } catch (URISyntaxException e) {
            if (complete != null) {
                complete.onCallback(-1);
            }
            return;
        }
        socketClient = new WebSocketClient(msgUri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                Log.d(LOG_TAG, "WebSocketClient onOpen status=" + handshakedata.getHttpStatus());
                startHeartTimer(10);

                if (complete != null) {
                    complete.onCallback(0);
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
                    if (complete != null) {
                        complete.onCallback(-4);
                    }
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (code == CloseFrame.ABNORMAL_CLOSE) {
                    if (complete != null) {
                        complete.onCallback(-3);
                    }
                }
            }

            @Override
            public void onError(Exception ex) {
                if (complete != null) {
                    complete.onCallback(-2);
                }
            }
        };
        // close webclient inner heart detect
        socketClient.setConnectionLostTimeout(-1);
        socketClient.connect();
        Log.d(LOG_TAG, "WebSocketClient connect url=" + SOCKET_URL);
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

        String requestId = dict.optString("requestId");
        long ts = dict.optLong("ts");

        int code = dict.optInt("code");
        String msg = dict.optString("msg");

        String action = dict.optString("action");
        String channelName = dict.optString("channelName");

        JSONObject data = dict.optJSONObject("data");

        if (action.equals(SocketType.ping.name())) {
            heartLastPong = System.nanoTime();
            return;
        }

        if (code != 0) {
            ICallback<SyncManagerException> callback = onFailCallbacks.get(channelName);
            if (callback != null) {
                callback.onCallback(new SyncManagerException(code, msg));
            }
            return;
        }


        if (data == null) {
            return;
        }
        JSONObject props = data.optJSONObject("props");

        List<Attribute> attributes = new ArrayList<>();

        if (props != null) {
            Iterator<String> keys = props.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = props.optString(key);
                if (!TextUtils.isEmpty(value)) {
                    attributes.add(new Attribute(key, value));
                }
            }
        }

        String realAction = data.optString("action");
        if (SocketType.subscribe.name().equals(action)) {
            if (SocketType.send.name().equals(realAction)) {
                ICallback<IObject> cb = onUpdateCallbacks.get(channelName);
                if (cb != null) {
                    for (Attribute attribute : attributes) {
                        cb.onCallback(attribute);
                    }
                }
            }
            if (SocketType.deleteProp.name().equals(realAction)) {
                ICallback<IObject> cb = onDeletedCallbacks.get(channelName);
                if (cb != null) {
                    if (attributes.size() == 0) {
                        cb.onCallback(new Attribute("", ""));
                    } else {
                        for (Attribute attribute : attributes) {
                            cb.onCallback(attribute);
                        }
                    }
                }

            }
        } else {
            ICallback<List<IObject>> cb = onSuccessCallbacks.get(channelName);
            if (cb != null) {
                cb.onCallback(new ArrayList<>(attributes));
            }
            ICallback<Void> cbVoid = onSuccessCallbacksVoid.get(channelName);
            if (cbVoid != null) {
                cbVoid.onCallback(null);
            }
            ICallback<IObject> cbOjb = onSuccessCallbacksObj.get(channelName);
            if (cbOjb != null && attributes.size() > 0) {
                cbOjb.onCallback(attributes.get(0));
            }
        }
    }

    private void writeData(String channelName,
                           Object params,
                           String objectId,
                           SocketType type,
                           boolean isAdd) {
        if (socketClient == null) {
            return;
        }
        String propsId = objectId;
        String propsValues = "";

        if(params != null){
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
                    ICallback<SyncManagerException> cb = onFailCallbacks.get(channelName);
                    if(cb != null){
                        cb.onCallback(new SyncManagerException(-999, "Json parse error, params=" + params.toString()));
                    }
                    return;
                }
            }
        }

        Map<String, Object> socketMsg = new HashMap<>();
        socketMsg.put("appId", appId);
        socketMsg.put("channelName", channelName);
        socketMsg.put("action", type.name());
        socketMsg.put("requestId", UUID.randomUUID().toString());

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

        // notify attribute add
        if (isAdd) {
            ICallback<IObject> callback = onCreateCallbacks.get(channelName);
            if (callback != null) {
                callback.onCallback(new Attribute(propsId, gson.toJson(propsValues)));
            }
        }

        if (socketClient != null) {
            String text = gson.toJson(socketMsg);
            Log.d(LOG_TAG, "WebSocketClient send message=" + text);
            socketClient.send(text);
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
                                        params.put("request", UUID.randomUUID().toString());

                                        String text = gson.toJson(params);
                                        //Log.d(LOG_TAG, "WebSocketClient send message=" + text);
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

    private static void asset(boolean condition) {
        if (!condition) {
            try {
                throw new RuntimeException("asset error");
            } catch (Exception e) {
                Log.e(LOG_TAG, "", e);
            }
        }
    }

    interface ICallback<T> {
        void onCallback(T ret);
    }

    enum SocketType {
        send, subscribe, unsubsribe, query, deleteProp, ping,
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
