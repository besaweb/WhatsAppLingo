# ChatLingo (prac. název WhatsAppLingo)

Android aplikace pro automatický překlad zpráv ve WhatsAppu mezi **češtinou a albánštinou**. Překlad se zobrazuje jako přístupnostní překryv (overlay) u příslušné zprávy. WhatsApp se nijak nemění.

## Stav projektu

| Etapa | Stav |
|---|---|
| 1 – Technická analýza | ✅ hotovo, viz [TECHNICAL_DOCUMENTATION.md](TECHNICAL_DOCUMENTATION.md) |
| 1b – Spike na reálném zařízení (go/no-go) | 🧪 diagnostická aplikace připravená v [spike/](spike/README.md), čeká na test na S21 |
| 2 – Prototyp příchozích překladů | – |
| 3 – Odchozí překlad | – |
| 4 – Stabilizace | – |

## Klíčové závěry analýzy

- Překlad „pod zprávou“ jde zobrazit **jen jako překryv** (`TYPE_ACCESSIBILITY_OVERLAY`). Zakryje obsah pod bublinou a nemůže odsunout další zprávy.
- Při scrollu se panely dopočítávají s malým zpožděním. Výchozí chování: během scrollu panely skrýt.
- ML Kit podporuje cs i sq (překládá přes angličtinu). Pro kvalitu je výchozí LLM přes proxy, ML Kit slouží jako offline záloha.
- Odchozí překlad: náhled nad klávesnicí + vložení do pole WhatsAppu. Nikdy se neodesílá automaticky.
- Hlavní rizika: změny UI WhatsAppu, možné `accessibilityDataSensitive`, pravidla Google Play pro Accessibility API.

## Plánovaný stack

Kotlin · Jetpack Compose · Material 3 · MVVM · Coroutines/Flow · Room · DataStore · AccessibilityService · TYPE_ACCESSIBILITY_OVERLAY · (volitelně) InputMethodService · Retrofit/OkHttp · ML Kit Translation · backendová proxy pro LLM

## Soukromí

- Služba čte jen okna WhatsAppu, nikdy pole s heslem.
- Bez souhlasu se obsah zpráv neukládá.
- Před zapnutím aplikace jasně uvede, které údaje odchází k překladovému poskytovateli.
- Automatické překlady jdou kdykoliv vypnout.
