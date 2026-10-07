package com.deivid22srk.gtavserver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.deivid22srk.gtavserver.MainActivity
import com.deivid22srk.gtavserver.R
import com.deivid22srk.gtavserver.core.MirrorServer
import com.deivid22srk.gtavserver.data.Prefs
import java.io.File
import java.net.BindException
import kotlin.concurrent.thread

/**
 * Foreground Service real: o MirrorServer roda DENTRO do serviço, com notificação
 * persistente (ação Parar), PARTIAL_WAKE_LOCK e START_STICKY. Sobrevive ao app
 * em segundo plano e à morte do processo (reinício sticky re-liga usando os prefs).
 *
 * Tipo especialUse (API 34+): serviço local de longa duração — o tipo dataSync
 * teria limite de 6 h do sistema; justificativa no <property> do manifest.
 */
class ServerService : Service() {

    private lateinit var prefs: Prefs
    private var server: MirrorServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                prefs.wasRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val path = intent.getStringExtra(EXTRA_PATH) ?: prefs.folder
                val port = intent.getIntExtra(EXTRA_PORT, prefs.port)
                startAsForeground()
                startServer(path, port)
            }
            else -> {
                // Reinício START_STICKY após morte do processo: religa com os prefs
                if (prefs.wasRunning && !ServerBus.running.value) {
                    startAsForeground()
                    startServer(prefs.folder, prefs.port)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_server)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(prefs.folder)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(
                        null, getString(R.string.notif_stop), stopIntent
                    ).build()
                )
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, 0)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startServer(path: String, port: Int) {
        if (ServerBus.running.value || ServerBus.starting.value) return
        val root = File(path)
        if (!root.isDirectory) {
            ServerBus.error.value = "Pasta não encontrada: $path"
            ServerBus.starting.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        ServerBus.starting.value = true
        ServerBus.error.value = null
        thread(name = "MirrorStarter", isDaemon = true) {
            try {
                val s = MirrorServer(root, "127.0.0.1", port) { line -> ServerBus.log(line) }
                s.start()
                server = s
                acquireLocks()
                ServerBus.url.value = "http://127.0.0.1:${s.actualPort}/"
                ServerBus.running.value = true
                ServerBus.starting.value = false
                prefs.wasRunning = true
            } catch (e: BindException) {
                ServerBus.starting.value = false
                ServerBus.error.value =
                    "Porta $port já está em uso. Escolha outra porta (ex.: 8001) e tente de novo."
                ServerBus.log("OSError: [Errno 98] Address already in use")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                ServerBus.starting.value = false
                ServerBus.error.value = e.message ?: e.javaClass.simpleName
                ServerBus.log("erro ao iniciar: $e")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        releaseLocks()
        ServerBus.running.value = false
        ServerBus.starting.value = false
        ServerBus.url.value = null
        prefs.wasRunning = false
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GtavServer:Mirror").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GtavServer:Wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.deivid22srk.gtavserver.START"
        const val ACTION_STOP = "com.deivid22srk.gtavserver.STOP"
        const val EXTRA_PATH = "path"
        const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "mirror_server"
        private const val NOTIF_ID = 42

        fun start(context: Context, path: String, port: Int) {
            val intent = Intent(context, ServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_PORT, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
