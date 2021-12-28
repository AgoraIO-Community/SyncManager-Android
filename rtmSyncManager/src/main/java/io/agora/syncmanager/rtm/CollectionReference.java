package io.agora.syncmanager.rtm;

import androidx.annotation.NonNull;

import java.util.HashMap;

/**
 * The Handle Reference for Collection type of object.
 */
public class CollectionReference {

    private String key;
    private String parent;
    private ISyncManager manager;

    public CollectionReference(ISyncManager manager, String parent, String key) {
        this.key = key;
        this.manager = manager;
        this.parent = parent;
    }

    /**
     * NOT SUPPORTED for rtm based Sync Manager
     * @param mQuery
     * @return
     */
    public CollectionReference query(Query mQuery) {
        return this;
    }

    /**
     * NOT SUPPORTED for rtm based Sync Manager
     * @return
     */
    public Query getQuery() {
        return null;
    }

    /**
     * @return collection id
     */
    public String getKey() {
        return key;
    }

    /**
     * @return Scene id
     */
    public String getParent() {
        return parent;
    }

    /**
     * @param id the key for related collection element. You could get it by IObject.getId() function.
     * @return related collection element.
     */
    public DocumentReference document(@NonNull String id) {
        return new DocumentReference(manager, parent, id);
    }

    /**
     * Add one element into related collection.
     * @param datas element object in Map format.
     * @param callback callback for notifying handle result.
     */
    public void add(HashMap<String, Object> datas, Sync.DataItemCallback callback) {
        manager.add(this, datas, callback);
    }

    /**
     * Fetch the whole collection list.
     * @param callback Async callback for collection data.
     */
    public void get(Sync.DataListCallback callback) {
        manager.get(this, callback);
    }

    /**
     * delete the whole collection.
     * @param callback callback for notifying handle result.
     */
    public void delete(Sync.Callback callback) {
        manager.delete(this, callback);
    }

    /**
     * subscribe the collection changes.
     * When subscribe succeed, any changes (add/update/delete) will reflected by a callback.
     * @param listener
     */
    public void subscribe(Sync.EventListener listener) {
        manager.subscribe(this, listener);
    }
}
