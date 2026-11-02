package uz.ekranoquvchi.mvp.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import uz.ekranoquvchi.mvp.R
import uz.ekranoquvchi.mvp.ReaderService
import uz.ekranoquvchi.mvp.content.AnnLang
import uz.ekranoquvchi.mvp.content.AnnounceText
import uz.ekranoquvchi.mvp.content.ClockStyle
import uz.ekranoquvchi.mvp.core.AnnounceLang
import uz.ekranoquvchi.mvp.core.TimeAnnouncer
import uz.ekranoquvchi.mvp.util.Prefs

/**
 * Sozlamalar > Tafsilotlar > Vaqt va batareya: soatni har 30 yoki 60 daqiqada aytish (12 yoki 24 soatlik),
 * o'qish paytida kelsa nima qilish, batareya quvvatini har 10 foizda yoki surgich bilan belgilangan qadamda aytish.
 */
class TimeBatteryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.tb_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, TimeBatteryFragment())
                .commit()
        }
    }
}

class TimeBatteryFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        // Sozlamalar xizmat bilan bir xil (qurilma-himoyalangan) faylga yoziladi
        preferenceManager.preferenceDataStore = SharedPrefsStore(ctx.applicationContext, Prefs.get(ctx))
        setPreferencesFromResource(R.xml.preferences_time_battery, rootKey)

        findPreference<Preference>("time_test")?.setOnPreferenceClickListener {
            TimeAnnouncer.speakTimeNow(requireContext())
            true
        }
        findPreference<Preference>("battery_test")?.setOnPreferenceClickListener {
            TimeAnnouncer.speakBatteryNow(requireContext())
            true
        }
        findPreference<Preference>("time_exact_alarm")?.setOnPreferenceClickListener {
            openExactAlarmSettings()
            true
        }
        // Surgich ekran o'quvchi bilan qiyin bo'lishi mumkin: aniq son yozish oynasi ham bor (nutq tezligidagi kabi)
        findPreference<Preference>("battery_step_exact")?.setOnPreferenceClickListener {
            NumberDialog.show(requireContext(), R.string.tb_battery_step, Prefs.BATTERY_STEP, 1..50, 10) {
                refreshStep()
            }
            true
        }
        // Soatni o'qish uslubi: ikki misol ro'yxatda, har birini eshitib ko'rish mumkin
        findPreference<Preference>("style_hear_1")?.setOnPreferenceClickListener {
            speakSample(ClockStyle.PASSED)
            true
        }
        findPreference<Preference>("style_hear_2")?.setOnPreferenceClickListener {
            speakSample(ClockStyle.CLOCK)
            true
        }
        findPreference<ListPreference>(Prefs.TIME_STYLE)?.setOnPreferenceChangeListener { _, newValue ->
            refreshStyle(newValue as? String)
            true
        }
        // Format (12 yoki 24) o'zgarsa, misollar va ko'rsatmalar yangilanadi (qiymat yozilgach)
        findPreference<ListPreference>("time_format")?.setOnPreferenceChangeListener { _, _ ->
            view?.post { refreshStyle(null) }
            true
        }
        refreshStyle(null)
        refreshStep()
    }

    override fun onResume() {
        super.onResume()
        refreshExactAlarm()
        refreshStep()
        // Ovoz yoki til o'zgargan bo'lishi mumkin: e'lon tili va misollar qayta quriladi
        refreshStyle(null)
    }

    private fun languageName(ctx: Context, lang: AnnLang): String = getString(
        when (lang) {
            AnnLang.UZ -> if (AnnounceLang.cyrillic(ctx)) R.string.ann_lang_uz_cyrl else R.string.ann_lang_uz
            AnnLang.RU -> R.string.ann_lang_ru
            AnnLang.EN -> R.string.ann_lang_en
        }
    )

    /**
     * Uslub ro'yxatidagi misollar, ko'rsatmalar va e'lon tilini hozirgi format (12 yoki 24) va til bo'yicha qayta quradi.
     * Misollar aynan e'londa eshitiladigan matn bilan bir xil (AnnounceText), shuning uchun ko'rilgan narsa eshitiladi.
     * @param chosen yangi tanlangan uslub (qiymat hali yozilmagan bo'lsa); null bo'lsa sozlamadagisi olinadi
     */
    private fun refreshStyle(chosen: String?) {
        val ctx = context ?: return
        val lang = AnnounceLang.current(ctx)
        val twelve = Prefs.timeFormat(ctx) == "12"
        fun sample(h: Int, m: Int, twelveHour: Boolean, style: ClockStyle): String =
            AnnounceLang.shape(ctx, lang, AnnounceText.time(lang, h, m, twelveHour, style))
        val first = getString(R.string.tb_style_1, sample(14, 30, twelve, ClockStyle.PASSED))
        val second = getString(R.string.tb_style_2, sample(14, 30, twelve, ClockStyle.CLOCK))
        val list = findPreference<ListPreference>(Prefs.TIME_STYLE)
        if (list != null) {
            list.entries = arrayOf<CharSequence>(first, second)
            val style = chosen ?: Prefs.timeStyle(ctx)
            list.summary = if (style == "clock") second else first
        }
        findPreference<Preference>("style_guide_24")?.summary = getString(
            R.string.tb_guide_24,
            sample(13, 30, false, ClockStyle.PASSED),
            sample(13, 30, false, ClockStyle.CLOCK),
            sample(13, 0, false, ClockStyle.PASSED),
        )
        findPreference<Preference>("style_guide_12")?.summary = getString(
            R.string.tb_guide_12,
            sample(13, 30, true, ClockStyle.PASSED),
            sample(13, 30, true, ClockStyle.CLOCK),
            sample(13, 0, true, ClockStyle.PASSED),
        )
        findPreference<Preference>("style_guide_notes")?.summary = getString(
            R.string.tb_guide_notes,
            sample(14, 5, false, ClockStyle.CLOCK),
            languageName(ctx, lang),
        )
    }

    /** Tanlangan uslubdagi namunani (14:30) hozirgi format va e'lon tilida eshittiradi. */
    private fun speakSample(style: ClockStyle) {
        val ctx = requireContext()
        val lang = AnnounceLang.current(ctx)
        val text = AnnounceLang.shape(ctx, lang, AnnounceText.time(lang, 14, 30, Prefs.timeFormat(ctx) == "12", style))
        val svc = ReaderService.instance
        if (svc != null) svc.announceRaw(text) else Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun refreshStep() {
        val step = Prefs.batteryStep(requireContext())
        findPreference<SeekBarPreference>(Prefs.BATTERY_STEP)?.value = step
        findPreference<Preference>("battery_step_exact")?.summary = getString(R.string.tb_battery_step_exact_summary, step)
    }

    private fun refreshExactAlarm() {
        val p = findPreference<Preference>("time_exact_alarm") ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            p.isVisible = false
            return
        }
        val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        p.summary = getString(if (am.canScheduleExactAlarms()) R.string.tb_exact_on else R.string.tb_exact_off)
    }

    private fun openExactAlarmSettings() {
        val uri = Uri.parse("package:" + requireContext().packageName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri))
                return
            }
        } catch (_: Throwable) {
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (_: Throwable) {
        }
    }
}
