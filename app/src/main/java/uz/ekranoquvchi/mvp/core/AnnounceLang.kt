package uz.ekranoquvchi.mvp.core

import android.content.Context
import uz.ekranoquvchi.mvp.ReaderService
import uz.ekranoquvchi.mvp.content.AnnLang
import uz.ekranoquvchi.mvp.content.AnnounceText
import uz.ekranoquvchi.mvp.content.UzScripts
import uz.ekranoquvchi.mvp.util.Prefs
import java.util.Locale

/**
 * Soat, batareya va tizim holati e'lonlari qaysi tilda va qaysi yozuvda aytilishini aniqlaydi.
 * Qoida: avval ovoz paketining tili (ovoz shu tilni to'g'ri talaffuz qiladi), u o'zbekcha, ruscha yoki inglizcha bo'lmasa
 * tizim tili, u ham bo'lmasa ilovaning asosiy tili (o'zbekcha). Misol: telefon tili inglizcha, lekin ovoz paketi o'zbekcha
 * bo'lsa, e'lon o'zbekcha aytiladi; telefon tili o'zbekcha, ovoz ruscha bo'lsa, ruscha.
 */
object AnnounceLang {

    /** Hozirgi e'lon tili. */
    fun current(c: Context): AnnLang {
        val voice = try {
            ReaderService.instance?.voiceLanguageTag()
        } catch (_: Throwable) {
            null
        }
        return AnnounceText.pick(voice, Locale.getDefault().language)
    }

    /**
     * O'zbekcha e'lon kirillda yoziladimi: telefon tili o'zbekcha kirill bo'lsa va hozirgi dvigatel kirillni o'qiy olsa.
     * Aks holda lotinda yoziladi (hamma o'zbekcha ovoz uni o'qiydi).
     */
    fun cyrillic(c: Context): Boolean {
        val l = Locale.getDefault()
        if (l.language != "uz" || !l.script.equals("Cyrl", ignoreCase = true)) return false
        val engine = try {
            ReaderService.instance?.effectiveEnginePackage()
        } catch (_: Throwable) {
            null
        }
        return Prefs.engineReadsCyrillic(c, engine)
    }

    /** AnnounceText lotincha matn chiqaradi; kerak bo'lsa (o'zbekcha va kirill) kirillga o'tkaziladi. */
    fun shape(c: Context, lang: AnnLang, latin: String): String =
        if (lang == AnnLang.UZ && cyrillic(c)) UzScripts.latinToCyrillic(latin) else latin

    /** Soat matni: sozlamadagi format (12 yoki 24) va uslub (1- yoki 2-misol) bilan, e'lon tilida. */
    fun timeText(c: Context, hour24: Int, minute: Int): String {
        val lang = current(c)
        val text = AnnounceText.time(
            lang, hour24, minute, Prefs.timeFormat(c) == "12", AnnounceText.parseStyle(Prefs.timeStyle(c)),
        )
        return shape(c, lang, text)
    }

    /** Batareya matni, e'lon tilida. level 0..100 dan tashqarida bo'lsa (noma'lum) faqat quvvatlash holati aytiladi. */
    fun batteryText(c: Context, level: Int, charging: Boolean): String {
        val lang = current(c)
        return shape(c, lang, AnnounceText.battery(lang, level, charging))
    }
}
