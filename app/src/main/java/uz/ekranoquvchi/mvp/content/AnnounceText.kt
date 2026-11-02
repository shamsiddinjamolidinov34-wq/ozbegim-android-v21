package uz.ekranoquvchi.mvp.content

/** E'lon tili: soat, batareya va tizim holati e'lonlari shu tilda aytiladi (ovoz paketi tiliga yoki tizim tiliga qarab). */
enum class AnnLang { UZ, RU, EN }

/**
 * Soatni o'qish uslubi (Tafsilotlar > Vaqt va batareya):
 *  PASSED: "Soat o'n bir, soat o'n birdan o'ttiz daqiqa o'tdi." (1-misol)
 *  CLOCK:  "Soat o'n bir, soat o'n bir o'ttiz." (2-misol)
 */
enum class ClockStyle { PASSED, CLOCK }

/**
 * E'lon matnlari: soat, batareya, quvvatlash, ovoz rejimi, ekran burilishi va qulf. Raqamlar har doim so'z bilan yoziladi,
 * shunda ovoz dvigateli ularni qanday o'qishini kutib o'tirmaymiz: "to'g'ri o'qish" kafolatlanadi.
 * Faqat uch til qo'llanadi (o'zbekcha lotin, ruscha, inglizcha); o'zbekcha kirill uchun UzScripts orqali o'tkaziladi.
 * Android'ga bog'liq emas, shuning uchun oddiy JVM'da sinash mumkin.
 */
object AnnounceText {

    // ------------------------------------------------------------------ til tanlash

    /** "uz", "uzb", "uz-UZ", "ru_RU", "eng" kabi belgilardan e'lon tilini topadi; qo'llanmaydigan til uchun null. */
    fun langOf(tag: String?): AnnLang? {
        if (tag == null) return null
        val t = tag.trim().lowercase().substringBefore('-').substringBefore('_')
        return when (t) {
            "uz", "uzb", "uzn", "uzs" -> AnnLang.UZ
            "ru", "rus" -> AnnLang.RU
            "en", "eng" -> AnnLang.EN
            else -> null
        }
    }

    /**
     * Qaysi tilda aytiladi: avval ovoz paketining tili (ovoz shu tilni to'g'ri talaffuz qiladi), u qo'llanmasa tizim tili,
     * ikkalasi ham qo'llanmasa ilovaning asosiy tili (o'zbekcha).
     */
    fun pick(voiceTag: String?, systemTag: String?): AnnLang = langOf(voiceTag) ?: langOf(systemTag) ?: AnnLang.UZ

    /** Sozlama qiymati ("passed" yoki "clock") -> uslub; noma'lum qiymat 1-misolga tushadi. */
    fun parseStyle(v: String?): ClockStyle = if (v == "clock") ClockStyle.CLOCK else ClockStyle.PASSED

    // ------------------------------------------------------------------ soat

    /** 12 soatlik tizimda soat: 0 va 12 -> 12, 13 -> 1. */
    private fun hour12(h24: Int): Int = if (h24 % 12 == 0) 12 else h24 % 12

    /**
     * Soatni o'qiladigan matnga aylantiradi.
     * @param hour24 soat (0..23), @param minute daqiqa (0..59)
     * @param twelve true bo'lsa 12 soatlik tizim: soat 1..12 bo'ladi va boshiga kun qismi (ertalab, kunduz...) qo'shiladi
     */
    fun time(lang: AnnLang, hour24: Int, minute: Int, twelve: Boolean, style: ClockStyle): String {
        val h24 = hour24.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        return when (lang) {
            AnnLang.UZ -> uzTime(h24, m, twelve, style)
            AnnLang.RU -> ruTime(h24, m, twelve, style)
            AnnLang.EN -> enTime(h24, m, twelve, style)
        }
    }

    private fun uzPart(h24: Int): String = when (h24) {
        in 4..5 -> "Tongda"
        in 6..11 -> "Ertalab"
        in 12..17 -> "Kunduz"
        in 18..21 -> "Kechqurun"
        else -> "Tunda"
    }

    /**
     * O'zbekcha: aniq soatda faqat soat ("Soat o'n bir."). Boshqa vaqtda avval soat, so'ng:
     *  1-misol: "Soat o'n bir, soat o'n birdan o'ttiz daqiqa o'tdi."
     *  2-misol: "Soat o'n bir, soat o'n bir o'ttiz." (10 daqiqadan kam bo'lsa "nol besh": "soat o'n bir nol besh")
     * Chiqish kelishigi qo'shimchasi barcha son so'zlarida bir xil: -dan (o'n uchdan, o'n to'rtdan, yigirma birdan).
     */
    private fun uzTime(h24: Int, m: Int, twelve: Boolean, style: ClockStyle): String {
        val h = UzNumbers.words(if (twelve) hour12(h24) else h24)
        val lead = if (twelve) uzPart(h24) + " soat" else "Soat"
        if (m == 0) return "$lead $h."
        if (style == ClockStyle.PASSED) {
            return "$lead $h, soat ${h}dan ${UzNumbers.words(m)} daqiqa o'tdi."
        }
        val mm = if (m < 10) "nol " + UzNumbers.words(m) else UzNumbers.words(m)
        return "$lead $h, soat $h $mm."
    }

