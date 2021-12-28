package io.agora.syncmanager.rtm;

import androidx.annotation.NonNull;

/**
 * SyncManager General Transfer Object Interface
 */
public interface IObject {

    /**
     * Deserialize IObject into the business object.
     * @param valueType
     * @param <T>
     * @return
     */
    <T> T toObject(@NonNull Class<T> valueType);

    /**
     * @return the key of IObject.
     */
    String getId();
}
