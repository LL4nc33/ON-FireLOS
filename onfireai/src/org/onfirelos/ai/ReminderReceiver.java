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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The alarm manager's call at a reminder's time. */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final long id = intent.getLongExtra(Reminders.EXTRA_ID, -1);
        if (id >= 0) {
            // The alarm keeps the tablet awake only until here; saying it takes a while
            // (the voice may come from a server).
            context.getSystemService(android.os.PowerManager.class)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ONFireAI:reminder")
                    .acquire(30_000);
            Reminders.fire(context, id);
        }
    }
}