    // ----- ruscha

    private val RU_ONES_M = arrayOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
        "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
        "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val RU_ONES_F = arrayOf(
        "ноль", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
        "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать",
        "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val RU_TENS = arrayOf(
        "", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто",
    )

    /** Часы: именительный ("два часа"), родительный ("двух часов": "с двух часов") и число без слова "час" (для 2-го вида). */
    private val RU_HOUR_NOM = arrayOf(
        "ноль часов", "час", "два часа", "три часа", "четыре часа", "пять часов", "шесть часов", "семь часов",
        "восемь часов", "девять часов", "десять часов", "одиннадцать часов", "двенадцать часов", "тринадцать часов",
        "четырнадцать часов", "пятнадцать часов", "шестнадцать часов", "семнадцать часов", "восемнадцать часов",
        "девятнадцать часов", "двадцать часов", "двадцать один час", "двадцать два часа", "двадцать три часа",
    )
    private val RU_HOUR_GEN = arrayOf(
        "нуля часов", "часа", "двух часов", "трёх часов", "четырёх часов", "пяти часов", "шести часов", "семи часов",
        "восьми часов", "девяти часов", "десяти часов", "одиннадцати часов", "двенадцати часов", "тринадцати часов",
        "четырнадцати часов", "пятнадцати часов", "шестнадцати часов", "семнадцати часов", "восемнадцати часов",
        "девятнадцати часов", "двадцати часов", "двадцати одного часа", "двадцати двух часов", "двадцати трёх часов",
    )
    private val RU_HOUR_CARD = arrayOf(
        "ноль", "час", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
        "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать",
        "восемнадцать", "девятнадцать", "двадцать", "двадцать один", "двадцать два", "двадцать три",
    )

    /** 0..100 -> слова. fem=true: женский род ("одна минута", "две минуты"). */
    private fun ruNum(n: Int, fem: Boolean): String {
        if (n >= 100) return "сто"
        val ones = if (fem) RU_ONES_F else RU_ONES_M
        if (n < 20) return ones[n]
        val t = RU_TENS[n / 10]
        val r = n % 10
        return if (r == 0) t else t + " " + ones[r]
    }

    private fun ruMinutes(m: Int): String {
        val d = m % 10
        val dd = m % 100
        return if (d == 1 && dd != 11) {
            "минута"
        } else if (d in 2..4 && (dd < 12 || dd > 14)) {
            "минуты"
        } else {
            "минут"
        }
    }

    private fun ruPercent(n: Int): String {
        val d = n % 10
        val dd = n % 100
        return if (d == 1 && dd != 11) {
            "процент"
        } else if (d in 2..4 && (dd < 12 || dd > 14)) {
            "процента"
        } else {
            "процентов"
        }
    }

    private fun ruPart(h24: Int): String = when (h24) {
        in 4..5 -> "На рассвете"
        in 6..11 -> "Утром"
        in 12..17 -> "Днём"
        in 18..21 -> "Вечером"
        else -> "Ночью"
    }

    /**
     * Ruscha: "Время четырнадцать часов, с четырнадцати часов прошло тридцать минут." (1-misol) yoki
     * "Время четырнадцать часов, четырнадцать тридцать." (2-misol); aniq soatda: "Время четырнадцать часов."
     */
    private fun ruTime(h24: Int, m: Int, twelve: Boolean, style: ClockStyle): String {
        val h = if (twelve) hour12(h24) else h24
        val nom = RU_HOUR_NOM[h]
        val lead = if (twelve) ruPart(h24) + ", время" else "Время"
        if (m == 0) return "$lead $nom."
        if (style == ClockStyle.PASSED) {
            val verb = if (m % 10 == 1 && m % 100 != 11) "прошла" else "прошло"
            return "$lead $nom, с ${RU_HOUR_GEN[h]} $verb ${ruNum(m, true)} ${ruMinutes(m)}."
        }
        val mm = if (m < 10) "ноль " + ruNum(m, false) else ruNum(m, false)
        return "$lead $nom, ${RU_HOUR_CARD[h]} $mm."
    }

    // ----- inglizcha

    private val EN_ONES = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val EN_TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** 0..100 -> so'zlar (twenty-one). */
    private fun enNum(n: Int): String {
        if (n >= 100) return "one hundred"
        if (n < 20) return EN_ONES[n]
        val t = EN_TENS[n / 10]
        val r = n % 10
        return if (r == 0) t else t + "-" + EN_ONES[r]
    }

    private fun enPart(h24: Int): String = when (h24) {
        in 4..5 -> "At dawn"
        in 6..11 -> "In the morning"
        in 12..17 -> "During the day"
        in 18..21 -> "In the evening"
        else -> "At night"
    }

    /**
     * Inglizcha: "Time fourteen, thirty minutes past fourteen." (1-misol) yoki "Time fourteen, fourteen thirty." (2-misol).
     * Aniq soatda: 12 soatlik "Time two o'clock.", 24 soatlik "Time fourteen hundred hours."
     */
    private fun enTime(h24: Int, m: Int, twelve: Boolean, style: ClockStyle): String {
        val h = enNum(if (twelve) hour12(h24) else h24)
        val lead = if (twelve) enPart(h24) + ", time" else "Time"
        if (m == 0) return if (twelve) "$lead $h o'clock." else "$lead $h hundred hours."
        if (style == ClockStyle.PASSED) {
            val unit = if (m == 1) "minute" else "minutes"
            return "$lead $h, ${enNum(m)} $unit past $h."
        }
        val mm = if (m < 10) "oh " + enNum(m) else enNum(m)
        return "$lead $h, $h $mm."
    }

    // ------------------------------------------------------------------ batareya

    private fun sentence(sb: StringBuilder): String = if (sb.isEmpty()) "" else sb.append('.').toString()

    /**
     * Batareya holati. level 0..100 (boshqa qiymat: noma'lum, faqat quvvatlash holati aytiladi); charging=true bo'lsa
     * "quvvatlanmoqda" qo'shiladi. Hech narsa aytib bo'lmasa bo'sh matn qaytadi.
     */
    fun battery(lang: AnnLang, level: Int, charging: Boolean): String {
        val known = level in 0..100
        val sb = StringBuilder()
        when (lang) {
            AnnLang.UZ -> {
                if (known) sb.append("Batareya ").append(UzNumbers.words(level)).append(" foiz")
                if (charging) sb.append(if (known) ", quvvatlanmoqda" else "Quvvatlanmoqda")
            }
            AnnLang.RU -> {
                if (known) sb.append("Батарея ").append(ruNum(level, false)).append(' ').append(ruPercent(level))
                if (charging) sb.append(if (known) ", заряжается" else "Заряжается")
            }
            AnnLang.EN -> {
                if (known) sb.append("Battery ").append(enNum(level)).append(" percent")
                if (charging) sb.append(if (known) ", charging" else "Charging")
            }
        }
        return sentence(sb)
    }

    /** Quvvatlash ulandi yoki uzildi, keyin batareya foizi (noma'lum bo'lsa faqat birinchi qism). */
    fun charger(lang: AnnLang, connected: Boolean, level: Int): String {
        val head = when (lang) {
            AnnLang.UZ -> if (connected) "Quvvatlash ulandi" else "Quvvatlash uzildi"
            AnnLang.RU -> if (connected) "Зарядка подключена" else "Зарядка отключена"
            AnnLang.EN -> if (connected) "Charger connected" else "Charger disconnected"
        }
        val bat = battery(lang, level, false)
        return if (bat.isEmpty()) "$head." else "$head. $bat"
    }

    // ------------------------------------------------------------------ tizim holati

    /** mode: 0 = ovozsiz, 1 = tebranish, boshqasi = ovozli (AudioManager.RINGER_MODE_SILENT, _VIBRATE, _NORMAL). */
    fun ringer(lang: AnnLang, mode: Int): String = when (lang) {
        AnnLang.UZ -> when (mode) {
            0 -> "Ovozsiz rejim"
            1 -> "Tebranish rejimi"
            else -> "Ovozli rejim"
        }
        AnnLang.RU -> when (mode) {
            0 -> "Беззвучный режим"
            1 -> "Режим вибрации"
            else -> "Звуковой режим"
        }
        AnnLang.EN -> when (mode) {
            0 -> "Silent mode"
            1 -> "Vibrate mode"
            else -> "Sound mode"
        }
    }

    fun orientation(lang: AnnLang, landscape: Boolean): String = when (lang) {
        AnnLang.UZ -> if (landscape) "Gorizontal ekran" else "Vertikal ekran"
        AnnLang.RU -> if (landscape) "Альбомная ориентация" else "Книжная ориентация"
        AnnLang.EN -> if (landscape) "Landscape" else "Portrait"
    }

    fun screenOn(lang: AnnLang): String = when (lang) {
        AnnLang.UZ -> "Ekran yoqildi"
        AnnLang.RU -> "Экран включён"
        AnnLang.EN -> "Screen on"
    }

    fun unlocked(lang: AnnLang): String = when (lang) {
        AnnLang.UZ -> "Qulf ochildi"
        AnnLang.RU -> "Разблокировано"
        AnnLang.EN -> "Unlocked"
    }
}
