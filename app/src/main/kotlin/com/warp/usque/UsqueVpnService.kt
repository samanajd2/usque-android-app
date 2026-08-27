package com.warp.usque

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import usqueandroid.Usqueandroid
import usqueandroid.VpnStateCallback
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UsqueVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.warp.usque.STOP_VPN"
        private const val TAG = "UsqueVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "usque_vpn"
        @Volatile private var activeService: UsqueVpnService? = null
        @Volatile var isServiceRunning: Boolean = false
            private set
        @Volatile var isServiceConnected: Boolean = false
            private set
        @Volatile var stateListener: ((state: String, message: String) -> Unit)? = null

        fun stopActiveTunnel() {
            activeService?.stopVpn("external stop") ?: runCatching { Usqueandroid.stopTunnel() }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var tun: ParcelFileDescriptor? = null
    private var detachedTunFd: Int = -1
    private val running = AtomicBoolean(false)
    private val manualStop = AtomicBoolean(false)
    private val restarting = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    @Volatile private var lastConfigPath: String = ""
    @Volatile private var lastSni: String = ""
    @Volatile private var lastEndpoint: String = ""
    @Volatile private var lastSplitMode: Boolean = false
    @Volatile private var lastUseHttp2: Boolean = false    
    @Volatile private var lastAllowedApps: ArrayList<String> = arrayListOf()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeService = this
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            DiagLog.add("Service", "stop requested")
            manualStop.set(true)
            stopVpn("ACTION_STOP")
            stopSelf()
            return Service.START_NOT_STICKY
        }

        startForegroundCompat()
        manualStop.set(false)

        val configPath = intent?.getStringExtra("configPath") ?: File(filesDir, "config.json").absolutePath
        val sni = intent?.getStringExtra("sni") ?: "cdnjs.cloudflare.com"
        val endpoint = intent?.getStringExtra("endpoint") ?: "162.159.198.2:443"
        val splitMode = intent?.getBooleanExtra("splitMode", false) ?: false
        val useHttp2 = intent?.getBooleanExtra("useHttp2", false) ?: false
        val allowedApps = intent?.getStringArrayListExtra("allowedApps") ?: arrayListOf()

        lastConfigPath = configPath
        lastSni = sni
        lastEndpoint = endpoint
        lastSplitMode = splitMode
        lastUseHttp2 = useHttp2
        lastAllowedApps = ArrayList(allowedApps)

        if (running.get()) return Service.START_STICKY
        executor.execute { startNativeTunnel(configPath, sni, endpoint, splitMode, useHttp2, allowedApps) }
        return Service.START_STICKY
    }

    private fun startNativeTunnel(configPath: String, sni: String, endpoint: String, splitMode: Boolean, useHttp2: Boolean, allowedApps: ArrayList<String>) {
        try {
            manualStop.set(false)
            running.set(true)
            isServiceRunning = true
            isServiceConnected = false
            Log.i(TAG, "starting vpn service endpoint=$endpoint sni=$sni splitMode=$splitMode allowedApps=${allowedApps.size} config=$configPath")
            DiagLog.add("Service", "start endpoint=$endpoint sni=$sni http2=$useHttp2 split=$splitMode")
            Usqueandroid.resetConnectionOptions()
            Usqueandroid.setSNI(sni)
            Usqueandroid.setEndpoint(endpoint)

            Usqueandroid.setUseHttp2(useHttp2)

            Log.i(TAG, "native endpoint now=${runCatching { Usqueandroid.getEndpoint() }.getOrDefault("")}")

            val localIp = safeIPv4(configPath)
            DiagLog.add("Service", "local tun IPv4=$localIp")
            val builder = Builder()
                .setSession("Usque VPN")
                .setMtu(1280)
                .addAddress(localIp, 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .addRoute("0.0.0.0", 0)

            if (splitMode) {
                if (allowedApps.isEmpty()) throw IllegalStateException("split mode enabled but no apps selected")
                allowedApps.distinct().forEach { pkg ->
                    if (pkg == packageName) {
                        Log.i(TAG, "skip allowing own package to avoid VPN loop: $pkg")
                    } else {
                        runCatching { builder.addAllowedApplication(pkg) }
                            .onFailure { Log.w(TAG, "addAllowedApplication failed: $pkg", it) }
                    }
                }
            } else {
                // Critical: do not route this app's own MASQUE/QUIC control connection into itself.
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { Log.w(TAG, "addDisallowedApplication failed", it) }
            }

            val ipv6 = runCatching { Usqueandroid.getAssignedIPv6(configPath) }.getOrDefault("")
            if (ipv6.isNotBlank()) runCatching {
                builder.addAddress(ipv6, 128)
                builder.addRoute("::", 0)
                builder.addDnsServer("2606:4700:4700::1111")
                builder.addDnsServer("2606:4700:4700::1001")
            }.onFailure { Log.w(TAG, "ipv6 setup failed", it) }

            val pfd = builder.establish() ?: throw IllegalStateException("builder.establish returned null")
            tun = pfd
            detachedTunFd = pfd.detachFd()
            tun = null
            Log.i(TAG, "tun established fd=$detachedTunFd")
            DiagLog.add("Service", "tun established fd=$detachedTunFd")

            // Native fd mode: Go owns the Android TUN fd and handles the full data plane.
            // Do NOT pass connect-port here. The second argument is tunFd.
            val err = Usqueandroid.startTunnelWithFd(configPath, detachedTunFd.toLong(), object : VpnStateCallback {
                override fun onConnected() {
                    restarting.set(false)
                    Log.i(TAG, "tunnel connected")
                    DiagLog.add("Service", "onConnected")
                    broadcastState("connected")
                }
                override fun onDisconnected(reason: String?) {
                    Log.w(TAG, "tunnel disconnected: $reason")
                    DiagLog.add("Service", "onDisconnected: ${reason.orEmpty()}")
                    broadcastState(if (manualStop.get()) "disconnected" else "reconnecting", reason.orEmpty())
                    handleTunnelFailure("native disconnected: ${reason.orEmpty()}")
                }
                override fun onError(message: String?) {
                    Log.e(TAG, "tunnel error: $message")
                    DiagLog.add("Service", "onError: ${message.orEmpty()}")
                    broadcastState(if (manualStop.get()) "disconnected" else "reconnecting", message.orEmpty())
                    handleTunnelFailure("native error: ${message.orEmpty()}")
                }
            })
            if (!err.isNullOrBlank()) throw IllegalStateException(err)

            Log.i(TAG, "startTunnelWithFd returned without error")
        } catch (e: Exception) {
            Log.e(TAG, "vpn service failed", e)
            DiagLog.add("Service", "exception: ${e.message ?: e.javaClass.simpleName}")
            // Go отверг fd ещё до того, как взял его в работу (например,
            // "Tunnel is already running") — значит закрыть его может только
            // Kotlin. detachFd() уже отвязал его от ParcelFileDescriptor,
            // поэтому оборачиваем обратно через adoptFd(), только чтобы закрыть.
            if (detachedTunFd >= 0) {
                runCatching { ParcelFileDescriptor.adoptFd(detachedTunFd).close() }
                    .onFailure { Log.w(TAG, "failed to close orphaned tun fd=$detachedTunFd", it) }
                detachedTunFd = -1
            }
            handleTunnelFailure("exception: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun handleTunnelFailure(reason: String) {
        if (manualStop.get()) {
            stopVpn(reason)
            stopSelf()
            return
        }
        scheduleRestart(reason)
    }

    private fun scheduleRestart(reason: String) {
        if (restarting.getAndSet(true)) {
            Log.w(TAG, "restart already scheduled: $reason")
            return
        }
        Log.w(TAG, "scheduling VPN restart: $reason")
        stopVpn("restart: $reason")
        executor.execute {
            runCatching { TimeUnit.SECONDS.sleep(3) }
            if (manualStop.get()) {
                restarting.set(false)
                return@execute
            }
            // Allow a failed retry to schedule another retry instead of getting stuck after one attempt.
            restarting.set(false)
            startForegroundCompat()
            startNativeTunnel(lastConfigPath, lastSni, lastEndpoint, lastSplitMode, lastUseHttp2, ArrayList(lastAllowedApps))
        }
    }

    private fun startForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Usque VPN", NotificationManager.IMPORTANCE_LOW)
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setContentTitle("Usque VPN")
                .setContentText("VPN is running")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    private fun safeIPv4(configPath: String): String {
        return runCatching { Usqueandroid.getAssignedIPv4(configPath) }
            .getOrDefault("")
            .ifBlank { "172.16.0.2" }
    }
    private fun stopVpn(reason: String = "stop") {
        if (!stopping.compareAndSet(false, true)) { return }
        DiagLog.add("Service", "stopVpn: $reason")
        try {
            manualStop.set(true)
            running.set(false)
            runCatching { Usqueandroid.stopTunnel() }
            // Дескриптор больше НЕ закрываем здесь: теперь этим надёжно занимается
            // сама Go-сторона (tunDevice.Close() внутри неё), и делает это правильно —
            // синхронно, в момент, когда действительно перестаёт им пользоваться.
            // Если закрыть его тут же — образуется многосекундное окно, в которое
            // номер дескриптора успевает достаться чему-то другому, и когда Go
            // спустя 3-4 секунды дойдёт до своего закрытия — закроет уже чужое.
            tun = null
            detachedTunFd = -1
        } finally { stopping.set(false) }
    }
    
    private fun fileDescriptorFromInt(fdInt: Int): FileDescriptor {
        val fd = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(fd, fdInt)
        return fd
    }

    override fun onDestroy() {
        manualStop.set(true)
        stopVpn("onDestroy")
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onRevoke() {
        Log.w(TAG, "onRevoke: система отозвала VPN (скорее всего, запущен другой VPN)")
        manualStop.set(true)
        broadcastState("disconnected", "отозван системой/другим VPN")
        // НЕ зовём stopVpn() напрямую здесь — Android в этот самый момент сам
        // разбирает интерфейс через Binder, и наша параллельная попытка закрыть
        // тот же fd синхронно как раз и создавала гонку с системным teardown.
        // super.onRevoke() запускает штатный stopSelf() → onDestroy() → stopVpn(),
        // асинхронно — ровно так, как это устроено в оригинале (там onRevoke() нет вовсе).
        super.onRevoke()
    }

    private fun broadcastState(state: String, message: String = "") {
        isServiceRunning = (state != "disconnected")
        isServiceConnected = (state == "connected")
        stateListener?.invoke(state, message)
    }
}
