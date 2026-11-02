package uz.ekranoquvchi.mvp.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.os.BatteryManager
import uz.ekranoquvchi.mvp.ReaderService
import uz.ekranoquvchi.mvp.content.AnnLang
import uz.ekranoquvchi.mvp.content.AnnounceText
import uz.ekranoquvchi.mvp.util.Prefs

/**
 * Tizim holati e'lonlari. Android o'zi ekran o'quvchisiz buni aytmaydi (faqat tovush yoki tebranish beradi), ko'rmaydigan
 * foydalanuvchi uchun esa kerak. Har biri foydalanuvchi tanlovi (Tafsilotlar > Tizim holati e'lonlari):
 *  - quvvatlash ulandi yoki uzildi (batareya foizi bilan);
 *  - ovoz rejimi o'zgardi (ovozsiz, tebranish, ovozli);
 *  - ekran burildi (gorizontal, vertikal);
 *  - ekran yoqildi, qulf ochildi.
 * Matn e'lon tilida aytiladi (ovoz paketi tili, aks holda tizim tili): AnnounceLang.
 */
object SystemStatus {
    private var receiver: BroadcastReceiver? = null
    private var lastRinger = -1
    private var lastLandscape: Boolean? = null

    /** Xizmat ulanganda chaqiriladi: qabul qiluvchi ro'yxatdan o'tadi va hozirgi holat asos qilib olinadi (aytilmaydi). */
    fun start(context: Context) {
        val app = context.applicationContext
        if (receiver != null) return
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            lastRinger = am?.ringerMode ?: -1
            lastLandscape = app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        } catch (_: Throwable) {
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val i = intent ?: return
                try {
                    handle(app, i)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            val f = IntentFilter()
            f.addAction(Intent.ACTION_POWER_CONNECTED)
            f.addAction(Intent.ACTION_POWER_DISCONNECTED)
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            f.addAction(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_USER_PRESENT)
            app.registerReceiver(r, f)
            receiver = r
        } catch (_: Throwable) {
        }
    }

    fun stop(context: Context) {
        val r = receiver ?: return
        receiver = null
        try {
            context.applicationContext.unregisterReceiver(r)
        } catch (_: Throwable) {
        }
    }

    /** Ekran burilishi (ReaderService.onConfigurationChanged): faqat yo'nalish haqiqatan o'zgarganda aytiladi. */
    fun onConfiguration(context: Context, config: Configuration) {
        val land = when (config.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> true
            Configuration.ORIENTATION_PORTRAIT -> false
            else -> return
        }
        val before = lastLandscape
        lastLandscape = land
        if (before == null || before == land) return
        val app = context.applicationContext
        if (Prefs.annOrientation(app)) say(app) { AnnounceText.orientation(it, land) }
    }

    private fun handle(app: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_POWER_CONNECTED -> if (Prefs.annCharger(app)) {
                val level = batteryLevel(app)
                say(app) { AnnounceText.charger(it, true, level) }
            }
            Intent.ACTION_POWER_DISCONNECTED -> if (Prefs.annCharger(app)) {
                val level = batteryLevel(app)
                say(app) { AnnounceText.charger(it, false, level) }
            }
            AudioManager.RINGER_MODE_CHANGED_ACTION -> if (Prefs.annRinger(app)) onRinger(app)
            Intent.ACTION_SCREEN_ON -> if (Prefs.annScreen(app)) say(app) { AnnounceText.screenOn(it) }
            Intent.ACTION_USER_PRESENT -> if (Prefs.annScreen(app)) say(app) { AnnounceText.unlocked(it) }
        }
    }

    /** Ovoz rejimi xabari bir necha marta kelishi mumkin: faqat rejim haqiqatan o'zgarganda aytiladi. */
    private fun onRinger(app: Context) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.ringerMode
        if (mode == lastRinger) return
        lastRinger = mode
        say(app) { AnnounceText.ringer(it, mode) }
    }

    private fun batteryLevel(app: Context): Int {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun say(app: Context, build: (AnnLang) -> String) {
        val svc = ReaderService.instance ?: return
        val lang = AnnounceLang.current(app)
        val text = AnnounceLang.shape(app, lang, build(lang))
        if (text.isNotBlank()) svc.announceStatus(text)
    }
}
