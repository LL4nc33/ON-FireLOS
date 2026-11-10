---
name: compare-prices
description: Find and compare prices for a product, new or used (requests like "was kostet ...", "wo bekomme ich ... am billigsten", "such auf willhaben ...").
---

# Comparing prices

1. Pin down the product: model, size, new or used. If it is unclear, ask one question.
2. Open the search results directly by their address, in the background (browser_open
   background = true), then browser_read what = text - no typing, no clicking, no forms:
   - new, Austria: https://geizhals.at/?fs=WORDS
   - new, Germany: https://www.idealo.de/preisvergleich/MainSearchProductCategory.html?q=WORDS
   - used, Austria: https://www.willhaben.at/iad/kaufen-und-verkaufen/marktplatz?keyword=WORDS
   - used, Germany: https://www.kleinanzeigen.de/s-WORDS/k0
   (WORDS with + between them, e.g. fire+hd+10). If the user names another site, use it.
   A cookie notice in the text: ignore it and read on; never click it away in the
   background.
3. Note the 3 best offers: price, shop or seller, condition, shipping, how far away for
   pick-up.
4. Answer in 2 or 3 sentences: the cheapest sensible offer, and the one you would
   recommend if it is a different one, with why ("10 Euro mehr, aber mit Garantie").
5. Offer to open the best one visibly in the browser. Do not buy, reserve or message a
   seller: that is the user's step (ON-FireAI asks before any of it anyway).

Rules:
- Prices with currency, in digits (129 Euro).
- Say when an offer looks too good to be true.
