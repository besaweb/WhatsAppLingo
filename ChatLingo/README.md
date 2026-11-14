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

## Co zatím neumí (další kroky)
- Odchozí překlad (Etapa 3), překlad přes LLM/proxy, ochrana URL a čísel před překladem.
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
