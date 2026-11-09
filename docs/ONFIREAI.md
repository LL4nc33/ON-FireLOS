# ON-FireAI

The assistant of ON-FireLOS: you talk, it answers with the system voice and does
things on the tablet. The thinking happens in a language model of your choice -
any server that speaks the OpenAI API (llama.cpp, [llama-tq](https://github.com/LL4nc33/llama-tq),
Ollama, LM Studio, LocalAI, or a cloud service). Nothing of it is fixed in the
ROM: addresses and keys are typed in on the tablet and stay there.

Source: `onfireai/` (a system app, `org.onfirelos.ai`), placed into the build
tree by `scripts/onfireai-into-tree.sh`.

## The first start

Opened without a server, ON-FireAI goes straight into a short guided setup
(once; later through *Einrichten* in the sheet or the settings):

1. a welcome: what it is, and that the thinking happens on your own server
2. the language model: it looks for OpenAI-compatible servers on the usual
   ports of the home network (11434 Ollama, 1234 LM Studio, 8080 llama.cpp,
   8000, 5001) and lists them with their model; tap one, or type an address
   and, if needed, a key. *Verbinden* checks the server at once and goes on
   only when it answers. The search takes about 25s on my device.
3. listening: the Whisper server (optional; without it you write) and the
   wake word, when the build has it

*Fertig* opens the sheet for a first question.

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

## On the charger

ON-FireAI's view is the screen saver (set once; changeable in Settings > Display >
Screen saver): the time large, the date, the weather, the next appointment (not at
the lock screen), running timers counting down and what is playing. It drifts a
little every minute so nothing burns in, and dims from 22:00 to 6:00. "Hey Fire"
works on top of it; a tap ends it.

## At the lock screen

The sheet comes up over the lock screen too (the wake word on a charging tablet
is the common case). Anyone in the room may be asking then, so ON-FireAI answers
general questions and uses timer, media, volume, light/dark, weather and the
like, but keeps everything personal out until the tablet is unlocked: no notes,
no notifications, no earlier conversation (on screen or for the model), no
calendar, no alarms, no operating the screen, no opening apps, no MCP servers.
Asked for those, it says to unlock first; the settings button asks to unlock, too.

- Lists: "put milk on the list" works, but the list itself does not show.
- Smart home: only lights, media players, heating and fans are switched, and only
  sensors besides those are read; doors, locks, switches and who is home stay out.
- A reminder's notification shows only that there is one.
- When the tablet locks while the sheet is open, the sheet clears as it comes up
  again over the lock screen, and an answer still being given stops.

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
- Whatever else plays (a video in the browser, music, the radio, sleep sounds)
  goes down to a fifth the moment the wake word is heard, stays down while
  ON-FireAI listens, thinks and speaks, and comes back 1.2s after the
  conversation ends; a follow-up question right after keeps it down. Android
  does this for every player that holds the audio focus; ON-FireAI's own radio
  and sounds turn themselves down.
