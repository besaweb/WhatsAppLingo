# ChatLingo (prac. název WhatsAppLingo) – Technická dokumentace

> Stav: **ETAPA 1 – technická analýza a ověření proveditelnosti** (13. 11. 2026)
> Analýza vychází z dokumentace Android API, pravidel Google Play a známého chování WhatsAppu. Části označené **[SPIKE]** je nutné před MVP ověřit na reálném zařízení. Nic z toho zatím není ověřeno měřením.

---

## 0. Shrnutí pro rozhodnutí

| Otázka | Odpověď |
|---|---|
| Dá se zobrazit překlad „pod zprávou“ ve WhatsAppu? | **Ano, ale jen jako překryv.** Overlay se vykreslí *nad* obsah WhatsAppu. Neumí odsunout další zprávy, takže panel **zakryje to, co je pod bublinou** (mezeru nebo další zprávu). Skutečné vložení do bubliny / reflow chatu **nejde**. |
| Pohybuje se překlad se zprávou při scrollu? | **Přibližně.** Polohu dopočítáváme z accessibility událostí, ne z vykreslovací smyčky WhatsAppu. Čekej zpoždění o pár snímků a cukání. Spolehlivá varianta: **během scrollu panely skrýt a po zastavení je zobrazit znovu**. |
| Dají se identifikovat jednotlivé bubliny? | **Pravděpodobně ano** (text, hranice, příchozí/odchozí). Závisí ale na interních ID view WhatsAppu, která nejsou API a mohou se změnit s každou aktualizací. **[SPIKE]** |
| Překlad cs↔sq | **ML Kit albánštinu (sq) i češtinu (cs) podporuje** (ověřeno v oficiálním seznamu). Překládá ale přes angličtinu, kvalita je „casual“. Pro kvalitu doporučuji LLM (OpenAI) přes proxy, ML Kit jako offline záložní řešení. |
| Odchozí překlad | **Plně realizovatelný.** Doporučuji místo vlastní klávesnice použít overlay nad klávesnicí + `ACTION_SET_TEXT` do pole WhatsAppu (uživatel si nechá Gboard). Vlastní IME zůstane jako volitelná varianta. |
| Google Play | **Riziko střední až vysoké.** Aplikace není „accessibility tool“, takže potřebuje deklaraci v Play Console, výrazné upozornění a souhlas v aplikaci a video. Název „WhatsApp…“ v názvu aplikace je téměř jistě problém (ochranná známka Meta). |
| Doporučení | **Pokračovat**, ale nejdřív s krátkým **technickým spikem na zařízení (Etapa 1b, 2–3 dny)** s jasnými go/no-go kritérii (kap. 9). Teprve potom MVP. |

---

## 1. Ověřené skutečnosti (zdroje v kap. 12)

1. **ML Kit Translation** podporuje `sq` (albánština) i `cs` (čeština). Modely jsou trénované do a z angličtiny, takže při překladu mezi dvěma neanglickými jazyky jde text přes angličtinu, což snižuje kvalitu. Google pro vyšší kvalitu doporučuje Cloud Translation.
2. **Google Play – Accessibility API:** příznak `isAccessibilityTool` smí mít jen služby pro lidi s postižením. Ostatní aplikace musí splnit požadavky na výrazné upozornění a souhlas podle politiky User Data a vyplnit deklaraci v Play Console. Pokud aplikace, která není accessibility tool, nastaví `isAccessibilityTool=true`, Play ji odmítne a Play Protect ji na zařízeních zablokuje.
3. **Restricted settings (Android 13+):** u aplikací instalovaných mimo obchod (sideload) systém blokuje zapnutí Accessibility i Notification Listener. Uživatel to musí ručně povolit v Info o aplikaci → „Povolit omezená nastavení“. Android 15 tato omezení rozšiřuje, ruční povolení ale zůstává.
4. **Advanced Protection Mode (Android 17):** v tomto režimu systém neumožní udělit Accessibility oprávnění aplikacím, které nejsou accessibility tools, a už udělená oprávnění odebere. Pro uživatele s AAPM aplikace **nebude fungovat**.
5. **`accessibilityDataSensitive` (Android 14+):** aplikace (tedy i WhatsApp) může označit view jako citlivé. Služby bez `isAccessibilityTool` k nim pak nemají přístup. Google to v prosinci 2025 vývojářům aktivně doporučoval. **Pokud to Meta nasadí na bubliny zpráv, aplikace přestane vidět texty a nedá se s tím nic dělat.**

