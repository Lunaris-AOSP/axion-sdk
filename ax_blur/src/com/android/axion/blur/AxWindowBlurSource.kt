/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.axion.blur

import android.view.View

/** A backdrop drawn inside a window, used by blur surfaces without an explicit source. */
object AxWindowBlurSource {
    /** Call on the UI thread. Passing null restores the normal window backdrop. */
    @JvmStatic
    fun setSource(windowView: View, source: View?) {
        val root = windowView.rootView
        require(source == null || source.rootView === root) {
            "The blur source must belong to the same window"
        }
        val state = stateFor(root)
        state.source = source
        state.listeners.toList().forEach { it.onSourceChanged(source) }
        releaseIfEmpty(root, state)
    }

    internal fun interface Listener {
        fun onSourceChanged(source: View?)
    }

    internal fun addListener(root: View, listener: Listener) {
        val state = stateFor(root)
        state.listeners.add(listener)
        listener.onSourceChanged(state.source)
    }

    internal fun removeListener(root: View, listener: Listener) {
        val state = root.getTag(R.id.ax_window_blur_source) as? State ?: return
        state.listeners.remove(listener)
        releaseIfEmpty(root, state)
    }

    private fun stateFor(root: View): State =
        root.getTag(R.id.ax_window_blur_source) as? State
            ?: State().also { root.setTag(R.id.ax_window_blur_source, it) }

    private fun releaseIfEmpty(root: View, state: State) {
        if (state.source == null && state.listeners.isEmpty()) {
            root.setTag(R.id.ax_window_blur_source, null)
        }
    }

    private class State {
        var source: View? = null
        val listeners = mutableSetOf<Listener>()
    }
}
