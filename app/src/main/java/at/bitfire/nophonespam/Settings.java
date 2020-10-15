/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.nophonespam;

import android.content.Context;
import android.content.SharedPreferences;

import at.bitfire.nophonespam.model.BlockingModes;

public class Settings {

    private static final String
            PREF_BLOCK_HIDDEN_NUMBERS = "blockHiddenNumbers",
            PREF_NOTIFICATIONS = "notifications",
            PREF_BLOCKING_MODE = "callBlockingMode";



    private final SharedPreferences pref;


    public Settings(Context context) {
        pref = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
    }


    public boolean blockHiddenNumbers() {
        return pref.getBoolean(PREF_BLOCK_HIDDEN_NUMBERS, false);
    }

    public void blockHiddenNumbers(boolean block) {
        pref.edit()
                .putBoolean(PREF_BLOCK_HIDDEN_NUMBERS, block)
                .apply();
    }


    public boolean showNotifications() {
        return pref.getBoolean(PREF_NOTIFICATIONS, true);
    }

    public void showNotifications(boolean show) {
        pref.edit()
                .putBoolean(PREF_NOTIFICATIONS, show)
                .apply();
    }


    public int getCallBlockingMode() {
        return pref.getInt(PREF_BLOCKING_MODE, BlockingModes.BLOCK_LIST);
    }

    public void setCallBlockingMode(int value) {
        pref.edit()
                .putInt(PREF_BLOCKING_MODE, value)
                .apply();
    }



}
