/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.google.android.material.color.DynamicColors
import org.amnezia.awg.backend.Backend
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.AwgQuickBackend
import org.amnezia.awg.configStore.FileConfigStore
import org.amnezia.awg.model.TunnelManager
import org.amnezia.awg.util.NetworkState
import org.amnezia.awg.util.NetworkType
import org.amnezia.awg.util.RootShell
import org.amnezia.awg.util.ToolsInstaller
import org.amnezia.awg.util.UserKnobs
import org.amnezia.awg.util.applicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import java.util.Locale

class Application : android.app.Application() {
    private val futureBackend = CompletableDeferred<Backend>()
    private val coroutineScope = CoroutineScope(Job() + Dispatchers.Main.immediate)
    private var backend: Backend? = null
    private lateinit var rootShell: RootShell
    private lateinit var preferencesDataStore: DataStore<Preferences>
    private lateinit var toolsInstaller: ToolsInstaller
    private lateinit var tunnelManager: TunnelManager
    private lateinit var networkState: NetworkState

    override fun attachBaseContext(context: Context) {
        super.attachBaseContext(context)
        if (BuildConfig.MIN_SDK_VERSION > Build.VERSION.SDK_INT) {
            @Suppress("UnsafeImplicitIntentLaunch")
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            System.exit(0)
        }
    }

    private suspend fun determineBackend(): Backend {
        var backend: Backend? = null
        if (UserKnobs.enableKernelModule.first() && AwgQuickBackend.hasKernelSupport()) {
            try {
                rootShell.start()
                val awgQuickBackend = AwgQuickBackend(applicationContext, rootShell, toolsInstaller)
                awgQuickBackend.setMultipleTunnels(UserKnobs.multipleTunnels.first())
                backend = awgQuickBackend
                UserKnobs.multipleTunnels.onEach {
                    awgQuickBackend.setMultipleTunnels(it)
                }.launchIn(coroutineScope)
            } catch (ignored: Exception) {
            }
        }
        if (backend == null) {
            backend = GoBackend(applicationContext)
            GoBackend.setAlwaysOnCallback { get().applicationScope.launch { get().tunnelManager.restoreState(true) } }
        }
        return backend
    }

    override fun onCreate() {
        Log.i(TAG, USER_AGENT)
        super.onCreate()
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var splashFor: android.app.Activity? = null

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                if (activity is androidx.appcompat.app.AppCompatActivity) activity.title = getString(R.string.brand_name)
                if (activity is org.amnezia.awg.activity.MainActivity && savedInstanceState == null) splashFor = activity
            }

