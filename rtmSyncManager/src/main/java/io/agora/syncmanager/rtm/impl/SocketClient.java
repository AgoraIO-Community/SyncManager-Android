package io.agora.syncmanager.rtm.impl;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
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
import java.util.Set;

public class SocketClient {
    private static final String TAG = "SocketClient";
    private static final String SOCKET_MSG_URL = "ws://114.236.137.88:8199/ws";
    private static final String SOCKET_SUBSCRIBE_URL = "ws://114.236.137.88:8199/ws/subscribe";

    private final String appId;

    private WebSocketClient mMsgClient;
    private final Map<String, WebSocketClient> mSubscribeClients = new HashMap<>();
    private final Map<String, List<Runnable>> mSubscribePendingRun = new HashMap<>();
    private final ResultListener<String> mMsgListener = this::parseMsgString;
    private final ResultListener<String> mSubscribeListener = this::parseSubscribeString;

    private final Map<String, ResultListListener<SocketAttribute>> attributeListenerMap = new HashMap<>();

    SocketClient(String appId) {
        this.appId = appId;
    }

    void connect(ResultListener<WebSocketClient> onOpen, ResultListener<String> onClose, ResultListener<Exception> onError) {
        mMsgClient = connectWebSocket(
                SOCKET_MSG_URL,
                onOpen,
                mMsgListener,
                onClose,
                onError
        );
    }

    void close() {
        if (mMsgClient != null) {
            mMsgClient.close();
            mMsgClient = null;
        }
        Set<String> keySet = mSubscribeClients.keySet();
        for (String key : keySet) {
            WebSocketClient client = mSubscribeClients.get(key);
            if (client != null) {
                client.close();
                mSubscribeClients.remove(key);
            }
        }
        mSubscribeClients.clear();
        mSubscribePendingRun.clear();
    }

    int addOrUpdateProp(String channelName, List<SocketAttribute> attributes) {
        if (mMsgClient != null) {
            MessageBuilder mb = new MessageBuilder()
                    .setAppId(appId)
                    .setChannelName(channelName);
            if (attributes != null) {
                for (SocketAttribute attribute : attributes) {
                    mb.addProp(attribute.key, attribute.value);
                }
            }
            String msg = mb.build();
            Log.d(TAG, "addOrUpdateProp channelName=" + channelName + ", msg=" + msg);
            mMsgClient.send(msg);
            return 0;
        }
        return -1;
    }

    int deleteProp(String channelName, List<String> keys) {
        return 0;
    }

    int getProps(String channelName, ResultListListener<SocketAttribute> onResult) {
        return 0;
    }


    int subscribe(String channelName, ResultListListener<SocketAttribute> onUpdate) {
        if (mMsgClient != null) {
            String msg = new MessageBuilder()
                    .setAppId(appId)
                    .setChannelName(channelName)
                    .build();
            Log.d(TAG, "subscribe channelName=" + channelName + ", msg=" + msg);

            final WebSocketClient client = mSubscribeClients.get(channelName);
            if (client != null) {
                if(client.isOpen()){
                    client.send(msg);
                }else{
                    List<Runnable> runnables = mSubscribePendingRun.get(channelName);
                    if(runnables != null){
                        runnables.add(new Runnable() {
                            @Override
                            public void run() {
                                client.send(msg);
                            }
                        });
                    }
                }

            }else{
                final ArrayList<Runnable> pendingRuns = new ArrayList<>();
                mSubscribePendingRun.put(channelName, pendingRuns);
                WebSocketClient nClient = connectWebSocket(SOCKET_SUBSCRIBE_URL,
                        socket -> {
                            socket.send(msg);
                            for (Runnable pendingRun : pendingRuns) {
                                pendingRun.run();
                            }
                            pendingRuns.clear();
                        },
                        mSubscribeListener,
                        closeReason -> {
                            mSubscribeClients.remove(channelName);
                        }, ex -> {
                            mSubscribeClients.remove(channelName);
                        });
                if(nClient == null){
                    return -3;
                }
                mSubscribeClients.put(channelName, nClient);
            }
            attributeListenerMap.put(channelName, onUpdate);
            return 0;
        }
        return -1;
    }

