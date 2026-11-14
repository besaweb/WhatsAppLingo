# Etapa 1b – Výsledky spiku (ChatLingo Spike)

> Datum měření: 14. 11. 2026 · Zařízení: Samsung Galaxy S21 (SM-G991B), Android 15 (SDK 35) · WhatsApp 2.26.44.90 (264409011)
> Verze spiku: v1 → v2 → v3 (sestavováno přes GitHub Actions). Níže uvedená čísla jsou z **v3**, pokud není uvedeno jinak.
> Texty zpráv a jména v dumpech byla maskovaná. Tento dokument neobsahuje obsah žádné konverzace.

## Verdikt: **GO**

Všechna kritéria kromě rychlosti ustálení jsou splněná. Rychlost je na hraně a doporučujeme upravit prahovou hodnotu (viz níže). Žádné zjištění nebrání pokračování k Etapě 2 (prototyp příchozích).

## Go/No-go kritéria

| Kritérium | Požadavek | Naměřeno | Stav |
|---|---|---|---|
| Texty zpráv čitelné (bez `accessibilityDataSensitive`) | ano | Texty se čtou v 1:1 i skupině, vstupní pole se čte i zapisuje | ✅ |
| Hranice bublin | ±4 px v klidu | Rámeček = `main_layout`, vizuálně přesně obepíná bublinu (1:1, skupina, citace, náhled odkazu) | ✅ |
| Směr zprávy | ≥ 99 % | **0 neurčených z 549** bublin (bez uříznutých). Shoda značky s polohou 656/656 (1:1 i skupina) | ✅ |
| Ustálení po scrollu | ≤ 150 ms | Skupina (4–5 bublin): medián ≈ 120 ms. Chat 1:1 (8–9 bublin): medián ≈ 165 ms, p90 ≈ 250 ms | ⚠️ |
| `ACTION_SET_TEXT` | funguje | `OK (id:com.whatsapp:id/entry)`, text v poli, nic se neodeslalo | ✅ |

## Co se potvrdilo

- WhatsApp 2.26.44.90 vystavuje obsah zpráv AccessibilityService bez omezení.
- Seznam zpráv je `android:id/list` (třída `ListView`). Seznam chatů je `RecyclerView`. Chat se pozná podle vstupního pole `com.whatsapp:id/entry`.
- Řádek zprávy (`conversation_row_text`, `conversation_row_image`, `conversation_row_sticker`) je přes celou šířku displeje. **Přesnou bublinu dává vnořený `main_layout`**.
- Text zprávy je v `message_text`, u obrázku s popiskem v `caption`, u zpráv jen s odkazem je text v náhledu (`title`, `snippet`).
- Odchozí zprávy mají `status` (fajfka s popiskem) vedle `date`. Příchozí ve skupině mají `name_in_group_tv` a avatar (`group_profile_pic` nebo `group_profile_initials`).
- Poloha bubliny: odchozí končí u pravého okraje (rightGap ≈ 3 % šířky), příchozí začínají vlevo (≈ 3 % v 1:1, ≈ 13 % ve skupině kvůli avatarům).
- Reakce (`reactions_bubble_layout`), tlačítko přeposlat (`action_button`) a citace (`quoted_*`) leží mimo text zprávy a dají se bezpečně přeskočit.

## Zjištění z iterací

| Problém | Příčina | Řešení v spiku |
|---|---|---|
| Ve skupině směr `UNKNOWN` | Skupinová bublina leží uprostřed (144–914 px), staré pravidlo „levý vs. pravý okraj“ neurčilo | Značka `status` → odchozí; jméno/avatar → příchozí; poloha: `rightGap ≤ 7 %` odchozí, `leftGap ≤ 20 %` příchozí |
| Zkreslená statistika směru | `UNKNOWN` se do procent nepočítal | Samostatné počítadlo neurčených (bez uříznutých bublin) |
| `partial` bylo vždy `false` | Souřadnice bubliny se ořezávají na viewport | Uříznutá = horní/dolní hrana leží na hraně seznamu |
| Sender jako text zprávy | `name_in_group_tv` se bral jako „fallback“ text | Přidáno do vyloučených ID |
| Pomalý průchod | Průchod celým stromem, hledání seznamu i mimo chat | Seznam přes `findAccessibilityNodeInfosByViewId`, bublina z `main_layout`, přeskakování podstromů, bez hledání seznamu mimo chat |
| Zkreslené časy | Při screenshotu se aktivní okno přepne na Smart Capture a scan chat nenajde (389 ms, celý strom) | Při měření nedělat screenshoty / dump neslučovat s ním |

## Výkon

| Veličina | v1 (SLEDUJ) | v3 (SKRYJ) |
|---|---|---|
| Průměrný průchod stromem | 155–162 ms | 79–129 ms (podle obsahu) |
| Ustálení po scrollu – medián | 231 ms | ≈ 120–165 ms |
| `scrollDeltaY` v událostech | 46 / 308 | 28 / 327 |

- Čas průchodu roste s počtem viditelných bublin (≈ +10 ms na bublinu). Samotné ustálení = čekání po posledním scrollu (60 ms v režimu SKRYJ) + průchod.
- `scrollDeltaY` chodí jen občas, na rychlé posouvání rámečků se spolehnout nedá. Po každém scrollu je potřeba přepočet ze stromu.
- Režim **SKRYJ** (skrýt během scrollu, zobrazit po zastavení) je jednodušší a spolehlivý. Režim **SLEDUJ** cuká.

## Doporučení

1. **Pokračovat Etapou 2.** Hlavní riziko (R1: `accessibilityDataSensitive`) se u této verze WhatsAppu nepotvrdilo.
2. **Upravit práh rychlosti** z „≤ 150 ms“ na „medián < 200 ms a p90 < 300 ms“ pro režim SKRYJ. Pro překladový panel je zpoždění 0,15–0,25 s po zastavení scrollu přijatelné.
3. Případné zrychlení (až v MVP): hromadné vyhledání podle ID (`message_text`, `status`, `name_in_group_tv`) místo průchodu stromem po řádcích, počítat jen viditelné řádky.
4. **Výchozí režim scrollu SKRYJ**, SLEDUJ jen experimentálně.
5. Selektory držet v konfiguraci (`whatsapp.json`) a hlídat změnu verze WhatsAppu. Pokud se po aktualizaci nenajde `entry` nebo seznam, nezobrazovat nic.
6. Měření v produktu nikdy nedělat při screenshotu (okno Smart Capture) a nezahrnovat ho do statistik.

## Otevřené body

- **Ořez dlouhých textů:** jedna dlouhá zpráva v dřívějším dumpu skončila uprostřed slova. V pozdějším dumpu úplná. Ověřit u zpráv > 1000 znaků.
- **Skupinový chat s odchozími zprávami** a **směr v režimu RTL** se netestoval.
- Nálepky a velká emoji (`conversation_row_sticker`) nemají text a překlad se u nich nepoužije.
- Testováno jen na jedné verzi WhatsAppu a jednom zařízení (Samsung S21). Matice zařízení patří do Etapy 4.
- `w4b` (WhatsApp Business) nebyl testován.
