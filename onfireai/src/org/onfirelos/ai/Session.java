/*
 * Copyright (C) 2026 ON-FireLOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onfirelos.ai;

import android.content.Context;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The assistant's state, apart from any window: what was said and answered, what it is doing,
 * the voice and the microphone. The sheet only shows it, so a change of design (which rebuilds
 * every window) or an app a tool opened does not cut an answer short.
 */
final class Session implements Agent.Callback, Listening.Callback, Speaker.Callback {

    enum State { IDLE, LISTENING, THINKING, ANSWERING }

    static final int USER = 0;
    static final int ANSWER = 1;
    static final int NOTE = 2;
    static final int SETUP = 3;
    static final int CONFIRM = 4;

    static final class Item {
        final int type;
        String text;

        Item(int type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    interface Ui {
        /** Something changed; whole tells that items came or went, not just the last text. */
        void onSessionChanged(boolean whole);

        /** The sheet should close (the user is done). */
        void onSessionClosed();
    }

    private static Session sInstance;

    static synchronized Session get(Context context) {
        if (sInstance == null) {
            sInstance = new Session(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Context mContext;
    private final Agent mAgent;
    private final Speaker mSpeaker;
    private final SpeechInput mGoogle;
    private final WhisperInput mWhisper;
    private Listening mListening;

    final List<Item> items = new ArrayList<>();
    final List<String> chips = new ArrayList<>();
    State state = State.IDLE;
    /** A line under the conversation: listening, thinking, a problem. */
    String status = "";

    private Ui mUi;
    private boolean mVisible;
    private boolean mVoiceTurn;
    private boolean mFollowUp;
    private Item mLive;
    private Item mAnswer;
    private final StringBuilder mAnswerText = new StringBuilder();
    private java.util.function.Consumer<Boolean> mPendingConfirm;
    private Item mConfirmItem;
    /** When something last happened, so a conversation long gone starts afresh on screen. */
    private long mLastChange;
    /** The sheet was last shown at the lock screen. */
    private boolean mShownLocked;
    private static final long QUIET = 30 * 60 * 1000;

    private Session(Context context) {
        mContext = context;
        mAgent = new Agent(context, this);
        mSpeaker = new Speaker(context, this);
        mGoogle = new SpeechInput(context, this);
        mWhisper = new WhisperInput(context, this);
        mListening = mGoogle;
    }

    // ---------------------------------------------------------------- the sheet

    void attach(Ui ui) {
        mUi = ui;
        // At the lock screen the sheet does not show what was said while unlocked.
        final boolean locked = Tools.locked(mContext);
        if (locked && !mShownLocked && !busy()) {
            items.clear();
            chips.clear();
            status = "";
        }
        mShownLocked = locked;
        // A conversation that went quiet long ago starts afresh on screen too.
        if (state == State.IDLE && !mSpeaker.speaking()
                && System.currentTimeMillis() - mLastChange > QUIET) {
            items.clear();
            chips.clear();
            status = "";
        }
    }

    void detach(Ui ui) {
        if (mUi == ui) {
            mUi = null;
        }
    }

    /** The sheet is on screen (or not: another app came up, or it is being rebuilt). */
    void setVisible(boolean visible) {
        mVisible = visible;
        if (visible && ScreenAgent.get() != null) {
            ScreenAgent.get().hidePill();
        }
        if (!visible && state == State.LISTENING) {
            mListening.cancel();
            dropLive();
            setState(State.IDLE);
        }
    }

    boolean busy() {
        // A question of the agent still open counts too: its work goes on after the answer.
        return state != State.IDLE || mSpeaker.speaking() || mPendingConfirm != null;
    }

    /** The pill outside the sheet, when the screen agent can show one. */
    private void pill(CharSequence text) {
        final ScreenAgent agent = ScreenAgent.get();
        if (agent != null && !mVisible && text != null && text.length() > 0) {
            agent.showPill(text);
        }
    }

    @Override
    public void onStep(String tool, String detail) {
        final String text;
        switch (tool) {
            case "weather": text = mContext.getString(R.string.step_weather); break;
            case "web_search": text = mContext.getString(R.string.step_search); break;
            case "youtube_search": text = mContext.getString(R.string.step_videos); break;
            case "recall": text = mContext.getString(R.string.step_recall); break;
            case "notifications": text = mContext.getString(R.string.step_notifications); break;
            case "screen_read": text = mContext.getString(R.string.step_read); break;
            case "screen_tap": text = mContext.getString(R.string.step_tap, detail); break;
            case "screen_type": text = mContext.getString(R.string.step_type, detail); break;
            case "screen_scroll": text = mContext.getString(R.string.step_scroll); break;
            case "screen_key": text = mContext.getString(R.string.step_key, detail); break;
            case "open_app": text = mContext.getString(R.string.step_open, detail); break;
            default: text = Agent.readable(tool) + "…";
        }
        if (mVisible) {
            // In the sheet the line under the conversation says what goes on.
            if (state == State.THINKING) {
                status = text;
                changed(false);
            }
            return;
        }
        pill(text);
    }

    /** Said aloud without a question, after a soft chime (a reminder that is due). */
    void announce(String text) {
        Chime.play();
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> mSpeaker.say(text), 550);
    }

    /** The user closed the sheet: everything stops. */
    void close() {
        final ScreenAgent agent = ScreenAgent.get();
        if (agent != null) {
            agent.hidePill();
        }
        answerConfirm(false);
        mAgent.cancel();
        mListening.cancel();
        mSpeaker.stop();
        dropLive();
        setState(State.IDLE);
    }

    /** The cross on the pill: everything stops and the sheet behind the app is done. */
    void stopFromPill() {
        close();
        WakeWord.setPaused(false);
        if (mUi != null) {
            mUi.onSessionClosed();
        }
    }

    /** The way through a conversation, for adb logcat with log.tag.ONFireAI set to DEBUG. */
    private static void trace(String what) {
        if (Log.isLoggable("ONFireAI", Log.DEBUG)) {
            Log.d("ONFireAI", "session: " + what);
        }
    }

    private void changed(boolean whole) {
        mLastChange = System.currentTimeMillis();
        if (mUi != null) {
            mUi.onSessionChanged(whole);
        }
    }

    private void setState(State s) {
        state = s;
        switch (s) {
            case LISTENING:
                status = mContext.getString(R.string.listening);
                break;
            case THINKING:
                status = mContext.getString(R.string.thinking);
                break;
            default:
                status = "";
                break;
        }
        changed(false);
    }

    private void setStatus(int text) {
        status = text == 0 ? "" : mContext.getString(text);
        changed(false);
    }

    // ---------------------------------------------------------------- actions

    void newConversation() {
        close();
        Agent.newConversation();
        items.clear();
        chips.clear();
        status = "";
        changed(true);
    }

    void micPressed() {
        switch (state) {
            case LISTENING:
                mListening.stop();
                break;
            case THINKING:
            case ANSWERING:
                if (mPendingConfirm != null) {
                    // Waiting for a yes or no: the microphone is for the answer.
                    mSpeaker.stop();
                    startListening(true);
                    break;
                }
                mAgent.cancel();
                mSpeaker.stop();
                setState(State.IDLE);
                break;
            default:
                mSpeaker.stop();
                startListening(false);
        }
    }

    /** The user went to type: the microphone closes. */
    void typing() {
        if (state == State.LISTENING) {
            mListening.cancel();
            dropLive();
            setState(State.IDLE);
        }
    }

    private boolean useWhisper() {
        final String mode = Prefs.get(mContext).getString(Prefs.STT_MODE, "auto");
        return "server".equals(mode) || ("auto".equals(mode) && WhisperInput.configured(mContext));
    }

    /** The caller has made sure of the microphone permission. */
    void startListening(boolean followUp) {
        trace("listen followUp=" + followUp + " visible=" + mVisible);
        if (!mVisible) {
            return;
        }
        // Listening for the answer to a question of the agent must not end its work.
        if (mPendingConfirm == null) {
            mAgent.cancel();
        }
        mSpeaker.stop();
        mFollowUp = followUp;
        mListening = useWhisper() ? mWhisper : mGoogle;
        if (!mListening.start(followUp)) {
            setup(R.string.recognizer_missing);
            setState(State.IDLE);
            return;
        }
        if (!followUp) {
            chips.clear();
        }
        setState(State.LISTENING);
    }

    void submitTyped(String text) {
        mListening.cancel();
        dropLive();
        if (mPendingConfirm != null) {
            items.add(new Item(USER, text));
            answerConfirm(isYes(text));
            return;
        }
        items.add(new Item(USER, text));
        ask(text, false);
    }

    private void ask(String text, boolean voice) {
        mVoiceTurn = voice;
        chips.clear();
        mAnswerText.setLength(0);
        mAnswer = null;
        // A new question replaces one still being answered.
        mSpeaker.stop();
        mSpeaker.begin();
        status = mContext.getString(R.string.thinking);
        state = State.THINKING;
        changed(true);
        mAgent.ask(text);
    }

    private void setup(int message) {
        items.add(new Item(SETUP, mContext.getString(message)));
        changed(true);
    }

    private void dropLive() {
        if (mLive != null) {
            items.remove(mLive);
            mLive = null;
            changed(true);
        }
    }

    // ---------------------------------------------------------------- listening

    @Override
    public void onReadyForSpeech() {
    }

    @Override
    public void onPartial(String text) {
        if ("…".equals(text)) {
            // The recording is on its way to the Whisper server.
            status = mContext.getString(R.string.transcribing);
        }
        if (mLive == null) {
            mLive = new Item(USER, text);
            items.add(mLive);
            changed(true);
        } else {
            mLive.text = text;
            changed(false);
        }
    }

    @Override
    public void onFinal(String text) {
        final Item live = mLive;
        mLive = null;
        if (text.isEmpty()) {
            if (live != null) {
                items.remove(live);
            }
            status = mFollowUp ? "" : mContext.getString(R.string.not_understood);
            state = State.IDLE;
            changed(true);
            return;
        }
        if (live != null) {
            live.text = text;
        } else {
            items.add(new Item(USER, text));
        }
        if (mPendingConfirm != null) {
            answerConfirm(isYes(text));
            return;
        }
        ask(text, true);
    }

    @Override
    public void onError(int error) {
        trace("listening error " + error);
        dropLive();
        state = State.IDLE;
        int message = 0;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = mFollowUp ? 0 : R.string.not_understood;
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = R.string.recognizer_no_mic;
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = R.string.recognizer_busy;
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                message = R.string.mic_busy;
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case 13: // ERROR_LANGUAGE_UNAVAILABLE (Android 12): no offline pack
                if (mListening == mGoogle) {
                    // Google's recognition needs Google's services, or its offline pack,
                    // which cannot be downloaded without them.
                    status = "";
                    setup(R.string.stt_google_unavailable);
                    return;
                }
                message = R.string.stt_server_error;
                break;
            default:
                break;
        }
        setStatus(message);
    }

    @Override
    public void onLevel(float rmsDb) {
        if (mUi instanceof AssistActivity) {
            ((AssistActivity) mUi).showLevel(rmsDb);
        }
    }

    // ---------------------------------------------------------------- answering

    @Override
    public void onText(String piece) {
        if (mAnswer == null) {
            mAnswer = new Item(ANSWER, "");
            items.add(mAnswer);
            mAnswerText.append(piece);
            mAnswer.text = Agent.displayable(mAnswerText.toString()).trim();
            state = State.ANSWERING;
            status = "";
            changed(true);
            return;
        }
        mAnswerText.append(piece);
        mAnswer.text = Agent.displayable(mAnswerText.toString()).trim();
        if (state == State.THINKING) {
            state = State.ANSWERING;
            status = "";
        }
        changed(false);
    }

    @Override
    public void onSentence(String sentence) {
        if (Prefs.flag(mContext, Prefs.SPEAK)) {
            mSpeaker.say(sentence);
        }
    }

    @Override
    public void onChip(String chip) {
        chips.add(chip);
        changed(false);
    }

    @Override
    public void onToolRound() {
        if (mAnswerText.length() > 0 && !mAnswerText.toString().endsWith("\n")) {
            mAnswerText.append('\n');
        }
    }

    @Override
    public void onDone(String answer) {
        setState(State.IDLE);
        mSpeaker.end();
        if (!mVisible && !answer.isEmpty()) {
            pill(answer);
            final ScreenAgent agent = ScreenAgent.get();
            if (agent != null) {
                // Long enough to read; longer answers stay longer.
                agent.hidePillLater(Math.min(20000, 5000 + answer.length() * 60L));
            }
        }
    }

    @Override
    public void onError(String message, boolean needsSetup) {
        state = State.IDLE;
        status = "";
        items.add(new Item(needsSetup ? SETUP : NOTE, message));
        changed(true);
        if (mVoiceTurn && Prefs.flag(mContext, Prefs.SPEAK)) {
            mSpeaker.say(message);
        }
        mVoiceTurn = false;
        mSpeaker.end();
    }

    // ---------------------------------------------------------------- asking back

    @Override
    public void onConfirm(String question, java.util.function.Consumer<Boolean> answer) {
        mPendingConfirm = answer;
        mConfirmItem = new Item(CONFIRM, question);
        items.add(mConfirmItem);
        status = "";
        changed(true);
        // The sheet is away while the work goes on in an app: the pill asks.
        final ScreenAgent agent = ScreenAgent.get();
        if (!mVisible && agent != null) {
            agent.showConfirm(question, this::answerConfirm);
        }
        if (Prefs.flag(mContext, Prefs.SPEAK)) {
            mSpeaker.say(question);
            // Reported as spoken when done, so the microphone opens for the answer.
            mSpeaker.end();
        }
    }

    /** From the pills, or from what was said or typed. */
    void answerConfirm(boolean yes) {
        final java.util.function.Consumer<Boolean> pending = mPendingConfirm;
        mPendingConfirm = null;
        if (mConfirmItem != null) {
            settleConfirm(mContext.getString(yes ? R.string.yes : R.string.no));
            state = State.THINKING;
            status = mContext.getString(R.string.thinking);
            changed(true);
        }
        if (pending != null) {
            pending.accept(yes);
        }
    }

    /** Answered or gone: the question is shown as a plain line from now on. */
    private void settleConfirm(String suffix) {
        final String text = mConfirmItem.text + (suffix.isEmpty() ? "" : "  " + suffix);
        final int at = items.indexOf(mConfirmItem);
        if (at >= 0) {
            items.set(at, new Item(NOTE, text));
        }
        mConfirmItem = null;
    }

    /** The agent stopped waiting (no answer within a minute, or it was cancelled). */
    @Override
    public void onConfirmEnded(java.util.function.Consumer<Boolean> answer) {
        if (mPendingConfirm == null || mPendingConfirm != answer) {
            return;
        }
        mPendingConfirm = null;
        final ScreenAgent agent = ScreenAgent.get();
        if (agent != null) {
            agent.clearConfirm();
        }
        if (mConfirmItem != null) {
            settleConfirm("");
            changed(true);
        }
    }

    boolean confirming() {
        return mPendingConfirm != null;
    }

    /**
     * A yes, said or typed: a short answer that starts with one. Anything with a no in it is
     * not one ("bitte nicht", "ja, kein Problem" is too unclear for a risky step).
     */
    static boolean isYes(String text) {
        final String t = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L} ]", " ")
                .replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.split(" ").length > 4) {
            return false;
        }
        if (t.matches(".*\\b(nicht|nein|nee|nö|kein\\w*|niemals|nie|ohne|später|warte|halt"
                + "|abbrechen|stop|stopp|no|not|nope|never|don t|dont|cancel|wait)\\b.*")) {
            return false;
        }
        return t.matches("(ja|jo|jep|jawohl|klar|ok|okay|mach|gerne|gern|bitte|passt|genau"
                + "|los|yes|yeah|yep|sure|do it|go ahead)\\b.*");
    }

    /** The answer has been said: the microphone opens for a follow-up, like a conversation. */
    @Override
    public void onSpoken() {
        trace("spoken visible=" + mVisible + " state=" + state + " confirm="
                + (mPendingConfirm != null) + " voice=" + mVoiceTurn);
        if (!mVisible) {
            // Said while another app is in front: when the work is done too, the sheet has
            // done its part.
            if (state == State.IDLE && mPendingConfirm == null) {
                WakeWord.setPaused(false);
                if (mUi != null) {
                    mUi.onSessionClosed();
                }
            }
            return;
        }
        if ((mVoiceTurn || mPendingConfirm != null) && (state == State.IDLE
                || mPendingConfirm != null) && Prefs.flag(mContext, Prefs.FOLLOW_UP)) {
            if (mUi instanceof AssistActivity && ((AssistActivity) mUi).haveMic()) {
                startListening(true);
            }
        }
    }
}
