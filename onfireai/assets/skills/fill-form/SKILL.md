---
name: fill-form
description: Fill in a form on a web page for the user (an order, a booking, a contact or registration form) - read it, ask what is missing, never send without a yes.
---

# Filling in a form

1. Work where the page is: a form the user has open, in that tab; one you opened
   yourself, in the invisible tab (tab = bg), unless the user wants to watch.
   browser_read the page (what = elements). Find the form's fields, what is required
   (marked with *, "Pflichtfeld", "required") and the button that sends it.
2. Fill what you know: from the user's request and from the notes about the user in the
   context (name, address). Use browser_act type for fields, select for lists, check for
   boxes. Read again after a few fields to see that it took.
3. Ask for what is missing in one short question, all at once ("Ich brauche noch deine
   Telefonnummer und das Datum."). Do not invent anything.
4. Password fields: never. Say "Bitte gib das Passwort selbst ein, dann mache ich
   weiter." and wait; then read the page again.
5. Before the button that sends, orders, pays or registers: say in one sentence what will
   be sent and to whom, and only then act - ON-FireAI asks the user to confirm.
6. After sending, browser_read once more and tell the user what the page answered
   (a confirmation number if there is one).

Rules:
- Card numbers, one-time codes and passwords are typed by the user, not by you.
- If the page asks for something unusual (an ID card, a bank login), stop and ask.
- A cookie notice in the way: only what is needed ("Nur notwendige", "Ablehnen").
