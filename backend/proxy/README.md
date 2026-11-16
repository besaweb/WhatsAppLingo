# ChatLingo proxy (Cloudflare Worker + Gemini)

Malý server, který přeloží text přes Gemini API. API klíč je jen zde, v aplikaci není.
Obsah zpráv se neloguje ani neukládá. Cloudflare Workers i Gemini mají bezplatnou úroveň
(limity a podmínky si ověř na jejich stránkách; u bezplatné úrovně Gemini může Google data
používat ke zlepšování služeb).

## Nasazení (jednorázově, cca 10 minut)

Potřebuješ Node.js a účet na Cloudflare (zdarma).

1. Klíč Gemini: https://aistudio.google.com/apikey → *Create API key*.
2. Vymysli si dlouhý náhodný token (např. 32 znaků). Použije se jako heslo aplikace k proxy.
3. V této složce spusť:

```bash
npx wrangler login
```

```bash
npx wrangler secret put GEMINI_API_KEY
```

```bash
npx wrangler secret put APP_TOKEN
```

```bash
npx wrangler deploy
```

Po nasazení wrangler vypíše adresu typu `https://chatlingo-proxy.<tvuj-ucet>.workers.dev`.

## Rychlý test

```bash
curl -s -X POST "https://chatlingo-proxy.<tvuj-ucet>.workers.dev" -H "Content-Type: application/json" -H "X-Auth-Token: <TVUJ_TOKEN>" -d "{\"text\":\"Dobrý den, jak se máte?\",\"source\":\"cs\",\"target\":\"sq\"}"
```

Odpověď má tvar `{"translation":"..."}`.

## V aplikaci
V ChatLingo zapni **Kvalitní překlad přes LLM**, vyplň adresu proxy a token a stiskni **Otestovat spojení**.
Když proxy selže nebo není dostupná, aplikace automaticky použije překlad v telefonu (ML Kit).

## Poznámky
- Zdrojový/cílový jazyk: `cs, sq, en, de, sk, pl, ru, uk, es, fr, it, hr`.
- Změna modelu: `MODEL` v `wrangler.toml` a znovu `npx wrangler deploy`.
- Token nikomu nesděluj. Při úniku nastav nový (`wrangler secret put APP_TOKEN`) a změň ho i v aplikaci.
