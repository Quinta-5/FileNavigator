package com.w2sv.navigator

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import com.w2sv.androidutils.UnboundService
import com.w2sv.common.di.AppDispatcher
import com.w2sv.common.di.GlobalScope
import com.w2sv.common.utils.collectOn
import com.w2sv.common.utils.log
import com.w2sv.navigator.moving.MoveResultListener
import com.w2sv.navigator.notifications.AppNotificationId
import com.w2sv.navigator.notifications.managers.FileNavigatorIsRunningNotificationManager
import com.w2sv.navigator.observing.FileObserver
import com.w2sv.navigator.observing.FileObserverFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import slimber.log.e
import slimber.log.i
import javax.inject.Inject

@AndroidEntryPoint
class FileNavigator : UnboundService() {

    @Inject
    internal lateinit var isRunning: IsRunning

    @Inject
    internal lateinit var isRunningNotificationManager: FileNavigatorIsRunningNotificationManager

    @Inject
    internal lateinit var fileObserverFactory: FileObserverFactory

    @Inject
    internal lateinit var moveResultListener: MoveResultListener

    @Inject
    internal lateinit var moveResultChannel: MoveResultChannel

    @Inject
    @GlobalScope(AppDispatcher.IO)
    internal lateinit var scope: CoroutineScope

    private var activeFileObservers: List<FileObserver>? = null

    private val fileObserverHandlerThread by lazy {
        HandlerThread("com.w2sv.filenavigator.ContentObserverThread")
    }

    private fun getRegisteredFileObservers(): List<FileObserver> {
        if (!fileObserverHandlerThread.isAlive) {
            fileObserverHandlerThread.start()
        }

        return fileObserverFactory.invoke(handler = Handler(fileObserverHandlerThread.looper))
            .onEach { observer ->
                contentResolver.registerContentObserver(
                    observer.mediaType.readUri!!,
                    true,
                    observer
                )
            }
            .log { "Registered ${it.size} FileObserver(s)" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        i { "onStartCommand | action: ${intent?.action}" }

        when (intent?.action) {
            Action.STOP_SERVICE -> {
                stop()
            }

            Action.REREGISTER_MEDIA_OBSERVERS -> {
                unregisterFileObservers()
                activeFileObservers = getRegisteredFileObservers()
            }

            else -> try {
                start()
            } catch (e: RuntimeException) {
                e(e)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun start() {
        i { "Starting FileNavigator" }

        startForeground(
            AppNotificationId.FileNavigatorIsRunning.id,
            isRunningNotificationManager.buildNotification(Unit)
        )

        i { "Registering file observers" }
        activeFileObservers = getRegisteredFileObservers()
        moveResultChannel
            .consumeAsFlow()
            .collectOn(scope = scope, collector = moveResultListener::onMoveResult)

        isRunning.setState(true)
    }

    private fun stop() {
        i { "Stopping FileNavigator" }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning.setState(false)
    }

    private fun unregisterFileObservers() {
        activeFileObservers
            ?.forEach {
                contentResolver.unregisterContentObserver(it)
            }
        i { "Unregistered fileObservers" }
    }

    override fun onDestroy() {
        super.onDestroy()

        unregisterFileObservers()
        fileObserverHandlerThread.quit()
    }

    class IsRunning internal constructor(private val mutableStateFlow: MutableStateFlow<Boolean>) :
        StateFlow<Boolean> by mutableStateFlow {

        internal fun setState(value: Boolean) {
            mutableStateFlow.value = value
        }
    }

    private data object Action {
        const val REREGISTER_MEDIA_OBSERVERS =
            "com.w2sv.filenavigator.REREGISTER_MEDIA_OBSERVERS"
        const val STOP_SERVICE = "com.w2sv.filenavigator.STOP"
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(
                getIntent(context)
            )
        }

        fun stop(context: Context) {
            context.startService(
                getStopIntent(context)
            )
        }

        fun reregisterFileObservers(context: Context) {
            context.startService(
                getIntent(context)
                    .setAction(Action.REREGISTER_MEDIA_OBSERVERS)
            )
        }

        fun getStopIntent(context: Context): Intent =
            getIntent(context)
                .setAction(Action.STOP_SERVICE)

        private fun getIntent(context: Context): Intent =
            Intent(context, FileNavigator::class.java)
    }
}