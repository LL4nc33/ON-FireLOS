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

/** A way of listening: Android's speech recognition or a Whisper server. */
interface Listening {

    interface Callback {
        void onReadyForSpeech();

        void onPartial(String text);

        void onFinal(String text);

        /** SpeechRecognizer.ERROR_*. */
        void onError(int error);

        void onLevel(float rmsDb);
    }

    /** False when this way cannot listen at all. Main thread. */
    boolean start(boolean followUp);

    /** Ends listening and takes what was said. */
    void stop();

    /** Ends listening and drops it. */
    void cancel();

    void destroy();

    boolean active();
}
