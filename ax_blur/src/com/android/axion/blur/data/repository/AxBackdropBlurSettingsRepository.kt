/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.axion.blur.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.CrossWindowBlurListeners
import com.android.axion.blur.AxBlurProperties
import com.android.axion.blur.R
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec
import com.android.axion.blur.model.AxBackdropBlurSettingsSubscription
import com.android.internal.graphics.AdvancedBlurConfig
import com.android.axion.kotlin.settings.SettingsFlow
import com.android.axion.kotlin.settings.SettingsType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.function.Consumer

internal class AxBackdropBlurSettingsRepository(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val appContext = context.applicationContext ?: context
    private val resolver = appContext.contentResolver
    private val globalSettings = SettingsFlow(resolver, SettingsType.GLOBAL, handler = handler)
    private val secureSettings = SettingsFlow(
        resolver,
        SettingsType.SECURE,
        currentUserId(),
        handler,
    )

    private val systemSettings = SettingsFlow(
        resolver,
        SettingsType.SYSTEM,
        currentUserId(),
        handler,
    )
    private val secureObservationSettings = SettingsFlow(
        resolver, SettingsType.SECURE, AdvancedBlurConfig.observationUserId(appContext), handler,
    )
    private val systemObservationSettings = SettingsFlow(
        resolver, SettingsType.SYSTEM, AdvancedBlurConfig.observationUserId(appContext), handler,
    )

    private fun currentUserId(): Int {
        return if (UserHandle.myUserId() == UserHandle.USER_SYSTEM
            && appContext.userId == UserHandle.USER_SYSTEM
            && AdvancedBlurConfig.observationUserId(appContext) == UserHandle.USER_ALL) {
            UserHandle.USER_CURRENT
        } else appContext.userId
    }

    fun globalBlurEnabled(): Boolean {
        return !AxBlurProperties.disableBlur && globalSettings.getInt(
            Settings.Global.DISABLE_WINDOW_BLURS,
            if (AxBlurProperties.defaultGlobalBlurEnabled) 0 else 1,
        ) == 0
    }

    fun settingEnabled(spec: AxBackdropBlurSettingsSpec): Boolean {
        val key = spec.enabledKey ?: return true
        return enabledSettings(spec).getInt(key, if (spec.defaultEnabled) 1 else 0) != 0
    }

    fun advancedBlurConfig(): AdvancedBlurConfig? = AdvancedBlurConfig.currentForWindow(appContext)

    private fun enabledSettings(spec: AxBackdropBlurSettingsSpec): SettingsFlow {
        return when (spec.enabledSettingsType) {
            SettingsType.SYSTEM -> systemSettings
            SettingsType.SECURE -> secureSettings
            SettingsType.GLOBAL -> globalSettings
        }
    }

    fun secureFloat(key: String, default: Float): Float {
        return secureSettings.getFloat(key, default)
    }

    fun createSubscription(
        spec: AxBackdropBlurSettingsSpec,
        onSettingsChanged: () -> Unit,
    ): AxBackdropBlurSettingsSubscription {
        return SettingsFlowSubscription(
            context = appContext,
            flows = flows(spec),
            handler = handler,
            onSettingsChanged = onSettingsChanged,
        )
    }

    private fun flows(spec: AxBackdropBlurSettingsSpec): List<Flow<Unit>> {
        return buildList {
            add(globalSettings.observe(Settings.Global.DISABLE_WINDOW_BLURS, emitInitial = false))
            spec.enabledKey?.let {
                val settings = when (spec.enabledSettingsType) {
                    SettingsType.SYSTEM -> systemObservationSettings
                    SettingsType.SECURE -> secureObservationSettings
                    SettingsType.GLOBAL -> globalSettings
                }
                add(settings.observe(it, emitInitial = false))
            }
            add(secureObservationSettings.observe(spec.radiusKey, emitInitial = false))
        }
    }

    private class SettingsFlowSubscription(
        private val context: Context,
        private val flows: List<Flow<Unit>>,
        private val handler: Handler,
        private val onSettingsChanged: () -> Unit,
    ) : AxBackdropBlurSettingsSubscription {
        private val scope = CoroutineScope(
            SupervisorJob() + handler.asCoroutineDispatcher("AxBackdropBlurSettings"),
        )
        private var job: Job? = null
        private var listeningForBlur = false
        private val configListener = Runnable {
            handler.post { if (job != null) onSettingsChanged() }
        }
        private val blurListener = Consumer<Boolean> {
            if (job != null) onSettingsChanged()
        }

        override fun start() {
            if (job != null) return
            job = scope.launch {
                merge(*flows.toTypedArray()).collect {
                    onSettingsChanged()
                }
            }
            try {
                CrossWindowBlurListeners.getInstance().addListener(
                    Executor { handler.post(it) }, blurListener,
                )
                listeningForBlur = true
            } catch (_: RuntimeException) {
            }
            AdvancedBlurConfig.addListener(context, configListener)
        }

        override fun stop() {
            job?.cancel()
            job = null
            if (listeningForBlur) {
                CrossWindowBlurListeners.getInstance().removeListener(blurListener)
                listeningForBlur = false
            }
            AdvancedBlurConfig.removeListener(configListener)
        }
    }

    companion object {
        fun maxBlurRadiusPx(context: Context): Float {
            return context.resources.getDimension(R.dimen.ax_backdrop_blur_radius)
        }

    }
}
