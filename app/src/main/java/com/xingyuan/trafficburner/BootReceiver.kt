package com.xingyuan.trafficburner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 重启 / 应用升级后重新注册定时闹钟（闹钟在关机后会全部失效） */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = context.getSharedPreferences("burn_state", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sched_enabled", false)) return

        val day = prefs.getInt("sched_day", 28)
        val hour = prefs.getInt("sched_hour", 21)
        val minute = prefs.getInt("sched_minute", 0)
        val gb = prefs.getFloat("sched_target_gb", 1f).toDouble()
        val threads = prefs.getInt("sched_threads", 4)
        val targetBytes = (gb * 1073741824).toLong()
        Scheduler.schedule(context, day, hour, minute, targetBytes, threads)
    }
}
