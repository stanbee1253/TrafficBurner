package com.xingyuan.trafficburner

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var etTarget: TextInputEditText
    private lateinit var etThreads: TextInputEditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvSpeed: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNetwork: TextView
    private lateinit var tvBattery: TextView
    private lateinit var btnBattery: Button
    private lateinit var progress: ProgressBar

    private lateinit var spSource: android.widget.Spinner
    private lateinit var tilCustomUrl: com.google.android.material.textfield.TextInputLayout
    private lateinit var etCustomUrl: TextInputEditText
    private lateinit var etDay: TextInputEditText
    private lateinit var etHour: TextInputEditText
    private lateinit var etMinute: TextInputEditText
    private lateinit var etSchedTarget: TextInputEditText
    private lateinit var swSchedule: android.widget.Switch
    private lateinit var btnSaveSchedule: Button
    private lateinit var tvSchedule: TextView

    private lateinit var prefs: android.content.SharedPreferences

    private var targetBytes = 0L

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val total = intent.getLongExtra(DownloadService.EXTRA_TOTAL, 0)
            val speed = intent.getLongExtra(DownloadService.EXTRA_SPEED, 0)
            val finished = intent.getBooleanExtra(DownloadService.EXTRA_FINISHED, false)

            tvSpeed.text = "${formatBytes(speed)}/s"
            tvTotal.text = formatBytes(total)
            if (targetBytes > 0) {
                progress.progress = ((total * 100 / targetBytes).coerceIn(0, 100)).toInt()
            }
            if (finished) {
                tvStatus.text = "已达目标，自动停止。共消耗 ${formatBytes(total)}"
                setRunningUi(false)
            } else if (!DownloadService.isRunning) {
                tvStatus.text = "已停止，共消耗 ${formatBytes(total)}"
                setRunningUi(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTarget = findViewById(R.id.etTarget)
        etThreads = findViewById(R.id.etThreads)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvTotal = findViewById(R.id.tvTotal)
        tvStatus = findViewById(R.id.tvStatus)
        tvNetwork = findViewById(R.id.tvNetwork)
        tvBattery = findViewById(R.id.tvBattery)
        btnBattery = findViewById(R.id.btnBattery)
        progress = findViewById(R.id.progress)
        spSource = findViewById(R.id.spSource)
        tilCustomUrl = findViewById(R.id.tilCustomUrl)
        etCustomUrl = findViewById(R.id.etCustomUrl)
        etDay = findViewById(R.id.etDay)
        etHour = findViewById(R.id.etHour)
        etMinute = findViewById(R.id.etMinute)
        etSchedTarget = findViewById(R.id.etSchedTarget)
        swSchedule = findViewById(R.id.swSchedule)
        btnSaveSchedule = findViewById(R.id.btnSaveSchedule)
        tvSchedule = findViewById(R.id.tvSchedule)

        prefs = getSharedPreferences("burn_state", Context.MODE_PRIVATE)

        requestNotificationPermission()
        refreshNetworkLabel()
        refreshBatteryLabel()
        setupSourceUi()
        setupScheduleUi()

        btnBattery.setOnClickListener {
            if (isIgnoringBatteryOptimizations()) {
                Toast.makeText(this, "已在白名单中，息屏可继续消耗", Toast.LENGTH_SHORT).show()
            } else {
                requestIgnoreBatteryOptimizations()
            }
        }

        // 界面被系统回收后重建时，恢复上次的设置，避免看起来像"初始界面"
        etTarget.setText(prefs.getString("target_gb_text", "1"))
        etThreads.setText(prefs.getString("threads_text", "4"))
        targetBytes = prefs.getLong("target_bytes", 0L)

        btnStart.setOnClickListener {
            val targetGb = etTarget.text.toString().toDoubleOrNull() ?: 0.0
            if (targetGb <= 0.0) {
                Toast.makeText(this, "请输入有效的目标流量（GB）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val threads = (etThreads.text.toString().toIntOrNull() ?: 4).coerceIn(1, 8)
            targetBytes = (targetGb * 1073741824).toLong()

            prefs.edit()
                .putString("target_gb_text", etTarget.text.toString())
                .putString("threads_text", etThreads.text.toString())
                .putLong("target_bytes", targetBytes)
                .apply()
            saveSourcePrefs()

            if (!isMobileData()) {
                Toast.makeText(this, "当前不是移动数据网络，请注意 WiFi 不消耗套餐流量", Toast.LENGTH_LONG).show()
            }

            val intent = Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START
                putExtra(DownloadService.EXTRA_TARGET_BYTES, targetBytes)
                putExtra(DownloadService.EXTRA_THREADS, threads)
            }
            ContextCompat.startForegroundService(this, intent)
            setRunningUi(true)
            tvStatus.text = "运行中…"
            progress.progress = 0
        }

        btnStop.setOnClickListener {
            startService(Intent(this, DownloadService::class.java).apply {
                action = DownloadService.ACTION_STOP
            })
            setRunningUi(false)
            tvStatus.text = "已手动停止"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkLabel()
        setRunningUi(DownloadService.isRunning)
        // 服务还在跑但界面是重建的：把状态文案也恢复成运行态
        if (DownloadService.isRunning && tvStatus.text.toString() == "未开始") {
            tvStatus.text = "运行中…"
        }
        val filter = IntentFilter(DownloadService.BROADCAST_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }

    // ---------- 下载源 / 定向 ----------

    private fun setupSourceUi() {
        val mode = prefs.getInt("source_mode", DownloadService.MODE_AUTO)
        spSource.setSelection(mode.coerceIn(0, 3))
        etCustomUrl.setText(prefs.getString("custom_url", ""))
        tilCustomUrl.visibility =
            if (mode == DownloadService.MODE_CUSTOM) android.view.View.VISIBLE else android.view.View.GONE
        spSource.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                tilCustomUrl.visibility =
                    if (position == DownloadService.MODE_CUSTOM) android.view.View.VISIBLE
                    else android.view.View.GONE
                prefs.edit().putInt("source_mode", position).apply()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun saveSourcePrefs() {
        val mode = spSource.selectedItemPosition
        val url = etCustomUrl.text.toString().trim()
        if (mode == DownloadService.MODE_CUSTOM &&
            !(url.startsWith("http://") || url.startsWith("https://"))
        ) {
            Toast.makeText(this, "自定义直链必须以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putInt("source_mode", mode).putString("custom_url", url).apply()
    }

    // ---------- 每月定时 ----------

    private fun setupScheduleUi() {
        etDay.setText(prefs.getInt("sched_day", 28).toString())
        etHour.setText(prefs.getInt("sched_hour", 21).toString())
        etMinute.setText(prefs.getInt("sched_minute", 0).toString())
        etSchedTarget.setText(prefs.getFloat("sched_target_gb", 1f).toString())
        swSchedule.isChecked = prefs.getBoolean("sched_enabled", false)
        refreshScheduleLabel()

        btnSaveSchedule.setOnClickListener {
            val day = etDay.text.toString().toIntOrNull() ?: 0
            val hour = etHour.text.toString().toIntOrNull() ?: 0
            val minute = etMinute.text.toString().toIntOrNull() ?: 0
            val gb = etSchedTarget.text.toString().toDoubleOrNull() ?: 0.0
            when {
                day !in 1..31 -> Toast.makeText(this, "日期必须是 1-31", Toast.LENGTH_SHORT).show()
                hour !in 0..23 -> Toast.makeText(this, "小时必须是 0-23", Toast.LENGTH_SHORT).show()
                minute !in 0..59 -> Toast.makeText(this, "分钟必须是 0-59", Toast.LENGTH_SHORT).show()
                gb <= 0.0 -> Toast.makeText(this, "目标流量必须大于 0", Toast.LENGTH_SHORT).show()
                else -> {
                    saveSourcePrefs()
                    val targetBytesSched = (gb * 1073741824).toLong()
                    val threads = (etThreads.text.toString().toIntOrNull() ?: 4).coerceIn(1, 8)
                    prefs.edit()
                        .putInt("sched_day", day)
                        .putInt("sched_hour", hour)
                        .putInt("sched_minute", minute)
                        .putFloat("sched_target_gb", gb.toFloat())
                        .putInt("sched_threads", threads)
                        .putBoolean("sched_enabled", swSchedule.isChecked)
                        .apply()
                    if (swSchedule.isChecked) {
                        val next = Scheduler.schedule(this, day, hour, minute, targetBytesSched, threads)
                        Toast.makeText(
                            this,
                            "已启用：下次 ${Scheduler.formatTime(next)} 自动消耗 ${gb}GB",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Scheduler.cancel(this)
                        Toast.makeText(this, "已关闭定时", Toast.LENGTH_SHORT).show()
                    }
                    refreshScheduleLabel()
                }
            }
        }
    }

    private fun refreshScheduleLabel() {
        if (!prefs.getBoolean("sched_enabled", false)) {
            tvSchedule.text = "未启用定时"
            return
        }
        val day = prefs.getInt("sched_day", 28)
        val hour = prefs.getInt("sched_hour", 21)
        val minute = prefs.getInt("sched_minute", 0)
        val gb = prefs.getFloat("sched_target_gb", 1f)
        val next = Scheduler.nextTime(day, hour, minute)
        tvSchedule.text = "已启用：每月 ${day} 号 %02d:%02d，目标 ${gb}GB\n下次执行：${Scheduler.formatTime(next)}"
            .format(hour, minute)
    }

    private fun setRunningUi(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        etTarget.isEnabled = !running
        etThreads.isEnabled = !running
    }

    private fun isMobileData(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun refreshNetworkLabel() {
        if (isMobileData()) {
            tvNetwork.text = "当前网络：移动数据 ✓"
            tvNetwork.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvNetwork.text = "当前网络：非移动数据（WiFi 或无网络）"
            tvNetwork.setTextColor(Color.parseColor("#C62828"))
        }
    }

    /** Doze 会掐断网络，只有进了电池优化白名单，息屏后才能持续消耗 */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryLabel() {
        if (isIgnoringBatteryOptimizations()) {
            tvBattery.text = "息屏后台运行：已开启 ✓"
            tvBattery.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvBattery.text = "息屏后台运行：未开启，锁屏后会停止消耗"
            tvBattery.setTextColor(Color.parseColor("#C62828"))
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // 部分国产 ROM 不支持直接申请，退回到设置列表页手动开
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }
}
