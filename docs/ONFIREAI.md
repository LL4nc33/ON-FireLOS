# ON-FireAI

The assistant of ON-FireLOS: you talk, it answers with the system voice and does
things on the tablet. The thinking happens in a language model of your choice -
any server that speaks the OpenAI API (llama.cpp, [llama-tq](https://github.com/LL4nc33/llama-tq),
Ollama, LM Studio, LocalAI, or a cloud service). Nothing of it is fixed in the
ROM: addresses and keys are typed in on the tablet and stay there.

Source: `onfireai/` (a system app, `org.onfirelos.ai`), placed into the build
tree by `scripts/onfireai-into-tree.sh`.

## Starting it

- the wake word, "Hey Fire" (when turned on, see below)
- a long press on home (it is the tablet's assistant), or the launcher icon
- sharing a text to *ON-FireAI*: the text lands in the field, to send with one tap
  (another app's text never runs anything on its own)
- a sheet comes up from the bottom; the microphone opens at once (can be turned
  off), or type into the field
- empty, it greets by the time of day and offers three things to try with a tap
- its top edge glows, from the green of the design into ember: it breathes with
  the voice while listening, travels across while thinking, rests while speaking
  and goes out when done; what was said shows as a bubble, done things as chips
  with a tick

## At the lock screen

The sheet comes up over the lock screen too (the wake word on a charging tablet
is the common case). Anyone in the room may be asking then, so ON-FireAI answers
general questions and uses timer, alarm, media, volume, light/dark, weather and
the like, but keeps everything personal out until the tablet is unlocked: no
notes, no notifications, no earlier conversation (on screen or for the model),
no operating the screen, no opening apps, no MCP servers. Asked for those, it
says to unlock first; the settings button asks to unlock, too.

## A conversation

- What you say goes to the model with a short context: date and time, battery,
  the next alarm, what is playing, notes about you.
- The answer is shown as it is written and spoken sentence by sentence, so the
  first sentence plays while the model still writes.
- After an answer the microphone stays open a few seconds for a follow-up, and the
  conversation keeps its thread for 30 minutes ("and the day after?").
- Short commands go without the model, in well under a second: pause, go on,
  next/previous track, louder, quieter, "timer 5 minutes".
- A change of design (light/dark) or an app a tool opened does not cut an
  answer short: the conversation lives apart from the sheet.

## What it can do (tools)

| Tool | Does |
|---|---|
| timer, alarm | the clock app, without opening it; timers listed with the time left, paused, resumed, a minute more, stopped, deleted; alarms listed, on, off, deleted (through a small interface ON-FireLOS gives the clock app, open only to apps signed like the system) |
| reminders | "remind me at six to ...": a notification and said aloud at the time, after a soft chime; listed and deleted by voice; they outlast a restart |
| calendar | what is coming up (from every calendar on the tablet), new appointments; without an account they go into a calendar "Tablet" on the device, which the calendar app shows too; the next appointment within 12 hours goes with each question |
| media, now playing | play, pause, next, previous in any player |
| play music | by search in Spotify (or another player that can) |
| volume, brightness | media volume, screen brightness |
| light/dark | the system design |
| Wi-Fi, Bluetooth, do not disturb | on/off |
| open app | by name, web apps on the home screen included |
| open page | in its installed web app where there is one, else the browser |
| web search | results from a SearXNG of your own to answer from, or the browser |
| YouTube | find videos (with SearXNG), open them in the YouTube web app |
| weather | Open-Meteo, here (the time zone's city) or any place, up to 7 days |
| memory | keep, find and delete notes; search earlier conversations |
| notifications | read and sum up what is new (when turned on; Android asks once for notification access) |
| screen | operate apps (when turned on, see below) |
| MCP | the tools of your MCP servers |

## Operating the screen

When turned on (Settings > System > ON-FireAI > Screen), ON-FireAI can work in
apps for you, step by step: it reads the app on screen as a short numbered list
of what is there (buttons, fields, texts), taps, types, scrolls, goes back or
home - like adb, but on the tablet. Asked "look up the build number in
Settings", it opens Settings, reads, taps *About tablet*, reads again and answers.

- A pill at the top shows the step ("Tapping About tablet") while it works in
  another app, and at the end its answer; its cross stops it.
- Before a tap on something that sends, buys, pays, deletes, installs or resets,
  it asks: on the pill (yes/no) while it works in another app, else in the sheet,
  also by voice. An answer with a no in it ("bitte nicht") is a no.
- It acts only on the app it last read: if another app came up since, it reads
  again first.
- Password fields are never read; apps on the block list are never read nor
  operated, and their notifications are left out too, as are notifications an
  app marks secret.
- What it reads (web, screen, notifications, MCP results) is data for the model,
  never instructions; the log keeps only which tool ran, not what it saw.

## The wake word

Settings > System > ON-FireAI > Wake word. The word - "Hey Fire", "Okay Fire",
"Hey Computer", "Computer", "Hey Jarvis" or one's own - is recognised on the tablet
itself by a small keyword spotting model (sherpa-onnx, zipformer 3.3M, offline);
nothing is sent anywhere before it. On it, a short sound, and the sheet comes up
listening.

- It listens only while the tablet charges, unless that is turned off - it keeps
  one of the four cores about a quarter busy (measured on my device) and needs
  about 100MB of memory.
- It pauses while ON-FireAI itself listens or speaks.
- The model knows English sounds; English-sounding words of two or three
  syllables work best. A word of one's own is split into the model's pieces on
  the tablet.

## The microphone

This tablet's input for speech recognition (`VOICE_RECOGNITION`) is all but deaf:
noise at 15, speech not to be seen (measured on my device). ON-FireAI records from
the raw input (`UNPROCESSED`), which hears speech at 1000-2000, for both the
wake word and Whisper.

## MCP servers

Settings > System > ON-FireAI > MCP servers: the address of an MCP server
(Streamable HTTP) and an optional key. Its tools are lent to the model next to the
built-in ones. As the MCP spec has it, a tool asks before it runs unless the server
marks it read-only or not destructive. A server that does not answer is left alone
for 2 minutes, so it does not slow every question.

## Listening

- **Whisper server** (recommended): the tablet records, notices the pause at the end
  and sends what was said to an OpenAI-compatible `/v1/audio/transcriptions`
  (faster-whisper-server, whisper.cpp with `--inference-path
  /v1/audio/transcriptions`, LocalAI, OpenAI). Works without any of Google's
  software.
- **Android's recognition**: Google's speech services, where they are installed. On
  this tablet without Google's services their network recognition cannot connect
  and their offline pack cannot be downloaded (checked 2026-11-08), so they only
  give the voice, not the ears.

## Memory

- Notes: kept when you ask ("remember that ..."), and - if allowed - when the model
  finds something worth keeping. The notes that fit go with each question.
- Search by meaning (optional): with an embeddings server set (`/v1/embeddings`,
  e.g. llama.cpp started with `--embeddings`), each note gets a vector, kept in the
  app's database, and notes are found by what they mean. Without one, by their
  words.
- Conversations: every one, one list per day, only on the tablet.
- Settings > System > ON-FireAI > Notes and conversations: look at them, delete one,
  delete everything.

## Settings (Settings > System > ON-FireAI)

A card at the top says how things stand: ready (with the model's short name and
the wake word), the server not reachable, or what is still to set up; errors read
in words ("not reachable", "key refused"), not as Java names them.

Language model (address, key, model, connection test), voice (speak answers,
follow-ups, listen right away, the system voice), listening (Google or Whisper
server, its address and model), start (default assistant), screen (operate the
screen, blocked apps, read notifications), tools (web search address), memory
(notes and conversations, remember on its own, keep conversations, search by
meaning), MCP servers.

## Speed (measured on my device, 2026-11-08)

- llama-tq with Qwen3.8-27B (4-bit) on a GPU in the home network: a tool round
  about 2.7s, answers at about 55 tokens/s; the first sentence speaks after
  about 1-2s.
- whisper.cpp small on a PC's CPU: about 4s for a sentence; on a GPU well under 1s.
- Operating Settings to find the build number: 4 steps, about 6s.

## Not yet

- Talking over an answer to interrupt it (the microphone button does it now).