- A video or station ON-FireAI starts waits until its short answer ("Okay, hier
  ist ...") is said, so the two never play over each other (12s at the most).

## Answer cards

Some answers come with a card above the words, and the words then keep to the
gist:

- weather: now large with its symbol, the next five days with their highs, lows
  and rain chances
- timers: counting down while the sheet is open
- alarms: their times, days and whether they are on
- reminders and appointments: a neat list
- what is playing: cover, title and buttons, from the player itself
- videos: up to 6 YouTube suggestions side by side with picture, length,
  title, channel and views; a tap opens one in the YouTube web app, "das
  zweite" by voice too

## What it can do (tools)

| Tool | Does |
|---|---|
| timer, alarm | the clock app, without opening it; timers listed with the time left, paused, resumed, a minute more, stopped, deleted; alarms listed, on, off, deleted (through a small interface ON-FireLOS gives the clock app, open only to apps signed like the system) |
| lists | shopping (the default), to do, or any list one names: several items at once, ticked off, cleared |
| routines | "when I say good morning, tell me the weather and my reminders": kept by voice; the phrase then runs it, an instruction the model carries out with all its tools, not a fixed list of steps; optionally on its own at a time of day on chosen weekdays. Before one is kept, it says what it will do and asks ("Routine „Guten Morgen“ anlegen: ...?"). Run at its time, with no one perhaps there, it does not operate the screen, open web pages, use MCP servers or make routines |
| reminders | "remind me at six to ...": a notification and said aloud at the time, after a soft chime; listed and deleted by voice; they outlast a restart |
| calendar | what is coming up (from every calendar on the tablet), new appointments; without an account they go into a calendar "Tablet" on the device, which the calendar app shows too; the next appointment within 12 hours goes with each question |
| media, now playing | play, pause, next, previous in any player |
| play music | by search in Spotify (or another player that can) |
| radio | internet radio by the station's name ("play FM4"), found in the free radio-browser.info directory and played by ON-FireAI with a notification and a media session |
| timers by name | several at once, each named after what it is for ("Nudeln"); "wie lange noch für die Nudeln", "stopp den Nudel-Timer" find it by its name; without a name nothing acts on all timers unless all are meant |
| wake up with radio | "weck mich um 7 mit Ö3", once or on weekdays: rings as an alarm clock (on time while asleep), the station fades in at the alarm volume over a minute; without network or station the alarm sound after 20s. A large window over the lock screen: snooze (9 minutes) or off, by voice too |
| sleep sounds | rain, sea, rushing, deep hum, made on the tablet (no download), fading in and out, optionally for a number of minutes |
| sleep timer | "mach in 30 Minuten aus": whatever plays (music, radio, sounds) fades out over 8s and stops; the volume is back for the next day |
| YouTube, play | "spiel ein Video über ...": finds the best match itself and opens it in the browser's YouTube web app once the answer is said; it starts at once, and one real tap on the player turns on the sound YouTube leaves off when nobody touched the page |
| YouTube, suggest | "zeig mir Videos über ...": the video card, nothing plays until one is chosen; found on YouTube directly, without a search server of your own |
| news | the headlines of the region's public broadcaster (ORF, Tagesschau, SRF, BBC by time zone and language; a feed of one's own possible), summed up |
| volume, brightness | media volume, screen brightness |
| light/dark | the system design |
| Wi-Fi, Bluetooth, do not disturb | on/off |
| open app | by name, web apps on the home screen included |
| open page | in its installed web app where there is one, else the browser |
| web search | results from a SearXNG of your own to answer from, or the browser |
| weather | Open-Meteo, here (the time zone's city) or any place, up to 7 days |
| memory | keep, find and delete notes; search earlier conversations |
| notifications | read and sum up what is new (when turned on; Android asks once for notification access) |
| screen | operate apps (when turned on, see below) |
| smart home | Home Assistant straight through its API (address and a long-lived token in the settings): what lights, heating, windows, sensors are like, and switching them. Lights, media players, heating and fans switch straight away; everything else (switches, scripts, scenes, doors, locks, alarm systems - a garage opener is often just a switch) asks first with the thing's own name ("Garagentor einschalten?", "Haustür aufsperren?") and does nothing at the lock screen |
| MCP | the tools of your MCP servers |

## Working in the browser

ON-FireLOS's browser lets ON-FireAI work on web pages from the inside, not as
screen pixels: a service in the browser that only apps signed like the system
can use (it checks the caller's signature). "Such auf willhaben ein Rad unter
200 Euro", "lies mir den Artikel vor", "mach den zweiten Tab zu".

- Tabs: list, bring to the front, close; open a page or a search in a visible
  tab, or for looking something up in an invisible one, while the user does
  something else (only reading there, never acting).
- Reading: title, address, the page's main text (reader mode where it works),
  and every control numbered - links, buttons, fields with their label and
  value, lists with their options, boxes - also those off screen. Where a
  button's name for assistive tools differs from what it shows, both are given
  ("Download the app (Installieren)"), so the model sees what the user sees.
- Acting: click (in a visible tab a real touch, so pages that need one work),
  type (as a person would, React pages see it), choose an option, tick, keys,
  scroll, find text, back, forward, reload; it waits until a new page has
  loaded. Videos: play, pause, seek.
- Before a click or an Enter that sends, buys, pays, deletes, downloads,
  installs, subscribes or signs in, it asks. Password fields are never read
  and never typed into (the user types them, then it goes on); card numbers
  and one-time codes are kept out like passwords.
- What a page says is data for the model, never an instruction; the log keeps
  only which command ran.
- Measured on my device: open and read a page in about 2s; search on
  Wikipedia and open the first hit, 13 steps, in 23s.
- Not seen: other sites' frames inside a page and shadow DOM.

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
  one of the four cores 38% busy with its two listeners (measured on my device;
  one listener was about 25%) and needs about 100MB of memory.
- It pauses only while ON-FireAI itself listens. Said while ON-FireAI speaks, it
  cuts the answer short and ON-FireAI listens (to the answer of its own question,
  if one is open).
- The model knows English sounds; English-sounding words of two or three
  syllables work best. A word of one's own is split into the model's pieces on
  the tablet.
- Two listeners hear the same sound, the second 130ms later: whether the model
  hears a word depends on where it falls in its processing steps, and what one
  cuts badly the other catches (offline with recorded words: 63 instead of 56
  of 75). One word heard by both counts once.
- Over the tablet's own music: the speaker is a few cm from the microphone, so
  music at half volume arrives louder than a voice across the room (measured
  on my device: music 600-1000, a voice 450, room 150; without help 7 of 40
  words heard). ON-FireAI takes what the tablet plays out of the microphone
  (echo cancellation, as smart speakers do):
  - ON-FireLOS hands it a copy of what the speaker plays: while it records the
    remote submix, media plays on the speaker and into the submix at once
    (audio policy patch in frameworks/av, `persist.audio.submix_tee`; false
    gives the old casting behaviour back).
  - The copy only runs while media plays (music, videos, games; not
    ON-FireAI's own voice) and up to 10s after. Starting it moves the playing
    sound once, a gap of about a tenth of a second.
  - The delay between copy and microphone is measured every 3s (it jumps by
    21ms now and then); Speex's echo canceller (BSD, `onfireai/jni/speex`)
    then takes the music out, about 9dB at medium volume (measured on my
    device). Offline with the same recordings that brought 34 instead of 7 of
    40 words at medium volume; at full volume the speaker distorts too much,
    and it stays hard.
- Always works, however loud: a long press on the home bar starts ON-FireAI,
  and the music goes down just the same.

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

## Its own voice

Settings > System > ON-FireAI > Own voice: a voice from a server of one's own -
any OpenAI-compatible `/v1/audio/speech` (Kokoro, Chatterbox, Piper, LocalAI,
OpenAI) or ElevenLabs. ON-FireAI speaks with it at once; "for the whole tablet"
makes it the voice of every app (Android's text-to-speech engine "ON-FireAI
voice"). When the server cannot be reached, the voice there was before speaks
instead, so nothing falls silent.

Google's voice on this tablet has no German offline voice (its download needs
Google's services); it speaks German with its network voice, so only online
(checked on my device 2026-11-09).

## Listening

- **On the tablet** (the default without a server): NVIDIA's German FastConformer
  (`stt_de_fastconformer_hybrid_large_pc`, CC-BY-4.0, 115M parameters, int8, about
  130MB in the app) through sherpa-onnx. Nothing leaves the tablet.
  - Measured on my device: a 4s sentence in about 1s, with punctuation and capitals; 4
    other models compared (Canary 180M, Parakeet v3, a multilingual FastConformer,
    Kroko streaming) were slower, worse in German or not freely licensed.
  - While you speak, each stretch up to a short pause (300ms, at least 1.5s of speech)
    is read at once and shows in the bubble; at the end only the last bit is left, so
    the text is there about 0.5s after you stop.
  - The model loads in about 4.5s when listening starts (on the wake word already) and
    is let go 5 minutes after the last use (about 200MB of memory meanwhile).
- **Whisper server**: the tablet records, notices the pause at the end
  and sends what was said to an OpenAI-compatible `/v1/audio/transcriptions`
  (faster-whisper-server, whisper.cpp with `--inference-path
  /v1/audio/transcriptions`, LocalAI, OpenAI). Works without any of Google's
  software.
- **Live transcript with a server**: while you speak, what was said so far goes to
  the server every 1.2s (one request at a time) and the words show in the bubble as
  they come; the final transcript replaces them. A server that takes more than
  2.5s for it (Whisper on a CPU) is left alone: then only the final one.
- **Android's recognition**: Google's speech services, where they are installed. On
  this tablet without Google's services their network recognition cannot connect
  and their offline pack cannot be downloaded (checked 2026-11-08: error 13,
  LANGUAGE_PACK_ERROR), so they only give the voice, not the ears.
- Settings > Listening: automatic (a Whisper server when one is set, else the
  tablet), on the tablet, Whisper server, Android's.

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

- Talking over an answer without the wake word (the wake word or the
  microphone button do it).
