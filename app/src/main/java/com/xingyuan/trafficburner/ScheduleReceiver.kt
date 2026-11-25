package com.xingyuan.trafficburner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/** 定时到点：拉起消耗服务，并顺手排好下个月 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        val target = intent.getLongExtra(Scheduler.EXTRA_TARGET_BYTES, 0L)
        val threads = intent.getIntExtra(Scheduler.EXTRA_THREADS, 4)
        if (target <= 0L) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trafficburner:schedule")
        wl.acquire(30_000L)
        try {
            val svc = Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START
                putExtra(DownloadService.EXTRA_TARGET_BYTES, target)
                putExtra(DownloadService.EXTRA_THREADS, threads)
            }
            ContextCompat.startForegroundService(context, svc)
            Log.d("TrafficBurner", "定时触发，目标=${target / 1073741824.0} GB")
        } catch (e: Exception) {
            Log.e("TrafficBurner", "定时启动服务失败: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            val prefs = context.getSharedPreferences("burn_state", Context.MODE_PRIVATE)
            if (prefs.getBoolean("sched_enabled", false)) {
                val day = prefs.getInt("sched_day", 28)
                val hour = prefs.getInt("sched_hour", 21)
                val minute = prefs.getInt("sched_minute", 0)
                Scheduler.schedule(context, day, hour, minute, target, threads)
            }
            if (wl.isHeld) wl.release()
        }
    }
}