---

## 2. Technické možnosti – rozbor

### 2.1 AccessibilityService (čtení obsahu WhatsAppu)

- Konfigurace: `packageNames="com.whatsapp,com.whatsapp.w4b"`, události `typeWindowContentChanged|typeViewScrolled|typeWindowStateChanged|typeViewTextChanged|typeWindowsChanged`, `canRetrieveWindowContent=true`, příznaky `flagReportViewIds|flagRetrieveInteractiveWindows`, `notificationTimeout` 0–50 ms.
- Z `AccessibilityNodeInfo` získáme: text, `viewIdResourceName` (např. `com.whatsapp:id/...`), `getBoundsInScreen()`, `isVisibleToUser`, třídu, hierarchii, `isPassword`.
- Od API 28 nese `TYPE_VIEW_SCROLLED` hodnotu `scrollDeltaY`, kterou lze použít pro rychlý posun overlayů ještě před novým čtením stromu.
- **Omezení:** strom je snímek. Mezi událostí a čtením se UI mohlo změnit. Čtení stromu stojí jednotky až desítky ms. ID view nejsou veřejné API.

### 2.2 TYPE_ACCESSIBILITY_OVERLAY

- Okno přidané přes `WindowManager` z kontextu AccessibilityService. Nepotřebuje `SYSTEM_ALERT_WINDOW` a zobrazí se nad aplikacemi včetně WhatsAppu.
- Příznaky: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS | FLAG_LAYOUT_IN_SCREEN`. Dotyky tak propadají do WhatsAppu.
- API 34+: `AccessibilityService.attachAccessibilityOverlayToWindow(windowId, SurfaceControl)` připne overlay k oknu WhatsAppu. Z-pořadí pak sedí a overlay automaticky zmizí při přepnutí aplikace. **Obsah se ale při scrollu sám neposouvá**, polohu musíme dál přepočítávat.
- **Zásadní omezení:** overlay **nemůže měnit layout WhatsAppu**. Panel „pod zprávou“ = panel **přes** prostor pod zprávou.

Způsoby, jak tuto limitaci zmírnit (dají se kombinovat a přepínat v nastavení):

| Režim | Popis | Plus | Mínus |
|---|---|---|---|
| A. Inline panel | Panel přímo pod bublinou, max. 3 řádky, poloprůhledný | Nejblíž zadání | Zakrývá další zprávu |
| B. Odznak + rozbalení | Malý odznak „ChatLingo“ u bubliny, po klepnutí se rozbalí panel | Minimální zákryt | Překlad se ukáže až po klepnutí, klepací okna jsou složitější |
| C. Panel u poslední zprávy | Inline panel jen u nejnovější příchozí zprávy, starší jako odznak | Dobrý kompromis | Méně „kompletní“ |
| D. Spodní lišta | Lišta nad polem pro psaní se seznamem překladů viditelných zpráv a zvýrazněním vazby | Žádný zákryt bublin, robustní | Nesplňuje „přímo pod zprávou“ |

**Doporučení pro MVP:** výchozí režim **C** a přepínatelný režim **A**. Režim D jako záloha, pokud spike ukáže nespolehlivé pozicování.

### 2.3 NotificationListenerService

- Zachytí text příchozí zprávy, i když chat není otevřený (`EXTRA_TEXT`, `EXTRA_MESSAGES` z MessagingStyle).
- Využití: **předpřeklad do cache**, takže po otevření chatu je překlad hned k dispozici. Notifikace nikdy nepoužíváme k umístění na obrazovce.
- Omezení: ztlumené chaty, souhrnné notifikace, zkrácený text. U sideloadu také spadá pod restricted settings. **Volitelné, až po MVP.**

### 2.4 InputMethodService vs. accessibility pro odchozí zprávy

| Varianta | Popis | Hodnocení |
|---|---|---|
| **A (doporučeno)** | Služba sleduje pole `com.whatsapp:id/entry` (`TYPE_VIEW_TEXT_CHANGED`), zobrazí náhled nad klávesnicí (polohu vezme z okna `TYPE_INPUT_METHOD` přes `getWindows()`) a po potvrzení zavolá `ACTION_SET_TEXT` | Uživatel si nechá Gboard (diakritika, našeptávač). Žádná další oprávnění. |
| B | Vlastní IME: celá česká klávesnice + lišta s překladem, vkládání přes `InputConnection.commitText` | Funguje nezávisle na ID view, ale znamená vlastní klávesnici (rozložení, diakritika, opravy). Velký objem práce, horší UX než Gboard. |

- Pravidla v obou variantách: **nikdy `ACTION_CLICK` na tlačítko odeslat**, nic nezpracovávat, když `isPassword=true` nebo pole není WhatsApp `entry`.
- Kvůli soukromí a ceně: překládat až **po pauze v psaní (~700 ms)** nebo jen na tlačítko „Přeložit“, ne po každém znaku.

> ⚠️ Odchylka od zadání: zadání požaduje InputMethodService. Doporučuji v Etapě 3 začít variantou A a IME (B) přidat jen tehdy, když A selže, např. kvůli `accessibilityDataSensitive` na vstupním poli.

---

## 3. Identifikace zpráv – návrh algoritmu **[SPIKE]**

### 3.1 Selektory (konfigurovatelné, ne natvrdo v kódu)

Soubor `assets/adapters/whatsapp.json` (později aktualizovatelný), verzovaný podle `versionCode` WhatsAppu:

```json
{
  "package": ["com.whatsapp", "com.whatsapp.w4b"],
  "conversationList": ["android:id/list", "com.whatsapp:id/conversation_list"],
  "messageText": ["com.whatsapp:id/message_text"],
  "outgoingMarker": ["com.whatsapp:id/status"],
  "dateLabel": ["com.whatsapp:id/date"],
  "senderNameInGroup": ["com.whatsapp:id/name_in_group"],
  "quoted": ["com.whatsapp:id/quoted_message"],
  "inputField": ["com.whatsapp:id/entry"],
  "chatTitle": ["com.whatsapp:id/conversation_contact_name"]
}
```

Hodnoty jsou **předpoklady** podle historicky známých ID (`entry`, `send` a `message_text` se objevují ve veřejných ukázkách automatizace). Spike je musí potvrdit dumpem stromu. Pokud WhatsApp názvy prostředků obfuskuje, použije se strukturní heuristika (třída `TextView` uvnitř položky seznamu s `long-clickable` předkem).

### 3.2 Postup na jeden „průchod“ (frame)

1. Najdi okno WhatsAppu a v něm seznam konverzace. Pokud chybí, skryj všechny overlaye.
2. Viewport = `boundsInScreen` seznamu (bez toolbaru a vstupního panelu).
3. Pro každou položku seznamu:
   - najdi uzel `messageText`; uzly uvnitř `quoted` (citace) vynech,
   - **bublina** = nejbližší předek s `isLongClickable`, jeho `boundsInScreen` = hranice,
   - **směr**: přítomnost `outgoingMarker` (fajfky) → odchozí, jinak příchozí. Pokud se to nedá rozhodnout, použij polohu bubliny vůči středu viewportu (s ohledem na RTL),
   - **validace**: `isVisibleToUser`, hranice celé uvnitř viewportu (částečně viditelné → nezobrazovat nebo oříznout), šířka a výška > 0, text není prázdný.
4. Vytvoř `MessageKey` (níže). Neplatné nebo nejednoznačné položky **přeskoč, nikdy nehádej**.
5. Předej snímek `VisibleMessage[]` do `OverlayLayoutEngine`.

### 3.3 Identita zprávy a ochrana před duplicitami

Strom neobsahuje ID zprávy. Klíč tedy skládáme takto:

```
MessageKey = sha256(chatTitle | direction | senderInGroup | normalizedText | timeLabel) + "#" + ordinal
```

- `ordinal` = pořadí mezi stejnými klíči ve viditelném snímku (např. dvakrát „ok“ ve 14:02).
- Cache překladů: klíč = `sha256(normalizedText + langPair + engineId)`. Stejný text se tak nepřekládá dvakrát.
- Jeden klíč = maximálně jeden panel. Panel se při každém průchodu **aktualizuje**, nepřidává se nový.
- Odchozí zpráva vložená přes ChatLingo: uložíme mapu `translatedText → czechOriginal`. Až se objeví odchozí bublina se shodným textem, zobrazí se pod ní „Originál: …“.

### 3.4 Scroll a změny

| Situace | Řešení |
|---|---|
| `TYPE_VIEW_SCROLLED` | Rychlá cesta: posun všech panelů o `scrollDeltaY`. Pomalá cesta: po klidu ~80–120 ms nové čtení stromu a srovnání. |
| Rychlý fling | Panely skrýt (fade 100 ms) a zobrazit po zastavení. **Výchozí chování.** |
| Nová zpráva | `TYPE_WINDOW_CONTENT_CHANGED` → debounce → průchod |
| Klávesnice / rotace / split-screen | `TYPE_WINDOWS_CHANGED` / změna hranic → kompletní přepočet |
| Režim výběru (long-press), swipe na odpověď, reakce, smazání nebo úprava zprávy | Při změně hranic nebo textu panel skrýt a přepočítat |
| Odchod z chatu / jiná aplikace | `TYPE_WINDOW_STATE_CHANGED` → skrýt vše (API 34+: připnutím k oknu automaticky) |

---

## 4. Překladový engine

```kotlin
data class LanguagePair(val source: String, val target: String)   // "sq" -> "cs"
data class TranslationRequest(val text: String, val pair: LanguagePair, val context: List<String> = emptyList())
sealed interface TranslationResult {
    data class Success(val text: String, val engineId: String) : TranslationResult
    data class Failure(val reason: Reason) : TranslationResult
}
interface TranslationEngine {
    val id: String
    fun supports(pair: LanguagePair): Boolean
    suspend fun translate(request: TranslationRequest): TranslationResult
}
```

| Engine | cs↔sq | Kvalita | Offline | Náklady | Soukromí |
|---|---|---|---|---|---|
| ML Kit | ✅ ověřeno | střední (přes EN) | ✅ (~30 MB/jazyk) | 0 | Data neopouštějí zařízení |
| OpenAI přes proxy | ✅ | vysoká, kontextová | ❌ | za tokeny | Text jde k poskytovateli |
| Google Cloud Translation | ✅ (podle Googlu vyšší kvalita) | vyšší | ❌ | za znaky | Text jde ke Googlu |

**Doporučení:** výchozí engine OpenAI přes proxy, ML Kit jako automatická záloha při chybě sítě nebo offline. Engine se vybírá v nastavení.

**Ochrana obsahu před překladem:** URL, e-maily, telefonní čísla a @zmínky nahradit zástupnými tokeny (`⟦1⟧`) a po překladu je vrátit. LLM prompt: „přelož pouze text, zachovej řádkování, interpunkci, emoji, jména a tokeny ⟦n⟧, nic nepřidávej“. Odpověď validovat (počet tokenů, nesmí být prázdná).

**Proxy (backend):** Cloudflare Worker nebo malý server. Klíč OpenAI je jen na serveru. Aplikace se ověřuje tokenem zařízení, server má rate-limit, maximální délku vstupu a **neloguje obsah**. V APK nikdy není produkční klíč.

---

## 5. Architektura

### 5.1 Moduly (Gradle)

```
:app                      Compose UI (Material 3), onboarding, disclosure, nastavení, stav služeb
:core:model               LanguagePair, Direction, VisibleMessage, MessageKey
:core:data                Room (cache překladů, mapování odchozích – jen se souhlasem), DataStore (nastavení)
:translation:api          TranslationEngine, TranslationRepository (cache, fallback, rate-limit)
:translation:mlkit        ML Kit implementace + správa modelů
:translation:remote       Retrofit/OkHttp klient na proxy
:capture:accessibility    ChatLingoAccessibilityService, event pipeline (Flow, debounce), čtení stromu
:adapter:api              ChatAppAdapter (rozhraní pro WhatsApp, později Telegram…)
:adapter:whatsapp         Selektory, MessageExtractor, InputFieldLocator
:overlay                  OverlayWindowManager, OverlayLayoutEngine, TranslationPanelView (Canvas View)
:ime                      (Etapa 3, volitelně) InputMethodService
backend/proxy             samostatný projekt
```

### 5.2 Tok dat (příchozí)

```
AccessibilityEvent ─► EventPipeline (Flow, debounce/throttle)
   ─► ChatAppAdapter.extract(root) ─► List<VisibleMessage>(key, text, bounds, direction)
   ─► TranslationRepository.translate() (cache → engine → fallback)
   ─► OverlayLayoutEngine (kolize, viewport, režim A/B/C/D)
   ─► OverlayWindowManager.render()
