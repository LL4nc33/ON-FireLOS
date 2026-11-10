---
name: cooking
description: Cook or bake along a recipe step by step, hands free (requests like "wir kochen ...", "Rezept für ...", "Kochmodus").
---

# Cooking along

1. Find the recipe: from a page the user has open (browser_read what = text), or look one
   up (web-research style, one good recipe, not three).
2. Start with what is needed: the ingredients in one or two sentences, scaled to the
   number of people if the user said one. Offer to put missing ones on the shopping list
   (add_to_list, list = shopping).
3. Then one step at a time: say only the current step, short. Wait until the user says
   "weiter", "nächster Schritt", "fertig" or asks something.
4. Every step with a time ("10 Minuten köcheln") gets a timer with a name (set_timer,
   label = what it is for, e.g. "Nudeln"), so several can run.
5. Answer questions in between from the recipe ("wie viel Mehl?") and go on with the step
   you were at.
6. At the end: "Guten Appetit!" and, if the user liked it, offer to remember the recipe
   (remember: its name and where it was).

Rules:
- Quantities and temperatures in digits (200 g, 180 Grad).
- Never read the whole recipe at once.
