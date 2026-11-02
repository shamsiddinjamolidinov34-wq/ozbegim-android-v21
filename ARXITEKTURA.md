# O'zbegim 0.9.1: arxitektura qaydlari

Bu hujjat uch muammoning sababini, kiritilgan tuzatishni va telefonda sinash tartibini bayon qiladi. Kod namunalari
haqiqiy fayllardan olingan.

## Holat (halol ma'lumot)

- 0.9.1 tuzatishlari (5-bo'lim) ham Gradle bilan qurilmadi (kompilyator yo'q) va telefonda sinalmadi. Qavslar balansi, nomlarning
  bir marta e'lon qilinishi va chaqiruvlar qo'lda tekshirildi.
- 5-bo'limdagi Telegram sabablarining bir qismi kod o'qishdan aniq, bir qismi taxmin: yuborilgan jurnalda Telegram yozuvi yo'q edi.
  Qaysi biri taxmin ekani 5.1'da alohida belgilangan.
- Tuzatishlar v12 kodiga kiritildi va qo'lda tekshirildi (qavslar balansi, nomlar, chaqiruvlar).
- Gradle bilan qurilmadi (tahrir muhitida tarmoq yo'q) va telefonda sinalmadi. Qurishda xato chiqsa, xato matnini yuboring.
- Ishora haqidagi bir taxmin (pastda, 3-bo'lim) tekshirilmagan; shu sababli ilovalar menyusi bir necha yo'l bilan ochiladi va
  qaysi yo'l ishlagani Xatolar jurnaliga yoziladi.

## 1. Fokus olish va elementni aniqlash

Teginib o'rganishda tizim barmoq ostidagi tugmani o'zi aniqlaydi va `TYPE_VIEW_HOVER_ENTER` hodisasiga manba qilib beradi
(`event.source`). Hodisada koordinata yo'q, shuning uchun "eng yaqin koordinata" qidiruvi uchun ma'lumot yetishmaydi.
`onMotionEvent` yordam bermaydi: Android hujjatiga ko'ra teginib o'rganish yoqiq bo'lsa, u sensor hodisalarini olmaydi.
Koordinata kerak bo'lsa, keyingi bosqichda `TouchInteractionController` (Android 11+) sinab ko'riladi; telefonsiz buni xavfsiz
qo'shib bo'lmaydi. `findAccessibilityNodeInfosByViewId` ham mos emas: id lar ilovaga xos va ko'pincha yo'q.

Shuning uchun aniqlash yorliq va ahamiyat qoidasiga tayanadi (`core/Navigator.kt`):

```kotlin
fun isActionable(n: AccessibilityNodeInfo): Boolean =
    n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable || (n.isFocusable && n.childCount == 0)

fun isMeaningful(n: AccessibilityNodeInfo): Boolean {
    if (!n.isVisibleToUser) return false
    return hasOwnLabel(n) || isActionable(n) || n.isFocusable || n.isScreenReaderFocusable
}
```

Yangi: teginilgan tugunda ham, ota-onalarida ham yorliq bo'lmasa (jim qolardi), tugun kichik konteyner bo'lsa, uning ichidagi
birinchi ahamiyatli tugun olinadi:

```kotlin
fun unitFor(start: AccessibilityNodeInfo, descendantAreaLimit: Long = 0L): AccessibilityNodeInfo? {
    // ... bosiladigan ota-ona, tugunning o'zi, yorliqli ota-ona ...
    if (descendantAreaLimit > 0L && start.childCount > 0) {
        val r = Rect()
        start.getBoundsInScreen(r)
        if (r.width().toLong() * r.height().toLong() <= descendantAreaLimit) return firstMeaningfulDescendant(start)
    }
    return null
}
```

Chegara ekran maydonining 30 foizi (`ReaderService.processHover`): katta bo'sh joyga tegilganda tasodifiy bola o'qilmasligi uchun.

## 2. Xabarlarning o'tkazib yuborilishi (Telegram kanallari, ro'yxatlar)

Sabab: postning matni konteynerning o'zida turadi, ichida esa tugmalar va ba'zan oddiy matnli bolalar (ko'rishlar soni, vaqt)
bo'ladi. Eski qoida ichida oddiy matnli bola bo'lsa konteynerni fokus nuqtasi deb hisoblamasdi: asosiy matn tushib qolar,
tugmalar va bolalar o'qilardi.

Tuzatish: konteynerning o'z yorlig'i hech qachon tashlab ketilmaydi. Takror o'qilmasligi uchun matni ota-onasining
yorlig'ida to'liq bor bola alohida nuqta bo'lmaydi.

```kotlin
fun isLabeledUnit(n: AccessibilityNodeInfo): Boolean {
    if (!hasOwnLabel(n)) return false
    val count = n.childCount
    if (count == 0) return true
    return !(n.isScrollable || n.collectionInfo != null || count > 12)
}

fun coveredByLabeledParent(n: AccessibilityNodeInfo): Boolean {
    if (n.isScreenReaderFocusable || n.childCount != 0) return false
    val own = labelText(n)
    if (own.isEmpty()) return false
    var p: AccessibilityNodeInfo? = n.parent
    var depth = 0
    while (p != null && depth++ < 2) {
        if (p.isScrollable || p.collectionInfo != null || isActionable(p)) return false
        if (isLabeledUnit(p)) {
            val pl = labelText(p)
            if (pl.length >= own.length && pl.contains(own, ignoreCase = true)) return true
        }
        p = p.parent
    }
    return false
}
```

Tartib: `collect()` va `nextUnit()` daraxtni chuqurlik bo'yicha (DFS) bosib o'tadi, shuning uchun xabar matni, keyin vaqt, keyin
tugmalar ketma-ket keladi. Ro'yxat qatorlarini bitta obyektga birlashtirish (TalkBack "top-level scroll item") qilinmadi:
bu hamma ro'yxatning o'qilishini o'zgartiradi va telefonsiz tekshirib bo'lmaydi. Hozirgi ketma-ketlik hech narsani
tashlab ketmaydi.

Chuqur joylashgan matn: yorlig'siz konteynerning matnini yig'uvchi `NodeDescriber.rollUp` chuqurligi 6 gacha, soni 8 gacha
(tezkor rejimda 3 va 4), tugunlar byudjeti 90 (tezkor rejimda 40).

Aylantirish (`TYPE_VIEW_SCROLLED`, `ReaderService.onScrolled/handleScrolled`): hodisa soniyasiga o'nlab marta keladi, shuning
uchun har hodisada faqat kutish vaqti suriladi, ish aylantirish tinchigach (160 ms) bir marta bajariladi:

```kotlin
private fun onScrolled(event: AccessibilityEvent) {
    if (SystemClock.uptimeMillis() < ownScrollUntil) return      // xizmatning o'z aylantirishi: fokusni o'zi boshqaradi
    if (scrollSource == null) scrollSource = event.source
    main.removeCallbacks(scrollTask)
    main.postDelayed(scrollTask, SCROLL_SETTLE_MS)
}

private fun handleScrolled() {
    // ... navigator.clearCaches(); foydalanuvchi so'nggi 4 soniyada tekkan bo'lsa ...
    val focusGone = last == null || !last.refresh() || !last.isVisibleToUser
    if (!focusGone) return                                       // fokus ko'rinib tursa, tegmaymiz
    val first = navigator.collect(source).firstOrNull { navigator.matches(it, navigator.mode) } ?: return
    focus(first)
}
```

## 3. Ishoralar va global amallar

### Joriy arxitektura

`accessibility_service_config.xml` teginib o'rganish va ko'p barmoqli ishoralarni so'raydi:

```xml
android:accessibilityFlags="flagDefault|flagReportViewIds|flagRequestTouchExplorationMode|flagRequestFilterKeyEvents|flagRetrieveInteractiveWindows|flagEnableAccessibilityVolume|flagRequestMultiFingerGestures"
android:canPerformGestures="true"
android:canRequestTouchExplorationMode="true"
android:canRetrieveWindowContent="true"
```

Teginib o'rganish yoqiq bo'lganda tizim barcha barmoq harakatini o'zi ushlab oladi va faqat tayyor ishora sifatida
`onGesture(gestureId)` ga beradi. Bosh ekran (launcher) xom barmoq harakatini ko'rmaydi: bu "to'liq intercept"ning o'zi,
alohida kod kerak emas. `onGesture` -> `GestureMap` (ishora -> amal) -> `perform(amal)`.

`onMotionEvent` bu yerda ishora ushlash uchun yaramaydi: u faqat kuzatish uchun (Android 14+) va teginib o'rganish yoqiq
bo'lsa sensor hodisalarini bermaydi.

Ikki barmoqli surishni ilovaning o'ziga o'tkazish kerak bo'lsa (qulf ekrani), `FLAG_REQUEST_2_FINGER_PASSTHROUGH` ishlatiladi
(`ReaderService.updatePassthrough`, sozlama `two_finger_pass`).

### Ilovalar menyusi muammosi

Eski kod bosh ekranda ikki barmoq bilan tepaga surishni `dispatchGesture` bilan yuborilgan barmoq surishi orqali bajarardi.
Taxmin (tekshirilmagan): teginib o'rganish yoqiq paytda tizim xizmatning o'zi yuborgan surishni ham o'z ushlagichidan
o'tkazadi va u launcherga yetmaydi. Taxmin to'g'ri bo'lsa, eski yo'l hech qachon ishlamasdi.

Yangi yo'l (`core/SystemSurfaces.kt`): uch qadam ketma-ket, har biridan keyin ekran o'zgarganmi tekshiriladi:

```kotlin
fun openApps(onDone: (Boolean) -> Unit = {}) {
    // ...
    val before = screenSignature()
    val steps = ArrayList<Step>(3)
    if (Build.VERSION.SDK_INT >= 31) {
        steps.add(Step("global") { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS) })
    }
    steps.add(Step("button") { clickAppsButton() })
    steps.add(Step("swipe") { injectSwipe(0.5f, 0.74f, 0.5f, 0.26f, 220L) })
    runStep(steps, 0, before, onDone)
}
```

Oxirgi qadamda teginib o'rganish 250 ms o'chiriladi va surish tugagach (yoki 2 soniyalik xavfsizlik muddatidan keyin) qayta
yoqiladi:

```kotlin
val teWasOn = touchExplorationOn()
if (teWasOn) { teSuspendedByUs = true; setTouchExploration(false) }
main.postDelayed(restoreTask, settle + durationMs + RESTORE_MARGIN_MS)
main.postDelayed({ svc.dispatchGesture(desc, callback /* onCompleted/onCancelled -> restore */, main) }, settle)
```

Boshqa o'zgarishlar: ikki barmoq bilan pastga tortish bosh ekranda to'g'ridan-to'g'ri `GLOBAL_ACTION_NOTIFICATIONS`;
soyani yopish Android 12+ da `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`; qulf ekranini ochish va sahifa almashtirish
surishlari ham xavfsiz yuborish yo'lidan o'tadi (`ReaderService.fingerSwipe`). Yangi ishora amali `OPEN_APPS`
("Ilovalar menyusini ochish") istalgan ishoraga biriktirilishi mumkin.

## 4. Telefonda sinash ro'yxati

1. Bosh ekranda ikki barmoq bilan tepaga surish: ilovalar menyusi ochiladimi.
2. Bosh ekranda ikki barmoq bilan pastga tortish: xabarnoma soyasi tushadimi; soya ochiq paytda tepaga surish uni yopadimi.
3. Qulf ekranida ikki barmoq bilan tepaga surish: qulf ochiladimi.
4. Teginib o'rganish tugagandan keyin ishlayaptimi (surishdan keyin ekranga tegib, o'qilishini tekshiring).
5. Telegram kanalida xabarlar bo'ylab o'ngga suring: avval post matni, keyin tugmalar o'qiladimi.
6. Telegram kanalida ikki barmoq bilan aylantirish: fokus yangi ko'ringan birinchi elementga o'tadimi.
7. Ro'yxatlarda (Sozlamalar, Telegram chatlar) yurish avvalgidek tez va takrorsiz ishlayaptimi.

Ishlamagan joy bo'lsa, Xatolar jurnalini yuboring: ilovalar menyusi bo'yicha `GESTURE_PATH` yozuvlari (`apps_ok`,
`apps_step_rejected`, `apps_step_no_effect`, `apps_failed`) qaysi yo'l nima uchun yiqilganini ko'rsatadi.

## 5. 0.9.1: Telegram'da yurish, tanlanganlik va yorliq tartibi

### 5.1 Sabablar

Kod o'qishdan aniq (tekshirilgan):

- `NEEDED_EVENTS` ichida `TYPE_VIEW_SELECTED` va `TYPE_VIEW_LONG_CLICKED` yo'q edi, `onClicked` esa faqat `isCheckable`
  elementlarda ishlardi. Tanlash yoki tanlovni olib tashlash hech qachon aytilmasdi; faqat "belgilangan" tomoni ba'zan
  (element belgilanadigan bo'lib qolganda) eshitilardi.
- `stateOf` belgilanadigan elementda `isSelected`ni, ro'yxat elementining tanlanganligini (`collectionItemInfo.isSelected`)
  hisobga olmasdi.
- `NodeDescriber.labelOf` matnni contentDescription'dan oldin olardi. TalkBack aksincha: ilova ekran o'quvchi uchun yozgan
  contentDescription birinchi.
- `labelOf` ichida `val hint = node.hintText...` parametr `hint`ni yopib qo'ygan edi, shuning uchun hodisaning o'z matni
  (4-qadam) hech qachon aytilmasdi.
- `onFocused` manbasiz hodisada (`event.source == null`) hech narsani eslab qolmasdi va `currentFocus()` tizim topmasa eskirgan
  `lastFocusedSource`ni qaytarardi. Natija: keyingi surish eski elementdan hisoblanadi.
- `focusFrom` fokus bermagan elementni jimgina tashlab, keyingisiga o'tardi.
- `handleScrolled` virtual elementda `refresh()` false qaytarsa, fokusni "yo'qolgan" deb ro'yxat boshiga ko'chirardi.
- `isLabeledUnit` bolalari 12 tadan ko'p konteynerni, uzun matni bo'lsa ham, nuqta deb hisoblamasdi.

Taxmin (jurnalda Telegram yozuvi yo'qligi uchun tasdiqlanmagan):

- Javob tariqasida yozilgan xabardagi javob havolasi virtual element; uning fokus hodisasi manbasiz kelganda joriy element
  eskirgan bo'lib, yurish shu yerda qotadi. Tuzatish shu mexanizmga qaratilgan.
- Ovozli xabar tugmasi ikkita: bosiladigan konteyner va ichidagi bosiladigan belgi, ikkalasi bir xil nom bilan alohida nuqta.
- YouTube'dagi "Video": tugmada ham matn, ham contentDescription bor. Aniq daraxt noma'lum.

### 5.2 Kod

Fokus manbasi (ReaderService.onFocused): manbasiz hodisada o'zimiz so'ragan fokus joriy bo'ladi.

```kotlin
val req = focusRequest
if (req != null && nowMs - focusRequestAt < 1500L) {
    lastFocusedSource = req
    lastFocusedAt = nowMs
    rememberState(req)
}
```

Joriy element (ReaderService.currentFocus): tizim ota-onani, biz esa uning ichidagi elementni bildirsak, aniqrog'i olinadi.

```kotlin
if (sys == null) return tracked
if (tracked == null) return sys
if (sys == tracked) return sys
if (isInside(tracked, sys)) return tracked
return sys
```

Fokus bermagan element o'tkazilmaydi (ReaderService.focusOrRead) va qotib qolishdan himoya (moveFocus):

```kotlin
val ok = focus(node) || readWithoutFocus(node)
...
if (fast != null && stuckTarget != null && fast == stuckTarget) fast = navigator.nextUnit(fast, forward, mode)
```

Ikkilangan nuqta (Navigator.isShadow): yorliq bir xil va maydonning 80 foizi ustma-ust.

```kotlin
val smaller = minOf(areaA, areaB)
return smaller > 0L && areaInter * 10L >= smaller * 8L
```

Yorliq tartibi (NodeDescriber.labelOf): contentDescription, matn, hint, tooltip, maxsus yorliq, labelFor, ichidagi matnlar,
hodisa matni, ichidagi bosiladigan bolalar.

Holat o'zgarishi (ReaderService.checkStateChange): bosish, uzoq bosish, tanlash yoki holat tavsifi o'zgargach fokusdagi element
qayta o'qilmaydi, faqat yangi holat aytiladi; tanlov olib tashlansa "tanlanmagan" yoki "belgilanmagan".

### 5.3 Telefonda sinash ro'yxati

1. Telegram kanalida o'ngga surib to'liq yuring: har post matni, keyin "fikrlar" va "ulashish" tugmalari tushib qolmasdan o'qiladimi.
2. Xuddi shunday chapga surib tepaga yuring.
3. Telegram guruhida o'zingiz javob tariqasida yozgan xabarga yetganda yurish davom etadimi, pastdagi yozish maydoniga o'tiladimi.
4. Yozish maydonida ovozli xabar tugmasi bir marta o'qiladimi.
5. Telegram'da bir nechta audio yoki faylni tanlang: har tanlashda "tanlangan" yoki "belgilangan" aytiladimi; olib tashlaganda
   "tanlanmagan" yoki "belgilanmagan".
6. Samsung "Mening fayllarim"da bir nechta faylni tanlang: shunday tekshiring; keyin ro'yxat bo'ylab yurib, tanlanganlar
   "tanlangan" deb o'qiladimi.
7. YouTube'da video pleyer tugmasini toping: "Video" o'rniga tugmaning nomi aytiladimi.
8. Sozlamalar va chatlar ro'yxatida yurish avvalgidek tez, takrorsiz va to'liq ishlayaptimi (yorliq tartibi o'zgargani uchun).

Ishlamagan joy bo'lsa, Xatolar jurnalini yuboring. Yangi yozuvlar: `FOCUS_SOFT_READ` (tizim fokus bermagan element: sinf, ilova,
ko'rinish identifikatori), `FOCUS_STUCK_FIXED` (qotib qolishdan himoya ishga tushgan joy), `FOCUS_NODE_FAILED`.

### 5.4 v14 hisoboti (0.9.2): xabarlar tushib qolishi va ovozli xabar

Hisobot: Telegram guruh va kanallarida o'ngga/chapga surib yurishda ayrim xabarlar o'qilmaydi, fokus to'g'ridan-to'g'ri
"fikrlar" yoki "ulashish" tugmasiga o'tadi; ovozli xabar eshitilayotganda ekran o'quvchi gapirishda davom etadi.
Bu safar xato jurnali yuborilmadi, shuning uchun sabablar kod o'qilib topilgan taxminlar. Telefonda sinalmagan.

Yurish sabablari (taxmin) va tuzatishlar:

1. Ikkilangan nuqta (isShadow) kaliti. Yorlig'i yo'q konteynerning kaliti ichidagi birinchi ko'rinadigan yorliq edi. Postda
   (o'z yorlig'i tugunda bo'lmasa) u birinchi tugma "fikrlar" bo'lib qolardi: oldinga yurishda post unga, orqaga yurishda
   tugma postga "soya" bo'lib, biri o'tkazib yuborilardi. Endi kalit faqat ichida bitta turli yorliq bo'lsa beriladi,
   bosilmaydigan konteyner yoki ro'yxat elementi ichidagi tugmani yashirmaydi.
2. Bosiladigan ota-ona yutishi. hasActionableAncestor va collect() ro'yxatdan yuqoridagi bosiladigan konteynerni ham
   hisoblardi; undan keyin bosilmaydigan xabar matni nuqta bo'lmay, faqat bosiladigan tugmalar qolardi (aynan hisobotdagi
   belgi). Endi ro'yxat/jadval/aylantiriladigan konteyner chegarasi (isListBoundary) to'xtash joyi. TalkBack'da ham ro'yxat
   elementi mustaqil nuqta.
3. Matni tugunda bo'lmagan xabar konteyneri (isChatHost): faqat suhbat ilovalarida, faqat ro'yxat elementi, yorlig'i yo'q,
   bosilmaydi, ichida faqat bosiladigan bolalar bor. Ichida oddiy matnli bola bo'lsa, u o'zi nuqta va konteyner qo'shilmaydi.
4. Mustaqil tekshiruv (ReaderService.crossCheckInList): suhbat ilovasida tez yo'l (nextUnit) natijasi ro'yxatning to'liq
   tartibi (collect) bilan solishtiriladi; farq bo'lsa ro'yxat tartibidagi qo'shni olinadi va NAV_DIVERGED yoziladi.
5. Aylantirishdan keyin (focusAfterScroll): eski fokus ko'rinib tursa, yurish uning qo'shnisidan davom etadi; aks holda
   eski usul (yangi paydo bo'lgan birinchi nuqta).
6. getChild null qaytarsa bir marta qayta so'raladi (childAt).

Ovozli xabar sababi (taxmin) va tuzatish: ijro paytida ilova mazmunni va fokus hodisalarini tez-tez yuboradi, suhbat rejimi
(readNewChatMessage), holat tekshiruvi, e'lonlar va takroriy fokus hodisasi gapirishni davom ettirardi. PlaybackMonitor
(AudioPlaybackCallback, API 26+) faqat USAGE_MEDIA, GAME, UNKNOWN, VOICE_COMMUNICATION ijrolarini sanaydi: O'zbegimning o'z
tovushlari USAGE_ASSISTANCE_ACCESSIBILITY, shuning uchun o'zini jim qilmaydi. Suhbat ilovasida ijro 450 ms dan ortiq
davom etsa ReaderService.beginListening(): nutq to'xtatiladi va ijro tugaguncha yuqoridagi avtomatik yo'llar o'chadi.
Foydalanuvchi so'nggi 1,5 soniyada ekranga tekkan bo'lsa, uning buyrug'i aytiladi. Boshqa ilovalarda o'zgarish yo'q.

Telefonda sinash ro'yxati:

1. Telegram kanalida o'ngga surib to'liq yuring: har post matni, keyin "fikrlar" va "ulashish" tugmalari ketma-ket o'qiladimi.
2. Xuddi shunday chapga surib tepaga yuring: tugmalar, keyin post matni, keyin oldingi postning tugmalari.
3. Guruhda xuddi shunday yuring, javob tariqasida yozilgan xabarda ham.
4. Ovozli xabarni bosing: nutq darhol to'xtaydimi, xabar oxirigacha jim eshitiladimi, ijro paytida surish bilan yurish ishlaydimi.
5. Musiqa yoki video chalayotganda boshqa ilovaga kelgan bildirishnoma avvalgidek o'qiladimi.
6. Sozlamalar va chatlar ro'yxatida yurish avvalgidek tez va to'liqmi.

Ishlamagan joy bo'lsa, Xatolar jurnalini yuboring. Yangi yozuv: `NAV_DIVERGED` (fwd, cur, fast, cand: sinf, bosiladimi,
bolalar soni, tepa chegarasi, yorlig'i bormi). Bu yozuv bo'lsa, tez yo'l biror nuqtani tashlagan ekani aniq bo'ladi.

## 6. 0.9.3: slayder va tanlagich, emoji va stikerlar, menyu sozlash

Holat: Gradle bilan qurilmadi (kompilyator yo'q), telefonda sinalmadi. Qo'lda tekshirildi: qavslar balansi, XML tuzilishi, hamma
`R.string` nomi to'rt tilda borligi, `GestureAction` nomlari, importlar (hammasi yangi kod uchun alohida skript bilan).

Slayder (hisobot: ekran yorqinligi 50 foizda, bir barmoq bilan pastga surilsa kamaymaydi):

- Sabab (kod o'qilib aniq): standart ishoralarda yuqoriga va pastga surish doim `moveFocus` (keyingi, oldingi element) edi.
  Qiymatni o'zgartiruvchi `adjustValue` faqat `VALUE_UP` va `VALUE_DOWN` amallariga ulangan, ular esa standart ishoralarga
  biriktirilmagan. `scrollScreen` slayderni umuman bilmasdi (`isScrollable` SeekBar'da yolg'on), shuning uchun ISHORALAR.md'dagi
  "o'ngga, so'ng chapga: slayderda qiymatni oshirish" bajarilmasdi.
- `core/Adjustable.kt` (yangi): `isAdjustable` (RangeInfo bor va qadam amali yoki ACTION_SET_PROGRESS bor; yoki NumberPicker qadam
  amali bilan), `find` (o'zi yoki tanlagichning ichki elementi uchun ota-onasi; ichidagi bolalar qidirilmaydi: Telegram xabaridagi slayder
  yurishni to'smasin), `step` (avval ACTION_SCROLL_FORWARD/BACKWARD, bo'lmasa ACTION_SET_PROGRESS qadamlari 1/20, 1/10, 1/5, 1/2),
  `atEdge` (chekka).
- `ReaderService.adjustByGesture`: faqat bir barmoqli yuqoriga, pastga, "o'ngga-chapga", "chapga-o'ngga" ishoralari va ularga
  NEXT, PREV (faqat "Elementlar" boshqaruvida), MODE_NEXT, MODE_PREV (faqat VoiceOver profili) yoki SCROLL_FWD, SCROLL_BACK biriktirilgan
  bo'lsa. Ikki barmoqli aylantirishga tegilmaydi (xabarnoma soyasi va ro'yxat uchun). Tez tekshiruv `cachedFocus` bo'yicha tizimga murojaat
  qilmaydi, shuning uchun oddiy elementlarda yuqoriga/pastga surish sekinlashmaydi.
- `adjustNode`: refresh, qadam, 130 va 350 ms da qiymat tekshiriladi va aytiladi; chekkada `Earcons.Kind.EDGE` va joriy qiymat.
  `checkStateChange` endi slayderni jim tashlamaydi: foydalanuvchi tegib turganda (`touching`) yoki ishoradan keyin 2,5 soniya ichida
  o'zgargan qiymatni aytadi; `announceValue` bir xil matnni 900 ms ichida takrorlamaydi.
- `NodeDescriber`: foiz `roundToInt` (ilgari `toInt` kasrni tashlardi: 127/255 -> 49); ilovaning stateDescription'i bo'lsa foiz qo'shilmaydi;
  `Slider` va sozlanadigan RangeInfo elementlar "slayder" deb tanladi; foydalanish ko'rsatmasi `hint_adjust`. `Navigator` "Boshqaruvlar"
  rejimi sozlanadigan elementlarni ham oladi.

Emoji va stikerlar:

- `content/EmojiSpeech.kt`: emoji topish (Emoji_Presentation, odatda emoji sifatida yoziladigan belgilar, U+FE0F bilan emoji bo'ladigan belgilar,
  teri rangi, ZWJ ketma-ketliklari, bayroqlar, tugmalar), 600 dan ortiq o'zbekcha nom, davlat bayroqlari. `EmojiMode`: KEEP, READ, SKIP.
  `ContentPipeline.process` matnni normallashtirgandan keyin birinchi bosqichda ishlatadi.
- `content/StickerText.kt`: stiker aniqlash (id yoki sinf "sticker", yoki qisqa tavsifda stiker so'zi: boshida yoki emoji bilan) va `quiet`.
  `NodeDescriber.describe` `details.readStickers` o'chiq bo'lsa chaqiradi.
- `Details.readEmoji`, `Details.readStickers`, `Prefs.DET_EMOJI`, `Prefs.DET_STICKERS`, `Prefs.emojiMode`. `detailsFor` ilova profilidagi
  darajaga ularni ko'chiradi.
- Taxmin: Telegram va WhatsApp stiker tavsifi so'z va emojidan iborat deb olindi; haqiqiy daraxt ko'rilmadi.

Menyu:

- `GestureAction` ga `SETTINGS_MENU`, `OPEN_VOICES`, `OPEN_GESTURES`, `OPEN_DETAILS`, `OPEN_AI`, `OPEN_BACKUP`, `OPEN_HEALTH`, `OPEN_LOG`,
  `OPEN_MENU_EDITOR` qo'shildi (nomlar bo'yicha saqlanadi: eski saqlangan ishoralar buzilmaydi).
- `GlobalMenu.effective(ctx)`: `Prefs.MENU_ITEMS` (vergul bilan ajratilgan amal nomlari) yoki standart ro'yxat; bo'sh yoki yaroqsiz bo'lsa
  standart. `ui/MenuActivity`: belgilash katakchalari, har o'zgarish darhol saqlanadi. Eksport va import `menu_items` ni tekshiradi.

v20 (0.9.6):

- Menyu: `GlobalMenu.actions` da `LINKS_MENU` ikkinchi; `effective()` bir martalik `links_second` yangilanishi bilan saqlangan ro'yxatda
  uni ikkinchi o'ringa ko'chiradi (`moveItem`). Tartib foydalanuvchi belgilagan holda saqlanadi. `ReaderService.showGlobalMenu()` oxirgi
  bandi `showMenuReorderPick` -> `showMenuReorderDrop` / `showMenuAddItem`; `MenuOverlay.show(onLongPick)` qatorni uzoq bosishni ushlaydi.
- Ovoz: `Prefs.ENGINE = "custom"` + `ENGINE_PACKAGE` (`SpeechManager.initialEngine`, `customPinned`), `Prefs.VOICE_READS` va
  `Prefs.engineReadsCyrillic` (matn quvuri kirillni lotinga o'tkazish-o'tkazmasligini shu bilan hal qiladi). `VoicesActivity` hamma
  o'rnatilgan dvigatellarni sanaydi (`TextToSpeech.getEngines`).
- Stiker: `Details.stickerShort`, `StickerText.short`, `EmojiSpeech.keepFirst`.
- Til: `GestureAction.displayName(ctx)` / `GestureItem.displayName(ctx)` resursdan (`ga_*`, `gi_*`), `act_*` (element amallari), `quirk_*`.

Telefonda sinash ro'yxati:

1. Tezkor sozlamalarda ekran yorqinligi slayderiga fokus bering: yuqoriga surish oshiradimi, pastga surish kamaytiradimi, har safar yangi
   foiz aytiladimi; eng chekkada chekka ovozi eshitiladimi.
2. Sozlamalar, Tovush: ovoz balandligi slayderi. Soat tanlagichi (vaqt tanlagich g'ildiragi) ham.
3. "Boshqaruvlar" o'qish boshqaruvida slayderga yetib boriladimi.
4. Telegram'da stiker va emojili xabar: Tafsilotlar, "Emoji va stikerlar" tugmalarini o'chirib/yoqib sinang.
5. O'zbegim menyusi, "O'zbegim sozlamalari bo'limlari": har band to'g'ri ekranni ochadimi. "O'zbegim menyusini sozlash" ekranida
   bandni olib tashlang: menyudan yo'qoladimi.
6. Oddiy yuqoriga/pastga surish (slayder bo'lmagan joyda) avvalgidek ishlayaptimi va sekinlashmadimi.

Ishlamagan joy bo'lsa, Xatolar jurnalini yuboring (`FOCUS_NODE_FAILED`, `SLOW_HANDLER`).

## 7. 0.9.4: telefon raqamlari, boshqa ekran o'quvchi bilan kelishuv, yacheykalar izolyatsiyasi, surish tezligi

Holat: Gradle bilan qurilmadi (kompilyator yo'q), telefonda sinalmadi. Qo'lda tekshirildi: o'zgargan joylar asl arxivga nisbatan diff
bo'yicha ko'rib chiqildi (qavslar qo'shilgan satrlarda juft), XML tuzilishi, yangi `R.string` nomlari to'rt tilda borligi va takrorlanmasligi,
yangi kod ishlatgan maydon va funksiyalar e'lon qilinganligi. Telefon raqami regexlari Kotlin faylidan olinib, JVMda (java.util.regex)
30 ga yaqin misolda sinaldi; Androidning ICU regex dvigatelida sinalmadi (ishlatilgan konstruksiyalar ikkalasida bir xil).

Telefon raqamlari:

- `content/PhoneFormat.kt` (yangi): `apply(text)`. Uch qoida: xalqaro (`+?998` va 9 xona, ajratgichlar: probel, chiziqcha, nuqta, qavs),
  mahalliy (2-3-2-2 guruh, kamida bitta probel yoki chiziqcha, operator kodi ro'yxatda), kalit so'zdan keyingi ajratgichsiz 9 xona.
  Natija: guruhlar `UzNumbers.words` bilan so'zga aylantiriladi, orasiga ". ", oxiriga nuqta (agar keyin allaqachon tinish belgisi yoki
  probeldan keyin nuqta bo'lsa, qo'shilmaydi). Xalqaro raqam "plyus to'qqiz yuz to'qson sakkiz" bilan boshlanadi.
  Sabab: `SpeechNormalizer.PLAIN` "+" dan keyingi sonni o'zgartirmaydi, "+998" dvigatelga qolardi; so'zga aylantirish natijani
  dvigatelga bog'liq qilmaydi.
- `PipelineOptions.phoneGroups`; `ContentPipeline.process` uni `SensitiveText` tekshiruvidan keyin, `SpeechNormalizer` dan oldin ishlatadi
  (shunda eski `+...` qoidasi bu raqamlarga tegmaydi). `Details.phoneGroups`, `Prefs.DET_PHONE` (standart `true`), `Prefs.phoneGroups`,
  `preferences_details.xml` ("Raqamlar" bo'limi), `detailToggles` (menyudagi Tafsilotlar). Ilova profilidagi daraja uni bekor qilmaydi.

Boshqa ekran o'quvchi bilan kelishuv:

- `core/OtherReaders.kt` (yangi): `find(ctx)` va `Watcher`. `find`: (1) `AccessibilityManager.getEnabledAccessibilityServiceList`
  ichida o'zimizdan boshqa paketli, `CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION` yoki `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` bor xizmat
  (yoki ma'lum paket); (2) zaxira: `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` qatorida ma'lum ekran o'quvchi paketi.
  `Watcher`: shu sozlama va `ACCESSIBILITY_ENABLED` ustida `ContentObserver` (ruxsat kerak emas).
- `ReaderService`: `suspended` endi hisoblanadigan xususiyat: `userSuspended || yielded` (eski ishlatilishlar ishlayveradi; qo'lda
  to'xtatish `userSuspended`). `evaluateOtherReader` (kuzatuvchi 350 ms kechikish bilan va har 2,5 soniyada) -> `enterStandby` yoki
  `leaveStandby`. Kutish rejimida: e'lon aytiladi, so'ng `speak()` va `focus()` hech narsa qilmaydi, `handleKey` va `updatePassthrough`
  chiqib ketadi, `setTouchExploration` hech qachon yoqmaydi; `applyStandbyFlags(true)` teginib o'rganish, tugmalarni filtrlash,
  ko'p barmoqli ishora, ikki barmoqli o'tkazish bayroqlarini olib tashlaydi va `eventTypes = 0` qiladi; chiqishda avvalgi bayroqlar
  aynan o'zi va `NEEDED_EVENTS` qaytariladi. `onInterrupt` kutish rejimiga o'tgach 5 soniya e'lonni kesmaydi.
  `onServiceConnected` boshqa ekran o'quvchi allaqachon yoniq bo'lsa, kutish rejimida boshlaydi.
- Taxminlar (telefonda tekshirilishi kerak): `serviceInfo` orqali `eventTypes = 0` va bayroqlarni olib tashlash tizim tomonidan
  qabul qilinishi (mavjud "to'xtatish" ham xuddi shunday teginib o'rganishni o'chiradi); TalkBack yoqilganda tizim sozlamalari
  kuzatuvchisi chaqirilishi.
- `Prefs.YIELD_OTHER` (standart `true`), `Prefs.yieldToOtherReaders`, `preferences.xml` ("Boshqa ekran o'quvchilar").

Qo'shni yacheykalar izolyatsiyasi:

- `NodeDescriber.trustHint`: hodisa matni (hint) faqat bolalari 1 tadan oshmagan yoki ro'yxat elementi bo'lgan tugun uchun ishlatiladi.
- `NodeDescriber.rollUp`: ro'yxat/jadval ildizi uchun bo'sh; ichki ro'yxatlar o'tkazib yuboriladi; markazi ildiz chegarasidan tashqarida
  bo'lgan bola o'tkazib yuboriladi; `includeActionable` rejimida fokusga tushmaydigan, bosilmaydigan konteynerda ikkitadan ortiq bosiladigan
  yorliqli bola bo'lsa, natija bo'sh.
- `ReaderService.onHover`: `event.source.childCount > 1` va hodisa matni bir nechta bo'lakdan iborat bo'lsa, birlashtirilgan matn
  zaxira sifatida ishlatilmaydi.
- `ReaderService.readNewChatMessage`: `cachedFocus()` shu ro'yxat ichida va oxirgi fokus 30 soniya ichida (`CHAT_FOCUS_IDLE_MS`)
  bo'lsa, chiqib ketadi.

Surish tezligi:

- `Navigator.listNeighbor` avval `windowedNeighbor` ni ishlatadi: cur turgan ro'yxat elementi `collect(item)`, keyin ko'rinish tartibida
  (teskari konteynerda indeks kamayadi) 6 tagacha ko'rinadigan qo'shni element. Natija `collect(list)` bilan bir xil; cur topilmasa
  yoki indeks aniqlanmasa `null` va to'liq usulga o'tiladi. Chet holat: ro'yxatning o'zi nuqta bo'lsa (bosiladigan ro'yxat), tez variant
  uni qo'shni deb topmaydi (oddiy RecyclerView uchun bu to'g'ri).
- `moveFocus`: oyna ildizi (`cur.window.root`, so'ng `rootInActiveWindow`) lazy `windowRoot()`: tez yo'lda yuklanmaydi.

Telefonda sinash ro'yxati:

1. Tafsilotlar, "Raqamlar" yoqiq: matnda "+998947739910", "+998 94 773 99 10", "94 773 99 10" va "+998. 94. 773. 99. 10." yozib (Telegram,
   Xabarlar, Kontaktlar) ustiga fokus keltiring: hammasi bir xil, guruh-guruh o'qiladimi. O'chirilsa, avvalgidek.
2. TalkBack'ni yoqing: O'zbegim e'lonni aytib jim bo'ladimi, TalkBack ishoralari to'liq ishlaydimi. TalkBack'ni o'chiring: O'zbegim
   ikkinchi e'lonni aytib, ishoralarni qaytaradimi. Sozlamada "o'zim to'xtash" ni o'chirib, qayta sinang.
3. Samsung bosh ekrani: ilovalar orasida va bo'sh joyga tegib yuring: faqat bitta ilova nomi eshitiladimi, papkada nechta nom aytiladi.
4. Telegram chatlar ro'yxati va kanal: o'ngga-chapga surish, to'xtab turib ham: qo'shni yacheyka matni qo'shilib o'qilmaydimi, surish
   avvalgidan tezmi (Xatolar jurnalidagi SLOW_HANDLER va NAV_DIVERGED yozuvlariga qarang).
5. Telegram suhbatida kelgan yangi xabar: fokus kiritish maydonida bo'lsa avtomatik o'qiladimi.

Ishlamagan joy bo'lsa, Xatolar jurnalini yuboring (`SLOW_HANDLER`, `NAV_DIVERGED`, `FOCUS_NODE_FAILED`).

## 8. 0.9.7 (v21): Android tizimiga topshirish, e'lon tili va soat uslublari

Holat: kompilyator yo'q, Gradle bilan qurilmadi, telefonda sinalmadi. Kompilyatorsiz tekshiruv: qavslar balansi (79 fayl), to'rt tilda
resurslarning to'liqligi va format belgilari, kodda ishlatilgan `R.string`, `R.array` nomlari, XML butunligi; `AnnounceText` mantig'i
Python nusxasida (24 soatning har bir daqiqasi, uch til, ikki uslub, batareya 0..100) sinaldi.

Android tizimiga topshirish:

- `Prefs.DOUBLE_TAP` (mavjud), `Prefs.HOLD_MODE`, `Prefs.NAV_MODE`: "service" (standart) yoki "system". `Prefs.delegation(ctx, key)`.
- Bayroq: `ReaderService.applyDoubleTapFlag()` `FLAG_SERVICE_HANDLES_DOUBLE_TAP` ni faqat `DOUBLE_TAP` va `HOLD_MODE` ikkalasi ham
  "system" bo'lganda olib tashlaydi (Android bu ikki ishorani bitta bayroq bilan beradi). `prefListener` `DOUBLE_TAP` yoki `HOLD_MODE`
  o'zgarganda uni qayta qo'llaydi; `leaveStandby` ham (kutish paytida saqlangan bayroqlar eskirgan bo'lishi mumkin).
- `handleGesture`: 17 (ikki marta bosish) va 18 (ushlab turish) ishoralari alohida yo'naltiriladi. "system" bo'lsa `activateLikeSystem`:
  `systemFocus()` dagi elementda `ACTION_CLICK` yoki `ACTION_LONG_CLICK` (amal bor bo'lsagina), bo'lmasa `TouchActions.tapCenter`; ota-onani
  bosmaydi, eslab qolingan yoki yumshoq fokusga tayanmaydi (tizimning o'z qoidasi). "service" bo'lsa avvalgi `activateByDoubleTap`.
- Navigatsiya: `moveFocus` da, o'qish boshqaruvi "Elementlar" va `NAV_MODE=system` bo'lsa, `systemMove(cur, forward)` birinchi
  uriniladi: 1) `traversalBefore` (oldinga) yoki `traversalAfter` (orqaga), bir oynada va ko'rinadigan bo'lsa; 2) element `isFocusable`
  bo'lsagina `focusSearch(FOCUS_FORWARD yoki FOCUS_BACKWARD)`. Element fokus ola olmasa tekshiruv o'tkazilmaydi: `FocusFinder` fokuslanmaydigan
  elementdan "birinchi fokuslanadigan"ga sakraydi. `false` qaytsa, odatdagi `Navigator` yo'li ishlaydi (hech qachon qotib qolmaydi).
- Kiritish fokusi: `NEEDED_EVENTS` ga `TYPE_VIEW_FOCUSED` qo'shildi, `onInputFocused` a11y fokusini shu elementga ko'chiradi va o'qiydi.
  Sharti: oxirgi `NAV_KEY_WINDOW_MS` (1200 ms) ichida Tab, strelka yoki D-pad bosilgan (`handleKey` da `lastNavKeyAt`), yoki navigatsiya
  "system" va foydalanuvchi ekranga tegmayapti; fokus allaqachon shu elementda yoki element ko'rinmasa, tegilmaydi. O'z oynalarimizdan
  (menyu) kelgan hodisalar e'tiborsiz.
- Menyu: Tafsilotlar menyusiga (`showDetailsMenu`) uchta qator qo'shildi (`delegations`), bosish holatni almashtiradi (`toggleDelegation`).
- UI: `DetailsActivity` da `hold_mode`, `nav_mode`, tezkor tugmalar `delegate_all_system` va `delegate_all_service`
  (`setAllDelegation`), tushuntirish qatori `delegation_info`.

E'lon tili va soat:

- `content/AnnounceText.kt` (Android'siz): `AnnLang` (UZ, RU, EN), `ClockStyle` (PASSED, CLOCK), `time`, `battery`, `charger`, `ringer`,
  `orientation`, `screenOn`, `unlocked`. Raqamlar so'z bilan (o'zbekcha `UzNumbers`, ruscha va inglizcha o'z jadvallari). Ruscha son va ot
  kelishuvi: `ruMinutes`, `ruPercent`, "прошла" va "прошло".
- `core/AnnounceLang.kt`: `current(ctx)` = `AnnounceText.pick(ovoz tili, tizim tili)`; `cyrillic(ctx)` (telefon tili o'zbekcha kirill va
  `Prefs.engineReadsCyrillic`); `shape` lotinni `UzScripts.latinToCyrillic` bilan o'tkazadi; `timeText`, `batteryText`.
- `SpeechManager.voiceLanguage()`: onlayn ovozda ovoz nomidan, aks holda `tts.voice.locale.language`; uch harfli kodlar ("uzb", "rus", "eng")
  `AnnounceText.langOf` da tanib olinadi. Natija 60 soniya keshlanadi (e'lon har safar dvigatelga murojaat qilmasligi uchun), `reconfigure()`
  keshni tozalaydi.
- `TimeAnnouncer.timeText` / `batteryText` va `ReaderService.speakStatus` endi bitta manbadan. E'lonlar `speak(raw = true)` bilan aytiladi:
  matn allaqachon ovoz tilida va raqamsiz, shuning uchun nutq quvuri (kirill-lotin, til ko'prigi) uni buzmaydi.
- `Prefs.TIME_STYLE` ("passed" yoki "clock"). `TimeBatteryFragment.refreshStyle` ro'yxat bandlarini va ko'rsatmalarni hozirgi format va tilda
  haqiqiy matn bilan qayta quradi; `speakSample` namunani (14:30) eshittiradi.

Tizim holati: `core/SystemStatus.kt` (`start`, `stop`, `onConfiguration`): `ACTION_POWER_CONNECTED` va `_DISCONNECTED`,
`RINGER_MODE_CHANGED_ACTION`, `ACTION_SCREEN_ON`, `ACTION_USER_PRESENT` (xizmat kontekstida ro'yxatdan o'tadi) va ekran burilishi
(`onConfigurationChanged`). Aytish: `ReaderService.announceStatus` (uzmaydi, `auto = true`, ekran o'chiq paytda nutq o'chirilgan bo'lsa
aytmaydi). Sozlamalar: `ann_charger`, `ann_ringer` (standart: yoqiq), `ann_orientation`, `ann_screen` (standart: o'chiq).

Taxminlar va ma'lum cheklovlar:

- Aniq soatda (daqiqa 0) ikkala uslubda ham faqat soat aytiladi: so'rovdagi misollar faqat :30 uchun edi.
- Ovoz tili o'zbekcha, ruscha, inglizcha bo'lmasa, tizim tili olinadi; u ham bo'lmasa o'zbekcha (ilovaning asosiy tili).
- Ikki marta bosish va ushlab turish aralash holatda (biri tizimda, biri O'zbegimda) ushlab turib sudrash ishlamaydi: tizim buni faqat
  `FLAG_SERVICE_HANDLES_DOUBLE_TAP` o'chiq bo'lganda beradi. `TouchInteractionController` (Android 11+) bilan buni ajratish mumkin bo'lishi
  mumkin, lekin telefonsiz xavfsiz qo'shib bo'lmaydi.
- `focusSearch` ro'yxatdagi birinchi fokuslanadigan elementga sakrashi mumkin bo'lgan holatlar (fokuslanmaydigan joriy element) chetlab
  o'tiladi, lekin ilova o'z `nextFocus*` tartibini noto'g'ri bersa, tizim tartibi ham noto'g'ri bo'ladi: shuning uchun standart O'zbegimda.