```

### 5.3 Klíčová rozhodnutí

- **Overlay jako obyčejná Canvas `View`**, ne Compose: jedno fullscreen okno bez dotyků vykreslí všechny panely, takže při posunu žádná okna nevznikají ani nezanikají. Pro klepací prvky (odznaky, tlačítka „Vložit překlad“) samostatná malá dotyková okna.
- `ChatAppAdapter` odděluje logiku konkrétní aplikace. Přidání Telegramu = nový adaptér + selektory.
- Jazykové profily kontaktů: tabulka `contact_profile(chatTitleHash, pair)` připravená, v MVP se používá pevná dvojice cs↔sq.
- `minSdk 28` (kvůli `scrollDeltaY`), `targetSdk` = aktuální stabilní SDK v době založení projektu. API 34+ využívá `attachAccessibilityOverlayToWindow`.

---

## 6. Bezpečnost a soukromí

- Služba reaguje **jen** na balíčky WhatsAppu (`packageNames`). Ignoruje pole s `isPassword` a vše mimo seznam zpráv a `entry`.
- Obsah zpráv se standardně drží **jen v paměti** (LRU). Perzistentní cache v Room pouze po výslovném souhlasu, s možností smazání.
- Nikdy automatické odeslání: kód neobsahuje `ACTION_CLICK` na tlačítko odeslat (hlídat lint pravidlem nebo testem).
- Onboarding: výrazné upozornění **před** přechodem do nastavení Accessibility. Co služba čte, co a komu se posílá (OpenAI / Google / nic u ML Kit), checkbox se souhlasem. Splňuje i požadavek Play.
- Hlavní přepínač „Pozastavit překlady“ v aplikaci i v notifikaci / dlaždici Rychlého nastavení.
- Žádný přístup k databázi WhatsAppu, žádné neoficiální API.

---

## 7. Klasifikace funkcí

### ✅ Plně realizovatelné
- Čtení textu viditelných zpráv přes AccessibilityService *(podmínka: WhatsApp nepoužívá `accessibilityDataSensitive` – ověří spike)*
- Abstrakce TranslationEngine, ML Kit cs↔sq, OpenAI přes proxy, cache, fallback
- Odchozí překlad: náhled nad klávesnicí, úprava, vložení do pole (`ACTION_SET_TEXT`), bez automatického odeslání
- Vlastní IME s překladovou lištou
- Aplikace v Compose/M3: stav služeb, nastavení, zapnutí a vypnutí, disclosure
- Předpřeklad z notifikací

### ⚠️ Realizovatelné s omezeními
- **Panel pod zprávou**: jen překryv, zakrývá obsah pod bublinou
- **Pohyb se scrollem**: zpoždění a cukání, doporučené skrytí během scrollu
- **Rozlišení příchozí/odchozí**: heuristika (fajfky, poloha)
- **Identita zprávy a deduplikace**: heuristika bez skutečného ID, okrajové případy (stejné texty, upravené zprávy)
- **Originál pod odeslanou zprávou**: párování podle textu, selže po ruční úpravě před odesláním
- **Odolnost vůči verzím WhatsAppu**: vyžaduje údržbu selektorů po aktualizacích
- **Distribuce přes Google Play**: deklarace a schválení nejisté
- **Sideload**: uživatel musí povolit omezená nastavení
- **OEM (Xiaomi/HyperOS, Samsung…)**: agresivní správa baterie může službu vypínat

### ❌ Nelze spolehlivě realizovat
- Skutečná integrace do UI WhatsAppu (překlad jako součást bubliny, odsunutí dalších zpráv)
- Pixelově přesné sledování scrollu při 60/120 fps
- Záruka funkčnosti po aktualizacích WhatsAppu, případně pokud Meta nasadí `accessibilityDataSensitive`
- Funkce na Androidu 17+ se zapnutým Advanced Protection Mode
- Překlad zpráv, které nejsou na obrazovce (historie, DB), hlasovek a textu v obrázcích (mimo rozsah)

---

## 8. Technická rizika

| # | Riziko | Pravděp. | Dopad | Mitigace |
|---|---|---|---|---|
| R1 | WhatsApp označí zprávy jako `accessibilityDataSensitive` | nízká–střední | **kritický** (konec funkce) | Ověřit ve spiku, sledovat betaverze WhatsAppu. Plán B = jen odchozí překlad přes IME. |
| R2 | Změna ID view / obfuskace po aktualizaci | vysoká | vysoký | Konfigurovatelné selektory, strukturní fallback, testy na snapshotech stromu, při selhání nic nezobrazit |
| R3 | Nepřesné nebo zpožděné pozicování při scrollu | vysoká | střední | Skrytí během scrollu, rychlá cesta přes `scrollDeltaY`, validace hranic |
| R4 | Zákryt obsahu pod bublinou | jistá | střední | Režimy B/C/D, omezení výšky panelu, průhlednost |
| R5 | Zamítnutí v Google Play | střední–vysoká | vysoký (distribuce) | MVP sideload / interní testování, pečlivá deklarace a video, název bez „WhatsApp“ |
| R6 | Výkon / baterie (časté čtení stromu) | střední | střední | Debounce, čtení jen podstromu seznamu, recyklace uzlů, pozastavení mimo chat |
| R7 | Kvalita překladu (ML Kit přes EN) | střední | střední | LLM jako výchozí, ML Kit jen záloha |
| R8 | Soukromí: odesílání obsahu třetí straně | jistá (u LLM) | právní / reputační | Disclosure, souhlas, volba „jen offline (ML Kit)“, proxy bez logů |
| R9 | Fragmentace OEM a verzí Androidu | střední | střední | Testovací matice (Pixel, Samsung, Xiaomi; Android 13–17) |

---

## 9. Implementační plán

### Etapa 1b – Spike na zařízení (2–3 dny) – **další krok**
Minimální diagnostická aplikace, ne MVP:
1. AccessibilityService, která vypíše strom WhatsAppu do JSON (ID, třídy, hranice, příznaky) pro otevřený chat.
2. Overlay, který kolem detekovaných bublin kreslí barevné rámečky (zelená = příchozí, modrá = odchozí) a sleduje scroll.
3. Test `ACTION_SET_TEXT` do `entry` (bez odeslání).

**Go/No-go kritéria:**
- [ ] texty zpráv jsou čitelné (žádný `accessibilityDataSensitive`)
- [ ] hranice bublin přesné na ±4 px v klidu
- [ ] směr zprávy správně u ≥ 99 % zpráv testovacího chatu (1:1 i skupina)
- [ ] po scrollu se rámečky do 150 ms ustálí na správném místě
- [ ] `ACTION_SET_TEXT` funguje

### Etapa 2 – Prototyp příchozích (cca 1,5–2 týdny)
Kostra projektu a modulů → onboarding a disclosure → služba a pipeline → WhatsApp adaptér → cache a ML Kit + proxy engine → overlay režim C/A → scroll (skrývání, přepočet) → deduplikace → testy na uložených snapshotech stromu.

### Etapa 3 – Odchozí překlad (cca 1 týden)
Sledování `entry` → náhled nad klávesnicí (Upravit / Vložit překlad) → `ACTION_SET_TEXT` → mapování originálu pro zobrazení pod odeslanou zprávou → (volitelně) IME.

### Etapa 4 – Stabilizace
Testovací scénáře ze zadání + matice zařízení, měření latence a spotřeby, chování po restartu (služba se obnoví sama, pokud ji OEM nevypne) a nastavení OEM výjimek z optimalizace baterie.

---

## 10. Otevřené otázky na zadavatele

1. Na jakém zařízení / verzi Androidu / verzi WhatsAppu se bude testovat?
2. Distribuce: osobní použití (sideload) nebo Google Play?
3. Je přijatelné, že inline panel zakryje část obsahu pod zprávou? Nebo preferuješ režim C/D?
4. Souhlasíš s odchozím překladem přes accessibility (Gboard zůstane) místo vlastní klávesnice?
5. Kde poběží proxy (Cloudflare Workers / vlastní server)?

---

### Rozhodnutí zadavatele (13. 11. 2026)

1. Testovací zařízení: **Samsung Galaxy S21**
2. Distribuce: **jen APK (sideload)**, Google Play zatím ne. Platí restricted settings (kap. 1, bod 3).
3. Zákryt obsahu inline panelem: **zatím přijatelný**
4. Odchozí překlad: **přes accessibility (varianta A)**, vlastní klávesnice jen jako záloha

---

## 11. Poznámka k názvu

„WhatsApp“ v názvu aplikace nebo v ikoně se v Google Play prakticky nedá použít (ochranná známka Meta). Doporučuji produktový název **ChatLingo** (už se objevuje v zadání u překladového panelu). WhatsApp uvádět jen v popisu jako podporovanou aplikaci.

---

## 12. Zdroje

- [ML Kit translation: supported languages](https://developers.google.com/ml-kit/language/translation/translation-language-support)
- [ML Kit on-device translation – přehled a omezení (pivot přes EN)](https://developers.google.cn/ml-kit/language/translation?hl=en)
- [Google Play – Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Android Developers Blog – accessibilityDataSensitive (Android 16), isAccessibilityTool & Play Protect](https://android-developers.googleblog.com/2025/12/enhancing-android-security-stop-malware.html?m=1)
- [Esper – Android 14 ACCESSIBILITY_DATA_PRIVATE / restricted settings](https://www.esper.io/blog/android-14-accessibility-security-feature)
- [Android Authority – Android 15 restricted settings & sideloading](https://androidauthority.com/android-15-restricted-settings-sideloading-3481098)
- [Android Authority – Android 17 Beta 2: Advanced Protection blokuje non-accessibility tools](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860)
- [The Hacker News – Android 17 Accessibility API restrictions](https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html)
