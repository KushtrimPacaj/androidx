/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.activity.contextaware;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Helper class for implementing {@link ContextAware}. Classes using this helper should
 * call {@link #addOnContextAvailableListener(OnContextAvailableListener)} and
 * {@link #removeOnContextAvailableListener(OnContextAvailableListener)} as the respective
 * methods of {@link ContextAware} are called.
 * <p>
 * You must call {@link #dispatchOnContextAvailable(Context, Bundle)} once the
 * {@link Context} is available to dispatch the callbacks to all registered listeners.
 */
public final class ContextAwareHelper {

    private final ContextAware mContextAware;

    private final Set<OnContextAvailableListener> mListeners = new CopyOnWriteArraySet<>();

    /**
     * Construct a new ContextAwareHelper for the given {@link ContextAware} instance.
     *
     * @param contextAware The ContextAware instance that listeners are being added to.
     */
    public ContextAwareHelper(@NonNull ContextAware contextAware) {
        mContextAware = contextAware;
    }

    /**
     * Add a new {@link OnContextAvailableListener} for receiving a callback for when
     * this class is associated with a {@link android.content.Context}.
     *
     * @param listener The listener that should be added.
     * @see #removeOnContextAvailableListener(OnContextAvailableListener)
     */
    public void addOnContextAvailableListener(@NonNull OnContextAvailableListener listener) {
        mListeners.add(listener);
    }

    /**
     * Remove a {@link OnContextAvailableListener} previously added via
     * {@link #addOnContextAvailableListener(OnContextAvailableListener)}.
     *
     * @param listener The listener that should be removed.
     * @see #addOnContextAvailableListener(OnContextAvailableListener)
     */
    public void removeOnContextAvailableListener(@NonNull OnContextAvailableListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Dispatch the callback of {@link OnContextAvailableListener#onContextAvailable} to
     * all currently added listeners.
     *
     * @param context The {@link Context} the {@link ContextAware} object is now associated with.
     * @param savedInstanceState The saved instance state, if any.
     */
    public void dispatchOnContextAvailable(@NonNull Context context,
            @Nullable Bundle savedInstanceState) {
        for (OnContextAvailableListener listener : mListeners) {
            listener.onContextAvailable(mContextAware, context, savedInstanceState);
        }
    }
}
