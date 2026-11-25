package com.xingyuan.trafficburner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 前台下载服务：多线程循环拉取 Cloudflare 公共测速文件，实时统计速度与总量。
 * 数据读入后直接丢弃，不写入磁盘。
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "TrafficBurner"
        const val ACTION_START = "com.xingyuan.trafficburner.START"
        const val ACTION_STOP = "com.xingyuan.trafficburner.STOP"
        const val EXTRA_TARGET_BYTES = "target_bytes"
        const val EXTRA_THREADS = "threads"

        const val BROADCAST_UPDATE = "com.xingyuan.trafficburner.UPDATE"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_SPEED = "speed" // bytes per second
        const val EXTRA_FINISHED = "finished"

        private const val CHANNEL_ID = "traffic_burner"
        private const val NOTIFICATION_ID = 1001

        /** 单次请求拉取 50MB，循环拉取 */
        private const val CHUNK_BYTES = 50_000_000

        private const val PREFS = "burn_state"

        /** 默认多源轮换：国内镜像为主（大文件 + 支持 Range），Cloudflare 测速接口兜底 */
        private val SOURCES = listOf(
            "https://mirrors.cloud.tencent.com/gradle/gradle-8.10-bin.zip",
            "https://mirrors.huaweicloud.com/gradle/gradle-8.10-bin.zip",
            "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip",
            "https://mirrors.huaweicloud.com/gradle/gradle-8.9-bin.zip",
            "https://speed.cloudflare.com/__down?bytes=$CHUNK_BYTES"
        )

        const val MODE_AUTO = 0
        const val MODE_TENCENT = 1
        const val MODE_HUAWEI = 2
        const val MODE_CUSTOM = 3

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val totalBytes = AtomicLong(0)

    @Volatile
    private var targetBytes = Long.MAX_VALUE

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** 记住本次任务参数，供服务被杀后自动恢复 */
    private var lastTarget = 0L
    private var lastThreads = 4

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetBytes = intent.getLongExtra(EXTRA_TARGET_BYTES, Long.MAX_VALUE)
                val threads = intent.getIntExtra(EXTRA_THREADS, 4).coerceIn(1, 8)
                lastTarget = targetBytes
                lastThreads = threads
                startBurn(threads)
            }
            ACTION_STOP -> stopBurn(finished = false)
            null -> {
                // 服务被系统回收后由 START_STICKY 重启：恢复上次的消耗任务
                if (lastTarget > 0) startBurn(lastThreads)
            }
        }
        // 被杀后尽量重建服务，配合电池优化白名单实现息屏持续消耗
        return START_STICKY
    }

    private fun startBurn(threads: Int) {
        if (isRunning) return
        isRunning = true
        totalBytes.set(0)

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(0, 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trafficburner:burn")
            .apply { acquire() }

        // 防止息屏后 WiFi 被系统休眠（移动数据场景无副作用）
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "trafficburner:wifi")
                .apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "无法获取 WiFi 锁: ${e.message}")
        }

        repeat(threads) {
            scope.launch { downloadLoop() }
        }
        scope.launch { statsLoop() }
    }

    private suspend fun downloadLoop() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getInt("source_mode", MODE_AUTO)
        val sources = resolveSources(mode, prefs)
        if (sources.isEmpty()) {
            Log.e(TAG, "下载源为空，停止该线程")
            return
        }
        val buffer = ByteArray(64 * 1024)
        var retryCount = 0
        while (isRunning && totalBytes.get() < targetBytes) {
            var conn: HttpURLConnection? = null
            try {
                // 随机选源 + 随机 Range 起点：防 CDN 缓存、防中间盒按固定 URL 拦截
                val source = sources.random()
                // 自定义直链不知道文件大小，从头拉并加随机参数防缓存
                val rangeStart = if (mode == MODE_CUSTOM) 0L else (0..120_000_000L).random()
                val url = if (mode == MODE_CUSTOM) {
                    val sep = if (source.contains("?")) "&" else "?"
                    "$source${sep}_r=${UUID.randomUUID()}"
                } else source
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Range", "bytes=$rangeStart-")
                    setRequestProperty("Cache-Control", "no-cache")
                    useCaches = false
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw java.io.IOException("HTTP $code from $source")
                }
                conn.inputStream.use { input ->
                    while (isRunning && totalBytes.get() < targetBytes) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        totalBytes.addAndGet(n.toLong())
                    }
                }
                retryCount = 0
            } catch (e: Exception) {
                // 网络抖动或某源被拦：退避后重试（自动换源），最长 30 秒
                retryCount++
                Log.w(TAG, "下载异常(第${retryCount}次): ${e.javaClass.simpleName}: ${e.message}")
                if (isRunning) delay((1_000L * retryCount).coerceAtMost(30_000L))
            } finally {
                conn?.disconnect()
            }
        }
        Log.d(TAG, "下载线程退出, 总量=${totalBytes.get()}, 目标=$targetBytes")
        // 任一线程发现达标，触发收尾
        if (totalBytes.get() >= targetBytes) stopBurn(finished = true)
    }

    private fun resolveSources(mode: Int, prefs: android.content.SharedPreferences): List<String> =
        when (mode) {
            MODE_TENCENT -> listOf(SOURCES[0], SOURCES[2])
            MODE_HUAWEI -> listOf(SOURCES[1], SOURCES[3])
            MODE_CUSTOM -> {
                val url = prefs.getString("custom_url", "")?.trim().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) listOf(url) else emptyList()
            }
            else -> SOURCES
        }

    private suspend fun statsLoop() {
        var last = totalBytes.get()
        while (isRunning) {
            delay(500)
            val now = totalBytes.get()
            val speed = ((now - last) * 2) // 500ms 增量 × 2 = B/s
            last = now
            broadcastUpdate(now, speed, finished = false)
            updateNotification(now, speed)
        }
    }

    private fun stopBurn(finished: Boolean) {
        if (!isRunning) return
        isRunning = false
        // 清掉任务参数，避免 START_STICKY 把已停止的任务重新拉起
        lastTarget = 0L
        scope.coroutineContext.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        broadcastUpdate(totalBytes.get(), 0, finished)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastUpdate(total: Long, speed: Long, finished: Boolean) {
        sendBroadcast(Intent(BROADCAST_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_TOTAL, total)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_FINISHED, finished)
        })
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "流量消耗", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(total: Long, speed: Long): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pct = if (targetBytes == Long.MAX_VALUE) 0
        else ((total * 100 / targetBytes).coerceIn(0, 100)).toInt()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在消耗流量 · ${formatBytes(speed)}/s")
            .setContentText("已用 ${formatBytes(total)} / ${formatBytes(targetBytes)}")
            .setProgress(100, pct, targetBytes == Long.MAX_VALUE)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(total: Long, speed: Long) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(total, speed))
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes == Long.MAX_VALUE -> "∞"
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}