            override fun onActivityStarted(activity: android.app.Activity) {
                if (activity is androidx.appcompat.app.AppCompatActivity) installBrandTitle(activity, activity === splashFor)
                if (activity === splashFor) {
                    splashFor = null
                    org.amnezia.awg.util.SplashOverlay.show(activity)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val prefs = getSharedPreferences("awg_notif", Context.MODE_PRIVATE)
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            !prefs.getBoolean("asked", false) &&
                            !activity.isFinishing &&
                            activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            prefs.edit().putBoolean("asked", true).apply()
                            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711)
                        }
                    }, 3800L)
                }
            }

            private fun installBrandTitle(activity: androidx.appcompat.app.AppCompatActivity, hideForIntro: Boolean) {
                val bar = activity.supportActionBar ?: return
                if (bar.customView is org.amnezia.awg.widget.BrandTitleView) return
                val view = org.amnezia.awg.widget.BrandTitleView(activity)
                bar.setDisplayShowTitleEnabled(false)
                bar.setDisplayShowCustomEnabled(true)
                bar.setCustomView(
                    view,
                    androidx.appcompat.app.ActionBar.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    )
                )
                if (hideForIntro) {
                    view.hideForIntro()
                    org.amnezia.awg.widget.BrandTitleView.setPendingIntro(view)
                }
            }

            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        rootShell = RootShell(applicationContext)
        toolsInstaller = ToolsInstaller(applicationContext, rootShell)
        preferencesDataStore = PreferenceDataStoreFactory.create { applicationContext.preferencesDataStoreFile("settings") }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runBlocking {
                AppCompatDelegate.setDefaultNightMode(if (UserKnobs.darkTheme.first()) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
            UserKnobs.darkTheme.onEach {
                val newMode = if (it) {
                    AppCompatDelegate.MODE_NIGHT_YES
                } else {
                    AppCompatDelegate.MODE_NIGHT_NO
                }
                if (AppCompatDelegate.getDefaultNightMode() != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                }
            }.launchIn(coroutineScope)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        tunnelManager = TunnelManager(FileConfigStore(applicationContext))
        tunnelManager.onCreate()
        org.amnezia.awg.util.ConnectionNotifier.start(this, coroutineScope)

        // Initialize network state monitor for auto-reconnection
        networkState = NetworkState(applicationContext) { oldType, newType ->
            Log.i(TAG, "NetworkState callback: Network changed: $oldType -> $newType")
            onNetworkChange(oldType, newType)
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                backend = determineBackend()
                futureBackend.complete(backend!!)
                networkState.bindNetworkListener()
            } catch (e: Throwable) {
                Log.e(TAG, Log.getStackTraceString(e))
            }
        }

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(VmPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setThreadPolicy(ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
    }

    override fun onTerminate() {
        networkState.unbindNetworkListener()
        coroutineScope.cancel()
        super.onTerminate()
    }

    /**
     * Called when network changes (e.g., WiFi to Mobile or vice versa).
     * Reconnects active tunnels to ensure VPN connection works on new network.
     */
    private fun onNetworkChange(oldType: NetworkType, newType: NetworkType) {
        Log.i(TAG, "onNetworkChange called: $oldType -> $newType")
        
        if (newType == NetworkType.NONE) {
            Log.i(TAG, "Network lost, waiting for new connection...")
            return
        }

        coroutineScope.launch {
            try {
                val activeTunnels = tunnelManager.getTunnels().filter { 
                    it.state == org.amnezia.awg.backend.Tunnel.State.UP 
                }

                if (activeTunnels.isEmpty()) {
                    Log.d(TAG, "No active tunnels, skipping reconnection")
                    return@launch
                }

                Log.i(TAG, "Reconnecting ${activeTunnels.size} tunnel(s) after network change: $oldType -> $newType")

                for (tunnel in activeTunnels) {
                    try {
                        Log.d(TAG, "Disconnecting tunnel: ${tunnel.name}")
                        // Toggle tunnel off and on to reconnect
                        tunnel.setStateAsync(org.amnezia.awg.backend.Tunnel.State.DOWN)
                        kotlinx.coroutines.delay(500) // Small delay for cleanup
                        Log.d(TAG, "Reconnecting tunnel: ${tunnel.name}")
                        tunnel.setStateAsync(org.amnezia.awg.backend.Tunnel.State.UP)
                        Log.i(TAG, "Successfully reconnected tunnel: ${tunnel.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reconnect tunnel ${tunnel.name}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during network change handling", e)
            }
        }
    }

    companion object {
        val USER_AGENT = String.format(Locale.ENGLISH, "AmneziaWG/%s (Android %d; %s; %s; %s %s; %s)", BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown ABI", Build.BOARD, Build.MANUFACTURER, Build.MODEL, Build.FINGERPRINT)
        private const val TAG = "AmneziaWG/Application"
        private lateinit var weakSelf: WeakReference<Application>

        fun get(): Application {
            return weakSelf.get()!!
        }

        suspend fun getBackend() = get().futureBackend.await()

        fun getRootShell() = get().rootShell

        fun getPreferencesDataStore() = get().preferencesDataStore

        fun getToolsInstaller() = get().toolsInstaller

        fun getTunnelManager() = get().tunnelManager

        fun getCoroutineScope() = get().coroutineScope

        fun getNetworkState() = get().networkState
    }

    init {
        weakSelf = WeakReference(this)
    }
}
