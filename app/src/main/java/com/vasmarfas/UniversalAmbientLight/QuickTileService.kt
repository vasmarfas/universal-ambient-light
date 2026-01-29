package com.vasmarfas.UniversalAmbientLight

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.TaskStackBuilder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.vasmarfas.UniversalAmbientLight.common.BootActivity
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.R

@RequiresApi(api = Build.VERSION_CODES.N)
class QuickTileService : TileService() {
    private val REMOVE_LISTENER_DELAY = 1000 * 10 // 10 second delay to remove listener
    private val mHandle = Handler(Looper.getMainLooper())

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tile = qsTile
            if (tile != null) {
                val running = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TAG, false)
                val error = intent.getStringExtra(ScreenGrabberService.BROADCAST_ERROR)
                tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.updateTile()
                if (error != null) {
                    Toast.makeText(baseContext, error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val unregisterReceiverRunner = Runnable {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver)
        mIsListening = false
    }

    override fun onStartListening() {
        super.onStartListening()
        mHandle.removeCallbacksAndMessages(null)
        if (!mIsListening) {
            LocalBroadcastManager.getInstance(this).registerReceiver(
                mMessageReceiver, IntentFilter(ScreenGrabberService.BROADCAST_FILTER)
            )
            mIsListening = true
        }
        if (isServiceRunning) {
            val intent = Intent(this, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.GET_STATUS
            startService(intent)
        } else {
            val tile = qsTile
            if (tile != null) {
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        super.onTileRemoved()
        mHandle.postDelayed(unregisterReceiverRunner, REMOVE_LISTENER_DELAY.toLong())
    }

    override fun onDestroy() {
        super.onDestroy()
        mIsListening = false
    }

    override fun onClick() {
        val tile = qsTile
        if (tile != null) {
            tile.updateTile()
            val tileState = tile.state
            if (tileState == Tile.STATE_ACTIVE) {
                AnalyticsHelper.logQuickTileUsed(this)
                val intent = Intent(this, ScreenGrabberService::class.java)
                intent.action = ScreenGrabberService.ACTION_EXIT
                startService(intent)
            } else {
                AnalyticsHelper.logQuickTileUsed(this)
                val runner = Runnable {
                    val setupStarted = startSetupIfNeeded()

                    if (!setupStarted) {
                        val i = Intent(this, BootActivity::class.java)
                        i.addFlags(
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                                    or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                    or Intent.FLAG_ACTIVITY_NO_HISTORY
                        )
                        startActivity(i)
                    }
                }
                if (isLocked) {
                    unlockAndRun(runner)
                } else {
                    runner.run()
                }
            }
        }
    }

    private val isServiceRunning: Boolean
        get() {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (ScreenGrabberService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }

    /** Starts the Settings Activity if connection settings are missing
     *
     * @return true if setup was started
     */
    private fun startSetupIfNeeded(): Boolean {
            val preferences = Preferences(applicationContext)
        if (TextUtils.isEmpty(
                preferences.getString(
                    R.string.pref_key_host,
                    null
                )
            ) || preferences.getInt(R.string.pref_key_port, -1) == -1
        ) {
            val settingsIntent = Intent(this, MainActivity::class.java)
            settingsIntent.action = Intent.ACTION_MAIN
            settingsIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Use TaskStackBuilder to make sure the MainActivity opens when the SettingsActivity is closed
            TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(settingsIntent)
                .startActivities()

            val closeIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            sendBroadcast(closeIntent)

            return true
        }

        return false
    }

    companion object {
        private var mIsListening = false

        val isListening: Boolean
            get() = mIsListening
    }
}
