/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.phone.vvm.omtp.fetch;

import android.accounts.Account;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.provider.VoicemailContract;
import android.provider.VoicemailContract.Voicemails;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.phone.PhoneUtils;
import com.android.phone.vvm.omtp.imap.ImapHelper;
import com.android.phone.vvm.omtp.sync.OmtpVvmSyncAccountManager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class FetchVoicemailReceiver extends BroadcastReceiver {
    private static final String TAG = "FetchVoicemailReceiver";

    final static String[] PROJECTION = new String[] {
        Voicemails.SOURCE_DATA,      // 0
        Voicemails.PHONE_ACCOUNT_ID, // 1
    };

    public static final int SOURCE_DATA = 0;
    public static final int PHONE_ACCOUNT_ID = 1;

    // Timeout used to call ConnectivityManager.requestNetwork
    private static final int NETWORK_REQUEST_TIMEOUT_MILLIS = 60 * 1000;

    private ContentResolver mContentResolver;
    private Uri mUri;
    private NetworkRequest mNetworkRequest;
    private OmtpVvmNetworkRequestCallback mNetworkCallback;
    private Context mContext;
    private Account mAccount;
    private String mUid;
    private ConnectivityManager mConnectivityManager;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (VoicemailContract.ACTION_FETCH_VOICEMAIL.equals(intent.getAction())) {
            mContext = context;
            mContentResolver = context.getContentResolver();
            mUri = intent.getData();

            if (mUri == null) {
                Log.w(TAG, VoicemailContract.ACTION_FETCH_VOICEMAIL + " intent sent with no data");
                return;
            }

            if (!context.getPackageName().equals(
                    mUri.getQueryParameter(VoicemailContract.PARAM_KEY_SOURCE_PACKAGE))) {
                // Ignore if the fetch request is for a voicemail not from this package.
                return;
            }

            Cursor cursor = mContentResolver.query(mUri, PROJECTION, null, null, null);
            if (cursor == null) {
                return;
            }
            try {
                if (cursor.moveToFirst()) {
                    mUid = cursor.getString(SOURCE_DATA);
                    String accountId = cursor.getString(PHONE_ACCOUNT_ID);
                    if (TextUtils.isEmpty(accountId)) {
                        TelephonyManager telephonyManager = (TelephonyManager)
                                context.getSystemService(Context.TELEPHONY_SERVICE);
                        accountId = telephonyManager.getSimSerialNumber();

                        if (TextUtils.isEmpty(accountId)) {
                            Log.e(TAG, "Account null and no default sim found.");
                            return;
                        }
                    }
                    mAccount = new Account(accountId,
                            OmtpVvmSyncAccountManager.ACCOUNT_TYPE);

                    if (!OmtpVvmSyncAccountManager.getInstance(context)
                            .isAccountRegistered(mAccount)) {
                        Log.w(TAG, "Account not registered - cannot retrieve message.");
                        return;
                    }

                    int subId = PhoneUtils.getSubIdForPhoneAccountHandle(
                            PhoneUtils.makePstnPhoneAccountHandle(accountId));

                    mNetworkRequest = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .setNetworkSpecifier(Integer.toString(subId))
                            .build();
                    mNetworkCallback = new OmtpVvmNetworkRequestCallback();
                    getConnectivityManager().requestNetwork(
                            mNetworkRequest, mNetworkCallback, NETWORK_REQUEST_TIMEOUT_MILLIS);
                }
            } finally {
                cursor.close();
            }
        }
    }

    private class OmtpVvmNetworkRequestCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(final Network network) {
            super.onAvailable(network);

            Executor executor = Executors.newCachedThreadPool();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    new ImapHelper(mContext, mAccount, network).fetchVoicemailPayload(
                            new VoicemailFetchedCallback(mContext, mUri), mUid);
                    releaseNetwork();
                }
            });
        }

        @Override
        public void onLost(Network network) {
            super.onLost(network);
            releaseNetwork();
        }

        @Override
        public void onUnavailable() {
            super.onUnavailable();
            releaseNetwork();
        }
    }

    private void releaseNetwork() {
        getConnectivityManager().unregisterNetworkCallback(mNetworkCallback);
    }

    private ConnectivityManager getConnectivityManager() {
        if (mConnectivityManager == null) {
            mConnectivityManager = (ConnectivityManager) mContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
        }
        return mConnectivityManager;
    }
}