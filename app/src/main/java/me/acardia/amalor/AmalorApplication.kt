package me.acardia.amalor

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AmalorApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        mainHandler.post {
            boundService = service
            restartCommunicationCheck()
        }
    }

    override fun onServiceDied(service: XposedService) {
        mainHandler.post {
            if (boundService === service) {
                boundService = null
                publishService(null)
            }
        }
    }

    companion object {
        @Volatile
        var currentService: XposedService? = null
            private set

        private val listeners = mutableSetOf<XposedServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var boundService: XposedService? = null
        private var communicationCheck: Job? = null

        fun addServiceStateListener(listener: XposedServiceStateListener, notifyImmediately: Boolean) {
            listeners += listener
            if (notifyImmediately) listener.onServiceStateChanged(currentService)
            if (listeners.size == 1) restartCommunicationCheck()
        }

        fun removeServiceStateListener(listener: XposedServiceStateListener) {
            listeners -= listener
            if (listeners.isEmpty()) {
                communicationCheck?.cancel()
                communicationCheck = null
            }
        }

        private fun restartCommunicationCheck() {
            communicationCheck?.cancel()
            if (listeners.isEmpty()) return
            communicationCheck = scope.launch {
                while (isActive) {
                    val candidate = boundService
                    val reachable = candidate != null && withContext(Dispatchers.IO) {
                        // A remote Binder call, not cached preferences or a local service flag.
                        runCatching { candidate.getFrameworkVersion() }.isSuccess
                    }
                    if (boundService === candidate) publishService(if (reachable) candidate else null)
                    delay(2_000L)
                }
            }
        }

        private fun publishService(service: XposedService?) {
            if (currentService === service) return
            currentService = service
            listeners.toList().forEach { it.onServiceStateChanged(service) }
        }
    }
}

interface XposedServiceStateListener {
    fun onServiceStateChanged(service: XposedService?)
}