    void unSubscribe(String channelName) {
        attributeListenerMap.remove(channelName);
        WebSocketClient client = mSubscribeClients.get(channelName);
        if(client != null){
            client.close();
            mSubscribeClients.remove(channelName);
        }
        mSubscribePendingRun.remove(channelName);
    }

    private void parseMsgString(String str) {
        //Log.d(TAG, "parseMsgString str=" + str);
    }

    private void parseSubscribeString(String str) {

        try {
            JSONObject root = new JSONObject(str);

            String channelName = root.optString("channelName");
            if (TextUtils.isEmpty(channelName)) {
                return;
            }
            Log.d(TAG, "parseSubscribeString str=" + str);
            JSONObject props = root.optJSONObject("props");

            ResultListListener<SocketAttribute> listener = attributeListenerMap.get(channelName);
            if (props != null && listener != null) {
                Iterator<String> keys = props.keys();
                List<SocketAttribute> attributes = new ArrayList<>();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = props.optString(key);
                    SocketAttribute attribute = new SocketAttribute();
                    attribute.key = key;
                    attribute.value = value;
                    attributes.add(attribute);
                }
                listener.onResult(attributes);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }


    private WebSocketClient connectWebSocket(
            String url,
            ResultListener<WebSocketClient> onOpen,
            ResultListener<String> onMessage,
            ResultListener<String> onClose,
            ResultListener<Exception> onError) {
        URI msgUri = null;
        try {
            msgUri = new URI(url);
        } catch (URISyntaxException e) {
            if (onError != null) {
                onError.onResult(e);
            }
            return null;
        }
        WebSocketClient client = new WebSocketClient(msgUri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                Log.d(TAG, "connectWebSocket " + url + " onOpen httpSatatus=" + handshakedata.getHttpStatus() + ", httpMessage=" + handshakedata.getHttpStatusMessage());
                if (onOpen != null) {
                    onOpen.onResult(this);
                }
            }

            @Override
            public void onMessage(String message) {
                Log.d(TAG, "connectMsgServer onMessage message=" + message);
                if (onMessage != null) {
                    onMessage.onResult(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                Log.d(TAG, "connectMsgServer onClose code=" + code + ", reason=" + reason + ", remote=" + remote);
                if (onClose != null) {
                    onClose.onResult(reason);
                }
            }

            @Override
            public void onError(Exception ex) {
                Log.d(TAG, "connectMsgServer onError ex=" + ex);
                if (onError != null) {
                    onError.onResult(ex);
                }
            }
        };
        client.setConnectionLostTimeout(200000);
        client.connect();
        return client;
    }


    private final static class MessageBuilder {
        private String appId;
        private String channelName;
        private final Map<String, Object> props = new LinkedHashMap<String, Object>() {
            @NonNull
            @Override
            public String toString() {
                Iterator<Entry<String, Object>> i = entrySet().iterator();
                if (!i.hasNext())
                    return "{}";

                StringBuilder sb = new StringBuilder();
                sb.append("{");
                for (; ; ) {
                    Entry<String, Object> e = i.next();
                    String key = e.getKey();
                    Object value = e.getValue();
                    sb.append("\"").append(key).append("\"");
                    sb.append(":");
                    if (value instanceof String) {
                        sb.append("\"").append(((String) value).replace("\"", "\\\"")).append("\"");
                    } else {
                        sb.append(value);
                    }
                    if (!i.hasNext())
                        return sb.append("}").toString();
                    sb.append(',').append(' ');
                }
            }
        };

        public MessageBuilder setAppId(String appId) {
            this.appId = appId;
            return this;
        }

        public MessageBuilder setChannelName(String channelName) {
            this.channelName = channelName;
            return this;
        }

        public MessageBuilder addProp(String key, Object value) {
            props.put(key, value);
            return this;
        }

        String build() {
            return '{' +
                    "\"appId\":\"" + appId + "\"," +
                    "\"channelName\":\"" + channelName + "\"," +
                    "\"props\":" + props +
                    '}';
        }
    }

    public static final class SocketAttribute {
        public String key;
        public String value;
    }

    interface ResultListener<T> {
        void onResult(T ret);
    }

    interface ResultListListener<T> {
        void onResult(List<T> ret);
    }

}
