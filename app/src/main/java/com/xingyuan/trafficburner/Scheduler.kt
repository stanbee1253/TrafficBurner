package com.xingyuan.trafficburner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 每月定时消耗：用精确闹钟（setExactAndAllowWhileIdle）触发广播，
 * 再由广播启动前台服务 —— Android 12+ 允许「精确闹钟触发的动作」从后台启动前台服务。
 */
object Scheduler {

    const val ACTION_FIRE = "com.xingyuan.trafficburner.SCHEDULE_FIRE"
    const val EXTRA_TARGET_BYTES = "target_bytes"
    const val EXTRA_THREADS = "threads"

    private const val REQ = 2001

    fun pendingIntent(context: Context, targetBytes: Long, threads: Int): PendingIntent {
        val i = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TARGET_BYTES, targetBytes)
            putExtra(EXTRA_THREADS, threads)
        }
        return PendingIntent.getBroadcast(
            context, REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 下一次触发时间；若本月该时刻已过则顺延到下个月。日期超过当月天数时自动取整月最后一天 */
    fun nextTime(day: Int, hour: Int, minute: Int): Long {
        fun build(monthOffset: Int): Calendar =
            Calendar.getInstance().apply {
                add(Calendar.MONTH, monthOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                val maxDay = getActualMaximum(Calendar.DAY_OF_MONTH)
                set(Calendar.DAY_OF_MONTH, day.coerceIn(1, maxDay))
            }
        var cal = build(0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal = build(1)
        return cal.timeInMillis
    }

    /** 注册闹钟，返回下次触发时间 */
    fun schedule(
        context: Context, day: Int, hour: Int, minute: Int,
        targetBytes: Long, threads: Int
    ): Long {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val time = nextTime(day, hour, minute)
        val pi = pendingIntent(context, targetBytes, threads)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 用户关闭了精确闹钟权限：退化为不精确定时（可能延后几分钟）
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi)
        }
        return time
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, 0L, 4))
    }

    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date(millis))
}
