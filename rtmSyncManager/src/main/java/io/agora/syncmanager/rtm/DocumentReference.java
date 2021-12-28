package io.agora.syncmanager.rtm;

import androidx.annotation.NonNull;

import java.util.HashMap;

/**
 * The Handle Reference for Document type of object.
 */
public class DocumentReference {

    private String id;
    protected String parent;
    protected ISyncManager manager;

    public DocumentReference(ISyncManager manager, String parent, String id) {
        this.id = id;
        this.parent = parent;
        this.manager = manager;
    }

    /**
     * @return ID of object
     */
    public String getId() {
        return id;
    }

    /**
     * @return Scene id if it is a Scene Property, or Collection id if it is a collection element.
     */
    public String getParent() {
        return parent;
    }

    /**
     * NOT SUPPORTED for rtm based Sync Manager
     * @return
     */
    public Query getQuery() {
        return null;
    }

    /**
     * NOT SUPPORTED for rtm based Sync Manager
     * @param mQuery
     * @return
     */
    public DocumentReference query(Query mQuery) {
        return this;
    }

    /**
     * fetch data content for this document object.
     * @param callback Async callback for object data.
     */
    public void get(Sync.DataItemCallback callback) {
        manager.get(this, callback);
    }

    /**
     * fetch data content for this property of related key.
     * @param callback Async callback for object data.
     */
    public void get(String key, Sync.DataItemCallback callback) {
        manager.get(this, key, callback);
    }

    /**
     * update object data content for specific document object.
     * @param data Object data in Map format
     * @param callback callback for notifying handle result.
     */
    public void update(@NonNull HashMap<String, Object> data, Sync.DataItemCallback callback) {
        manager.update(this, data, callback);
    }

    /**
     * update specific Scene property by key
     * @param key key for property
     * @param data data in json format
     * @param callback callback for notifying handle result.
     */
    public void update(String key, Object data, Sync.DataItemCallback callback) {
        manager.update(this, key, data, callback);
    }

    /**
     * delete scene or spefice collection element3.
     * @param callback callback for notifying handle result.
     */
    public void delete(Sync.Callback callback) {
        manager.delete(this, callback);
    }

    /**
     * Subscribe scene object
     * When this scene deleted by remote user, you will get onDeleted callback
     * @param listener callback for fetch async notifications.
     */
    public void subscribe(Sync.EventListener listener) {
        manager.subscribe(this, listener);
    }

    /**
     * Subscribe specific Scene property changes.
     * @param key for property
     * @param listener callback for fetch async notifications.
     */
    public void subscribe(String key, Sync.EventListener listener) {
        manager.subscribe(this, key, listener);
    }

    /**
     * Unsubscribe scene object.
     * Then you will not get any changes notification for this scene.
     * @param listener
     */
    public void unsubscribe(Sync.EventListener listener) {
        manager.unsubscribe(id, listener);
    }
}
