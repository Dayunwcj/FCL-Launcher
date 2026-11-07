package com.doubao.mclauncher.launch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.doubao.mclauncher.R

/**
 * ============================================================
 * LaunchService —— 前台服务
 * ============================================================
 *
 * 作用：MC 跑起来后，进程一旦被系统判定为“后台”就可能被杀，
 * 那样 JVM 直接崩。用它把进程标成前台服务，系统就不会轻易回收，
 * 顺便在下拉通知栏显示「游戏运行中」。
 *
 * 注意：Android 14（API 34）要求声明 foregroundServiceType，
 * 我们在 Manifest 里写的是 dataSync（下载 + 本地 IO 的场景）。
 */
class LaunchService : Service() {

    companion object {
        private const val CHANNEL_ID = "mc_launch"
        private const val NOTIFY_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, LaunchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LaunchService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFY_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android 8.0+ 必须先建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_launch),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Minecraft 运行状态"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Minecraft 正在运行，请勿切换到后台太久")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
