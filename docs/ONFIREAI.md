# ON-FireAI

The assistant of ON-FireLOS: you talk, it answers with the system voice and does
things on the tablet. The thinking happens in a language model of your choice -
any server that speaks the OpenAI API (llama.cpp, [llama-tq](https://github.com/LL4nc33/llama-tq),
Ollama, LM Studio, LocalAI, or a cloud service). Nothing of it is fixed in the
ROM: addresses and keys are typed in on the tablet and stay there.

Source: `onfireai/` (a system app, `org.onfirelos.ai`), placed into the build
tree by `scripts/onfireai-into-tree.sh`.

## Starting it

- a long press on home (it is the tablet's assistant), or the launcher icon
- sharing a text to *ON-FireAI*: the text is the question
- a sheet comes up from the bottom; the microphone opens at once (can be turned
  off), or type into the field

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
| timer, alarm | the clock app, without opening it |
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
| notifications | read and sum up what is new (when turned on) |
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
  it asks (yes/no, also by voice).
- Password fields are never read; apps on the block list are never read.

## MCP servers

Settings > System > ON-FireAI > MCP servers: the address of an MCP server
(Streamable HTTP) and an optional key. Its tools are lent to the model next to the
built-in ones; tools the server marks as destructive ask before they run.

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

- A wake word ("hey ...") without touching the tablet.
- Talking over an answer to interrupt it (the microphone button does it now).
