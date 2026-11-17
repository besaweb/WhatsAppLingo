# ChatLingo – prototyp příchozích překladů (Etapa 2, krok 1)

Prototyp, ne MVP. Staví na zjištěních ze spiku (`../spike/SPIKE_RESULTS.md`): selektory WhatsAppu,
pravidla směru, skrytí panelů během scrollu.

## Co umí
- Čte viditelné zprávy ve WhatsAppu (`com.whatsapp`, `com.whatsapp.w4b`) přes AccessibilityService.
- Pod **příchozí** zprávou zobrazí překlad (překryv, zakrývá to, co je pod bublinou).
- Překlad a rozpoznání jazyka běží **v telefonu** (ML Kit). Zprávy, které už jsou v cílovém jazyce, se nepřekládají.
- Během scrollu se panely skrývají, po zastavení se zobrazí znovu.
- Režim zobrazení: jen poslední příchozí zpráva (výchozí) / všechny viditelné příchozí.
- Souhlas uživatele před použitím, hlavní přepínač „Překlady zapnuté“.
- Nikdy neklikne na Odeslat a nic ve WhatsAppu nemění.

## Plovoucí tlačítko
Nad WhatsAppem se zobrazuje kulaté tlačítko **CL** (jinde ne). Klepnutí zapne nebo vypne
automatický překlad příchozích i psaných zpráv (zelené = zapnuto, šedé přeškrtnuté = vypnuto).
Tažením se přesune, po puštění se přichytí k okraji a poloha se pamatuje.
Tlačítko jde skrýt v nastavení aplikace.

## Překlad psané zprávy (odchozí)
Při psaní do pole zprávy se po pauze 0,7 s zobrazí nad polem panel s překladem
(z „Překládat do“ do „Jazyk příchozích zpráv“, např. čeština → albánština). Tlačítko
**Vložit překlad** vloží text do pole (`ACTION_SET_TEXT`), **Zavřít** panel skryje.
Zprávu vždy odesílá uživatel sám, služba na Odeslat nikdy neklikne.

## Kvalitnější překlad přes LLM (volitelné)
ML Kit překládá cs↔sq přes angličtinu, takže gramatika není ideální. V aplikaci jde zapnout
**Překlad přes LLM**: text příchozí zprávy se pošle na tvou vlastní proxy
(`../backend/proxy`, Cloudflare Worker + Gemini) a vrátí se překlad. Když proxy selže,
použije se automaticky ML Kit. Postup nasazení proxy: `../backend/proxy/README.md`.
Texty zpráv v tomto režimu opouštějí telefon (je uvedeno v souhlasu).

## Co zatím neumí (další kroky)
- Odchozí překlad (Etapa 3), ochrana URL a čísel před překladem.
- Konfigurace selektorů v JSON, oddělené Gradle moduly, Compose UI (zatím jedna aplikace s běžnými view).
- Úprava polohy panelu tak, aby nezakrýval další zprávu.

## Sestavení
GitHub Actions: workflow `chatlingo-apk` (`.github/workflows/chatlingo-apk.yml`) sestaví
`ChatLingo/` a nabídne APK jako Artifact `chatlingo-debug`.
Lokálně: Android Studio → otevřít složku `ChatLingo`.

## Test na zařízení
1. Nainstaluj APK, otevři ChatLingo, zaškrtni souhlas.
2. Zapni službu: *Nastavení → Usnadnění → Nainstalované aplikace → ChatLingo*
   (u zašedlé položky: *Info o aplikaci → ⋮ → Povolit omezená nastavení*).
3. Vyber jazyky. Výchozí je albánština → čeština. Pro zkoušku na českých chatech zvol např. čeština → angličtina.
4. Stiskni **Stáhnout jazykové modely** (potřebuje internet, jednou).
5. Otevři chat ve WhatsAppu. Po zastavení scrollu se pod poslední příchozí zprávou objeví překlad.
