/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.nophonespam;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.NotificationCompat;
import android.telephony.TelephonyManager;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.ITelephony;

import java.lang.reflect.Method;

import at.bitfire.nophonespam.model.BlockingModes;
import at.bitfire.nophonespam.model.DbHelper;
import at.bitfire.nophonespam.model.Number;

public class CallReceiver extends BroadcastReceiver {
    private static final String TAG = "NoPhoneSpam";

    private static final int NOTIFY_REJECTED = 0;
    private static boolean AlreadyOnCall = false;


    @Override
    public void onReceive(Context context, Intent intent) {

        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            String extraState = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            Log.d(TAG, "Received phone state change to the extra state " + extraState);

            /* swy: keep track of any calls that may already be happening while someone else tries to call us;
                    we don't want to interrupt actual, running calls by mistake */
            if (extraState.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                Log.d(TAG, "Setting AlreadyOnCall");
                AlreadyOnCall = true;
            }
            else if (extraState.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
                Log.d(TAG, "Clearing AlreadyOnCall");
                AlreadyOnCall = false;
            } else if (extraState.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                Log.d(TAG, "Handling ring");
                Settings settings = new Settings(context);
                if (settings.blockHiddenNumbers() || settings.getCallBlockingMode() != BlockingModes.ALLOW_ALL) {
                    String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

                    /* swy: we can receive two notifications; the first one doesn't
                            have EXTRA_INCOMING_NUMBER, so just skip it */
                    if (incomingNumber == null)
                        return;

                    Log.i(TAG, "Received call: " + incomingNumber);


                    if (TextUtils.isEmpty(incomingNumber)) {
                        // private number (no caller ID)
                        if (settings.blockHiddenNumbers())
                            rejectCall(context, null, context.getString(R.string.receiver_notify_private_number));

                    }
                    // block all calls
                    else if (settings.getCallBlockingMode() == BlockingModes.BLOCK_ALL) {
                        Log.i(TAG, "Block all Calls: " + incomingNumber);
                        Number number;
                        if(isNumberPresentInContacts(context, incomingNumber)){
                            String name = getCallerID(context, incomingNumber);
                            number = new Number(incomingNumber, name);
                        }
                        else{
                            number = new Number(incomingNumber);
                        }
                        rejectCall(context, number,context.getString(R.string.receiver_notify_no_call_allowed));
                    }
                    // allow calls only from contacts
                    else if (settings.getCallBlockingMode() == BlockingModes.ALLOW_CONTACTS) {
                        if (!isNumberPresentInContacts(context, incomingNumber)) {
                            Log.i(TAG, "Number not in contacts: " + incomingNumber);
                            rejectCall(context, new Number(incomingNumber), context.getString(R.string.receiver_notify_not_found_in_contacts));
                        }
                    }
                    else {
                        DbHelper dbHelper = new DbHelper(context);
                        try {
                            SQLiteDatabase db = dbHelper.getWritableDatabase();
                            Cursor c = db.query(Number._TABLE, null, "? LIKE " + Number.NUMBER, new String[]{incomingNumber}, null, null, null);
                            boolean inList = c.moveToNext();
                            // block calls from the numbers stored in list
                            if (inList && settings.getCallBlockingMode() == BlockingModes.BLOCK_LIST) {
                                Log.i(TAG, "Number was in list: " + incomingNumber);
                                ContentValues values = new ContentValues();
                                DatabaseUtils.cursorRowToContentValues(c, values);
                                Number number = Number.fromValues(values);

                                rejectCall(context, number, context.getString(R.string.receiver_notify_number_was_in_list));

                                values.clear();
                                values.put(Number.LAST_CALL, System.currentTimeMillis());
                                values.put(Number.TIMES_CALLED, number.timesCalled + 1);
                                db.update(Number._TABLE, values, Number.NUMBER + "=?", new String[]{number.number});

                                BlacklistObserver.notifyUpdated();

                            }
                            // allow calls only from numbers stored in list
                            else if (!inList && settings.getCallBlockingMode() == BlockingModes.ALLOW_ONLY_LIST_CALLS) {
                                Log.i(TAG, "Number was not in list: " + incomingNumber);

                                Number number;
                                if(isNumberPresentInContacts(context, incomingNumber)){
                                    String name = getCallerID(context, incomingNumber);
                                    number = new Number(incomingNumber, name);
                                }
                                else{
                                    number = new Number(incomingNumber);
                                }

                                rejectCall(context, number, context.getString(R.string.receiver_notify_number_was_not_in_list));
                                BlacklistObserver.notifyUpdated();
                            }
                            c.close();
                        } finally {
                            dbHelper.close();
                        }
                    }


                }
            }
            else {
                Log.d(TAG, "Did not match " + extraState + " with " + TelephonyManager.EXTRA_STATE_RINGING + ", " + TelephonyManager.EXTRA_STATE_OFFHOOK + ", or " + TelephonyManager.EXTRA_STATE_IDLE);
            }
        }
    }

    @SuppressLint("MissingPermission")
    protected void rejectCall(@NonNull Context context, Number number, String reason) {

        if (!AlreadyOnCall) {
            boolean failed = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);

                try {
                    telecomManager.endCall();
                    Log.d(TAG, "Invoked 'endCall' on TelecomManager");
                } catch (Exception e) {
                    Log.e(TAG, "Couldn't end call with TelecomManager", e);
                    failed = true;
                }
            } else {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                try {
                    Method m = tm.getClass().getDeclaredMethod("getITelephony");
                    m.setAccessible(true);

                    ITelephony telephony = (ITelephony) m.invoke(tm);

                    telephony.endCall();
                } catch (Exception e) {
                    Log.e(TAG, "Couldn't end call with TelephonyManager", e);
                    failed = true;
                }
            }
            if (failed) {
                Toast.makeText(context, context.getString(R.string.call_blocking_unsupported), Toast.LENGTH_LONG).show();
            }
        }

        Settings settings = new Settings(context);
        if (settings.showNotifications()) {


            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationChannel channel = new NotificationChannel(
                        "default", context.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription(reason);
                notificationManager.createNotificationChannel(channel);
            }

            Notification notify = new NotificationCompat.Builder(context)
                    .setSmallIcon(R.drawable.ic_launcher_small)
                    .setContentTitle(reason)
                    .setContentText(number != null ? (number.name != null ? number.name : number.number) : context.getString(R.string.receiver_notify_private_number))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setShowWhen(true)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, BlacklistActivity.class), PendingIntent.FLAG_UPDATE_CURRENT))
                    .addPerson("tel:" + number)
                    .setGroup("rejected")
                    .setChannelId("default")
                    .setGroupSummary(true) /* swy: fix notifications not appearing on kitkat: https://stackoverflow.com/a/37070917/674685 */
                    .build();

            String tag = number != null ? number.number : "private";
            NotificationManagerCompat.from(context).notify(tag, NOTIFY_REJECTED, notify);


        }

    }

    @SuppressLint("MissingPermission")
    private boolean isNumberPresentInContacts(Context context, String incomingNumber) {
        return getCallerID(context, incomingNumber) != null;
    }

    private String getCallerID(Context context, String incomingNumber){
        Cursor cursor = null;
        String name = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(incomingNumber));
            cursor = resolver.query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME));
                Log.i(TAG, "Received call from contact: " + name);

            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return name;
    }

}
