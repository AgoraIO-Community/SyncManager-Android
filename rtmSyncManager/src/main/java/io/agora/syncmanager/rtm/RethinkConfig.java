package io.agora.syncmanager.rtm;

public class RethinkConfig {

    public String appId;
    public String sceneName;

    public RethinkConfig(String appId, String sceneName) {
        this.sceneName = sceneName;
        this.appId = appId;
    }
}
