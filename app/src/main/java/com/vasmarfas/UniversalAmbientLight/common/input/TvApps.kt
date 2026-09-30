package com.vasmarfas.UniversalAmbientLight.common.input

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.core.graphics.createBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Приложения ТВ для списка на телефоне-пульте: название, пакет и картинка - баннер, как на
 * лаунчере ТВ, а у приложений без баннера иконка. Картинки мелкие и сжатые: список целиком
 * уходит одним сообщением, а их на приставке бывает под сотню.
 */
object TvApps {

    fun list(context: Context): JSONArray {
        val pm = context.packageManager
        val apps = launchable(context).take(MAX_APPS)
        val result = JSONArray()
        for ((info, label) in apps) {
            val entry = JSONObject()
                .put("pkg", info.activityInfo.packageName)
                .put("label", label)
            val banner = info.activityInfo.loadBanner(pm)
            if (banner != null) {
                entry.put("banner", encode(banner, BANNER_WIDTH, BANNER_HEIGHT, Bitmap.CompressFormat.JPEG))
            } else {
                entry.put("icon", encode(info.loadIcon(pm), ICON_SIZE, ICON_SIZE, Bitmap.CompressFormat.PNG))
            }
            result.put(entry)
        }
        return result
    }

    /** Только названия, без картинок: для выбора приложения на самом ТВ этого хватает. */
    fun labels(context: Context): Map<String, String> =
        launchable(context).associate { (info, label) -> info.activityInfo.packageName to label }

    private fun launchable(context: Context): List<Pair<ResolveInfo, String>> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                found.putIfAbsent(info.activityInfo.packageName, info)
            }
        }
        return found.values
            .map { it to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
    }

    fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun label(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun encode(drawable: Drawable, width: Int, height: Int, format: Bitmap.CompressFormat): String {
        val bitmap = createBitmap(width, height)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        bitmap.compress(format, if (format == Bitmap.CompressFormat.JPEG) 80 else 100, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private const val MAX_APPS = 80
    private const val ICON_SIZE = 64
    private const val BANNER_WIDTH = 160
    private const val BANNER_HEIGHT = 90
}
