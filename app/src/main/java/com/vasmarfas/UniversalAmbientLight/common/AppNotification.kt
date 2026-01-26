package com.vasmarfas.UniversalAmbientLight.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.vasmarfas.UniversalAmbientLight.R

class AppNotification(ctx: Context, manager: NotificationManager) {
    private val NOTIFICATION_CHANNEL_ID = "com.vasmarfas.UniversalAmbientLight.notification"
    private val NOTIFICATION_CHANNEL_LABEL: String
    private val NOTIFICATION_TITLE: String
    private val NOTIFICATION_DESCRIPTION: String
    private val PENDING_INTENT_REQUEST_CODE = 0
    private val mNotificationManager: NotificationManager
    private val mContext: Context
    private var mAction: Notification.Action? = null

    init {
        mNotificationManager = manager
        mContext = ctx
        NOTIFICATION_TITLE = mContext.getString(R.string.app_name)
        NOTIFICATION_DESCRIPTION = mContext.getString(R.string.notification_description)
        NOTIFICATION_CHANNEL_LABEL = mContext.getString(R.string.notification_channel_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager.createNotificationChannel(makeChannel())
        }
    }

    private fun getPendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    fun setAction(code: Int, label: String?, intent: Intent?) {
        val pendingIntent = PendingIntent.getService(
            mContext, code,
            intent!!, getPendingIntentFlags()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mAction = Notification.Action.Builder(
                Icon.createWithResource(mContext, R.drawable.ic_notification_icon),
                label,
                pendingIntent
            ).build()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            mAction = Notification.Action.Builder(
                R.drawable.ic_notification_icon,
                label,
                pendingIntent
            ).build()
        }
    }

    fun buildNotification(): Notification {
        var pIntent: PendingIntent? = null
        val intent = mContext.packageManager.getLaunchIntentForPackage(mContext.packageName)
        if (intent != null) {
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            pIntent = PendingIntent.getActivity(
                mContext, PENDING_INTENT_REQUEST_CODE,
                intent, getPendingIntentFlags()
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_DESCRIPTION)
            if (mAction != null) {
                builder.addAction(mAction)
            }
            if (pIntent != null) {
                builder.setContentIntent(pIntent)
            }
            return builder.build()
        } else {
            val builder = NotificationCompat.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                .setVibrate(null)
                .setSound(null)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_DESCRIPTION)
            if (pIntent != null) {
                builder.setContentIntent(pIntent)
            }
            return builder.build()
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun makeChannel(): NotificationChannel {
        val notificationChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_LABEL, NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationChannel.description = NOTIFICATION_CHANNEL_LABEL
        notificationChannel.enableVibration(false)
        notificationChannel.setSound(null, null)
        return notificationChannel
    }
}
