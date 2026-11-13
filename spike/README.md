# Etapa 1b – ChatLingo Spike (diagnostická aplikace)

Jednorázová testovací aplikace. **Není to MVP.** Ověřuje na reálném zařízení (Samsung Galaxy S21) předpoklady z `TECHNICAL_DOCUMENTATION.md`:

1. Jde přečíst text zpráv WhatsAppu přes AccessibilityService (žádné `accessibilityDataSensitive`)?
2. Sedí hranice bublin a směr (příchozí/odchozí)?
3. Jak rychle a přesně se dá překryv přepočítat při scrollu?
4. Funguje `ACTION_SET_TEXT` do vstupního pole?

Nepoužívá žádné knihovny, jen Android SDK a Kotlin. Nic neposílá na internet, nemá oprávnění INTERNET a nikdy neklikne na Odeslat.

## Sestavení APK

**Varianta A – Android Studio**
1. *File → Open* → složka `spike/ChatLingoSpike`.
2. Počkej na Gradle sync (AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0). Pokud Studio nabídne upgrade AGP, můžeš ho odmítnout.
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)* → `app/build/outputs/apk/debug/app-debug.apk`.
   Případně připoj S21 kabelem (s USB laděním) a dej ▶ Run.

**Varianta B – bez Android Studia (GitHub Actions)**
Přesuň soubor `spike/spike-apk.yml` do `.github/workflows/spike-apk.yml` (v kořeni `WhatsAppLingo`) a nahraj složku do GitHub repozitáře. Workflow sestaví APK a nabídne ho ke stažení v *Actions → spike-apk → Artifacts*.

## Instalace na S21

1. Zkopíruj APK do telefonu a otevři ho (povol instalaci z Moje soubory / prohlížeče).
2. *Nastavení → Usnadnění → Nainstalované aplikace → ChatLingo Spike* → zapnout.
   Pokud je položka zašedlá: *Nastavení → Aplikace → ChatLingo Spike → ⋮ → Povolit omezená nastavení* a zkus to znovu.

## Testovací scénář (cca 20 min)

| # | Krok | Co sleduješ |
|---|---|---|
| 1 | Otevři chat 1:1 s příchozími i odchozími zprávami | Rámečky: 🟩 příchozí, 🟦 odchozí, 🟧 neurčeno. Popisek `m:` = podle fajfek, `p:` = podle polohy, `FB` = text nalezen náhradní cestou |
| 2 | Zprávy různé délky, s emoji, odkazem, citací (odpověď na zprávu), přeposlaná | Rámeček obepíná bublinu, ne celý řádek. Citace se nepočítá jako text zprávy |
| 3 | Pomalý a rychlý scroll v režimu **SLEDUJ** | Jedou rámečky se zprávami? Jak moc cukají nebo se zpožďují? |
| 4 | Totéž v režimu **SKRYJ** | Rámečky po zastavení naskočí správně a rychle? |
| 5 | Otevři a zavři klávesnici, nech si přijít novou zprávu, otoč telefon | Přepočet polohy |
| 6 | Skupinový chat (se jmény odesílatelů, případně profilovkami) | Směr a hranice |
| 7 | **SET TEXT** v chatu | Text se objeví v poli, nic se neodešle |
| 8 | Odejdi na plochu, stáhni lištu oznámení | Rámečky a panel zmizí |
| 9 | **DUMP**: chat 1:1, skupina, seznam chatů | Soubory v `Download/ChatLingoSpike/` |

Potom otevři aplikaci ChatLingo Spike → *Obnovit výsledky* a udělej screenshot.

**Pošli mi:** JSON dumpy, screenshot výsledků a ideálně 2–3 screenshoty WhatsAppu s rámečky (klidně i nahrávku obrazovky při scrollu). Texty zpráv jsou v dumpu ve výchozím stavu maskované (`xxxx`).

## Go/No-go kritéria

- [ ] texty zpráv jsou čitelné (rámečky se vůbec objeví)
- [ ] hranice bublin přesné (v klidu ±4 px)
- [ ] směr správně u ≥ 99 % zpráv (1:1 i skupina)
- [ ] po scrollu se rámečky ustálí do 150 ms (hodnota „Ustálení po scrollu“, obsahuje i 80ms debounce)
- [ ] SET TEXT funguje

## Známá omezení spiku

- ID prvků (`message_text`, `status`, `entry`) jsou předpoklady. Smyslem dumpu je zjistit skutečné.
- Rozpoznání RTL rozložení (arabština apod.) se neřeší.
- Ve split-screen se bere jen horní aplikační okno.
