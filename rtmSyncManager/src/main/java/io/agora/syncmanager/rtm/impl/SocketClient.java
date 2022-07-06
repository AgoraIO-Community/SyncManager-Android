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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SocketClient {
    private static final String TAG = "SocketClient";
    private static final String SOCKET_URL_BASE = "ws://114.236.137.88:8199";

    private static final String ACTION_SEND = "send";
    private static final String ACTION_QUERY = "query";
    private static final String ACTION_DELETE = "delete";
    private static final String ACTION_SUBSCRIBE = "subscribe";


    private final String appId;

    private WebSocketClient mBasicClient;
    private final Map<String, WebSocketClient> mSubscribeClients = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> mSubscribePendingRun = new ConcurrentHashMap<>();
    private final Map<String, ResultListListener<SocketAttribute>> attributeListenerMap = new ConcurrentHashMap<>();
    private final ResultListener<String> mSubscribeListener = msg -> {
        ArrayList<SocketAttribute> outList = new ArrayList<>();
        String channel = parseAttributes(msg, outList);
        if(!TextUtils.isEmpty(channel)){
            ResultListListener<SocketAttribute> listener = attributeListenerMap.get(channel);
            if(listener != null){
                listener.onResult(outList);
            }
        }
    };


    SocketClient(String appId) {
        this.appId = appId;
    }

    void connect(ResultListener<WebSocketClient> onOpen, ResultListener<String> onClose, ResultListener<Exception> onError) {
        mBasicClient = connectWebSocket(
                SOCKET_URL_BASE,
                onOpen,
                null,null,
                onClose,
                onError
        );
    }

    void close() {
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
        if (mBasicClient != null) {
            mBasicClient.close();
            mBasicClient = null;
        }
    }

    int addOrUpdateProp(String channelName, List<SocketAttribute> attributes) {
        if (mBasicClient != null) {
            MessageBuilder mb = new MessageBuilder()
                    .setAction(ACTION_SEND)
                    .setAppId(appId)
                    .setChannelName(channelName);
            if (attributes != null) {
                for (SocketAttribute attribute : attributes) {
                    mb.addMapProp(attribute.key, attribute.value);
                }
            }
            String msg = mb.build();
            Log.d(TAG, "addOrUpdateProp channelName=" + channelName + ", msg=" + msg);
            mBasicClient.send(msg);
            return 0;
        }
        return -1;
    }

    int deleteProps(String channelName, List<String> keys) {
        if (mBasicClient != null) {
            String deleteStr = new MessageBuilder().setAppId(appId)
                    .setAction(ACTION_DELETE)
                    .addListProps(keys)
                    .setChannelName(channelName).build();
            Log.d(TAG, "deleteProp queryStr=" + deleteStr);
            connectWebSocket(SOCKET_URL_BASE,
                    client -> client.send(deleteStr),
                    null,
                    WebSocketClient::close,
                    null,
                    null);
            return 0;
        }
        return -1;
    }

    int getProps(String channelName, ResultListListener<SocketAttribute> onResult) {
        if (mBasicClient != null) {
            String queryStr = new MessageBuilder().setAction(ACTION_QUERY).setAppId(appId).setChannelName(channelName).build();
            Log.d(TAG, "getProps queryStr=" + queryStr);
            connectWebSocket(SOCKET_URL_BASE,
                    client -> client.send(queryStr),
                    msg -> {
                        ArrayList<SocketAttribute> outList = new ArrayList<>();
                        String cn = parseAttributes(msg, outList);
                        if(onResult != null){
                            onResult.onResult(outList);
                        }
                    },
                    WebSocketClient::close,
                    null,
                    null);
            return 0;
        }
        return -1;
    }


    int subscribe(String channelName, ResultListListener<SocketAttribute> onUpdate) {
        if (mBasicClient != null) {
            String msg = new MessageBuilder()
                    .setAction(ACTION_SUBSCRIBE)
                    .setAppId(appId)
                    .setChannelName(channelName)
                    .build();
            Log.d(TAG, "subscribe channelName=" + channelName + ", msg=" + msg);

            final WebSocketClient client = mSubscribeClients.get(channelName);
            if (client != null) {
                if (client.isOpen()) {
                    client.send(msg);
                } else {
                    List<Runnable> runnables = mSubscribePendingRun.get(channelName);
                    if (runnables != null) {
                        runnables.add(new Runnable() {
                            @Override
                            public void run() {
                                client.send(msg);
                            }
                        });
                    }
                }

            } else {
                final ArrayList<Runnable> pendingRuns = new ArrayList<>();
                mSubscribePendingRun.put(channelName, pendingRuns);
                WebSocketClient nClient = connectWebSocket(SOCKET_URL_BASE,
                        socket -> {
                            socket.send(msg);
                            for (Runnable pendingRun : pendingRuns) {
                                pendingRun.run();
                            }
                            pendingRuns.clear();
                        },
                        mSubscribeListener,null,
                        closeReason -> {
                            mSubscribeClients.remove(channelName);
                        }, ex -> {
                            mSubscribeClients.remove(channelName);
                        });
                if (nClient == null) {
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
        if (client != null) {
            client.close();
            mSubscribeClients.remove(channelName);
        }
        mSubscribePendingRun.remove(channelName);
    }

    private String parseAttributes(String str, List<SocketAttribute> outList) {
        if(TextUtils.isEmpty(str) || "null".equals(str)){
            return "";
        }
        try {
            JSONObject root = new JSONObject(str);

            String channelName = root.optString("channelName");
            if (TextUtils.isEmpty(channelName)) {
                return null;
            }
            Log.d(TAG, "parseAttributes str=" + str);
            JSONObject props = root.optJSONObject("props");

            if (props != null) {
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
                outList.addAll(attributes);
            }
            return channelName;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }


    private WebSocketClient connectWebSocket(
            String url,
            ResultListener<WebSocketClient> onOpen,
            ResultListener<String> onMessage,
            ResultListener<WebSocketClient> onAfterMessage,
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
                if(onAfterMessage != null){
                    onAfterMessage.onResult(this);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                Log.d(TAG, "connectMsgServer onClose code=" + code + ", reason=" + reason + ", remote=" + remote);
                if (remote && mBasicClient != null) {
                    reconnect();
                }
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
        client.setConnectionLostTimeout(Integer.MAX_VALUE);
        client.connect();
        return client;
    }


    private final static class MessageBuilder {
        private String appId;
        private String action;
        private String channelName;
        private final Map<String, Object> propsMap = new LinkedHashMap<String, Object>() {
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
        private final List<String> propsList = new ArrayList<String>(){
            @NonNull
            @Override
            public String toString() {
                Iterator<String> it = iterator();
                if (! it.hasNext())
                    return "[]";

                StringBuilder sb = new StringBuilder();
                sb.append('[');
                for (;;) {
                    String e = it.next();
                    sb.append("\"").append(e).append("\"");
                    if (!it.hasNext())
                        return sb.append(']').toString();
                    sb.append(',').append(' ');
                }
            }
        };

        public MessageBuilder setAction(String action){
            this.action = action;
            return this;
        }

        public MessageBuilder setAppId(String appId) {
            this.appId = appId;
            return this;
        }

        public MessageBuilder setChannelName(String channelName) {
            this.channelName = channelName;
            return this;
        }

        public MessageBuilder addMapProp(String key, Object value) {
            propsMap.put(key, value);
            return this;
        }

        public MessageBuilder addListProp(String key) {
            propsList.add(key);
            return this;
        }

        public MessageBuilder addListProps(List<String> keys) {
            propsList.addAll(keys);
            return this;
        }

        String build() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"action\":\"").append(action).append("\"");
            sb.append("\"appId\":\"").append(appId).append("\"");
            sb.append(",\"channelName\":\"").append(channelName).append("\"");
            if (propsMap.size() > 0) {
                sb.append(",\"props\":").append(propsMap);
            } else if (propsList.size() > 0) {
                sb.append(",\"props\":").append(propsList);
            }
            sb.append("}");
            return sb.toString();
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
