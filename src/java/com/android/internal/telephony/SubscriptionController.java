/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
* Copyright (C) 2011-2014 MediaTek Inc.
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

package com.android.internal.telephony;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.Rlog;
import android.util.Log;
import android.net.Uri;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.BaseColumns;
import android.provider.Settings;
import android.content.ContentResolver;
import android.content.ContentValues;

import com.android.internal.telephony.ISub;
import com.android.internal.telephony.uicc.SpnOverride;

import android.telephony.SubscriptionManager;
import android.telephony.SubInfoRecord;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.Time;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * SubscriptionController to provide an inter-process communication to
 * access Sms in Icc.
 *
 * Any setters which take subId, slotId or phoneId as a parameter will throw an exception if the
 * parameter equals the corresponding INVALID_XXX_ID or DEFAULT_XXX_ID.
 *
 * All getters will lookup the corresponding default if the parameter is DEFAULT_XXX_ID. Ie calling
 * getPhoneId(DEFAULT_SUB_ID) will return the same as getPhoneId(getDefaultSubId()).
 *
 * Finally, any getters which perform the mapping between subscriptions, slots and phones will
 * return the corresponding INVALID_XXX_ID if the parameter is INVALID_XXX_ID. All other getters
 * will fail and return the appropriate error value. Ie calling getSlotId(INVALID_SUB_ID) will
 * return INVALID_SLOT_ID and calling getSubInfoForSubscriber(INVALID_SUB_ID) will return null.
 *
 */
public class SubscriptionController extends ISub.Stub {
    static final String LOG_TAG = "SubController";
    static final boolean DBG = true;
    static final boolean VDBG = false;
    static final int MAX_LOCAL_LOG_LINES = 500; // TODO: Reduce to 100 when 17678050 is fixed
    private ScLocalLog mLocalLog = new ScLocalLog(MAX_LOCAL_LOG_LINES);

    /**
     * Copied from android.util.LocalLog with flush() adding flush and line number
     * TODO: Update LocalLog
     */
    static class ScLocalLog {

        private LinkedList<String> mLog;
        private int mMaxLines;
        private Time mNow;

        public ScLocalLog(int maxLines) {
            mLog = new LinkedList<String>();
            mMaxLines = maxLines;
            mNow = new Time();
        }

        public synchronized void log(String msg) {
            if (mMaxLines > 0) {
                int pid = android.os.Process.myPid();
                int tid = android.os.Process.myTid();
                mNow.setToNow();
                mLog.add(mNow.format("%m-%d %H:%M:%S") + " pid=" + pid + " tid=" + tid + " " + msg);
                while (mLog.size() > mMaxLines) mLog.remove();
            }
        }

        public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            final int LOOPS_PER_FLUSH = 10; // Flush every N loops.
            Iterator<String> itr = mLog.listIterator(0);
            int i = 0;
            while (itr.hasNext()) {
                pw.println(Integer.toString(i++) + ": " + itr.next());
                // Flush periodically so we don't drop lines
                if ((i % LOOPS_PER_FLUSH) == 0) pw.flush();
            }
        }
    }

    protected final Object mLock = new Object();
    protected boolean mSuccess;
    private List<SubInfoRecord> mActiveList;

    /** The singleton instance. */
    private static SubscriptionController sInstance = null;
    protected static PhoneProxy[] sProxyPhones;
    protected Context mContext;
    protected TelephonyManager mTelephonyManager;
    protected CallManager mCM;

    private static final int RES_TYPE_BACKGROUND_DARK = 0;
    private static final int RES_TYPE_BACKGROUND_LIGHT = 1;

    // private static final int[] sSimBackgroundDarkRes = setSimResource(RES_TYPE_BACKGROUND_DARK);
    // private static final int[] sSimBackgroundLightRes = setSimResource(RES_TYPE_BACKGROUND_LIGHT);

    //FIXME this does not allow for multiple subs in a slot
    private static ConcurrentHashMap<Integer, Integer> sSimInfo =
            new ConcurrentHashMap<Integer, Integer>();
    private static int mDefaultVoiceSubId = SubscriptionManager.INVALID_SUB_ID;
    private static int mDefaultPhoneId = SubscriptionManager.DEFAULT_PHONE_ID;

    private static final int EVENT_WRITE_MSISDN_DONE = 1;
    private boolean mIsOP01 = false;

    protected Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_WRITE_MSISDN_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        logd("EVENT_WRITE_MSISDN_DONE, mSuccess = "+mSuccess);
                        mLock.notifyAll();
                    }
                    break;
            }
        }
    };


    public static SubscriptionController init(Phone phone) {
        synchronized (SubscriptionController.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionController(phone);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static SubscriptionController init(Context c, CommandsInterface[] ci) {
        synchronized (SubscriptionController.class) {
            if (sInstance == null) {
                sInstance = new SubscriptionController(c);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    public static SubscriptionController getInstance() {
        if (sInstance == null)
        {
           Log.wtf(LOG_TAG, "getInstance null");
        }

        return sInstance;
    }

    private SubscriptionController(Context c) {
        mContext = c;
        mCM = CallManager.getInstance();
        mTelephonyManager = TelephonyManager.from(mContext);
        String operator = SystemProperties.get("ro.operator.optr", "OM");
        if (operator.equals("OP01")) {
            mIsOP01 = true;
        }

        if(ServiceManager.getService("isub") == null) {
                ServiceManager.addService("isub", this);
        }

        if (mActiveList != null) {
            mActiveList.clear();
        }

        logd("[SubscriptionController] init by Context");
    }

    private boolean isSubInfoReady() {
        return sSimInfo.size() > 0;
    }

    private SubscriptionController(Phone phone) {
        mContext = phone.getContext();
        mCM = CallManager.getInstance();

        if(ServiceManager.getService("isub") == null) {
                ServiceManager.addService("isub", this);
        }

        logdl("[SubscriptionController] init by Phone");
    }

    /**
     * Make sure the caller has the READ_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceSubscriptionPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE,
                "Requires READ_PHONE_STATE");
    }

    /**
     * Broadcast when subinfo settings has chanded
     * @SubId The unique SubInfoRecord index in database
     * @param columnName The column that is updated
     * @param intContent The updated integer value
     * @param stringContent The updated string value
     */
     private void broadcastSimInfoContentChanged(int subId,
            String columnName, int intContent, String stringContent) {

        Intent intent = new Intent(TelephonyIntents.ACTION_SUBINFO_CONTENT_CHANGE);
        intent.putExtra(BaseColumns._ID, subId);
        intent.putExtra(TelephonyIntents.EXTRA_COLUMN_NAME, columnName);
        intent.putExtra(TelephonyIntents.EXTRA_INT_CONTENT, intContent);
        intent.putExtra(TelephonyIntents.EXTRA_STRING_CONTENT, stringContent);
        if (intContent != SubscriptionManager.DEFAULT_INT_VALUE) {
            logd("[broadcastSimInfoContentChanged] subId" + subId
                    + " changed, " + columnName + " -> " +  intContent);
        } else {
            logd("[broadcastSimInfoContentChanged] subId" + subId
                    + " changed, " + columnName + " -> " +  stringContent);
        }

        mContext.sendBroadcast(intent);
    }


    /**
     * New SubInfoRecord instance and fill in detail info
     * @param cursor
     * @return the query result of desired SubInfoRecord
     */
    private SubInfoRecord getSubInfoRecordMTK(Cursor cursor) {
            SubInfoRecord info = new SubInfoRecord();
            info.subId = cursor.getInt(cursor.getColumnIndexOrThrow(BaseColumns._ID));
            info.iccId = cursor.getString(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.ICC_ID));
            info.slotId = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.SIM_ID));
            info.displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.DISPLAY_NAME));
            info.nameSource = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.NAME_SOURCE));
            // CMCC spec, the color is dedicated with slot.
            if (mIsOP01) {
                info.color = info.slotId;
            } else {
                info.color = cursor.getInt(cursor.getColumnIndexOrThrow(
                        SubscriptionManager.COLOR));
            }
            info.number = cursor.getString(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.NUMBER));
            info.displayNumberFormat = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.DISPLAY_NUMBER_FORMAT));
            info.dataRoaming = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.DATA_ROAMING));

            int size = 0;  // sSimBackgroundDarkRes.length;
            if (info.color >= 0 && info.color < size) {
				/*
                info.simIconRes[RES_TYPE_BACKGROUND_DARK] = sSimBackgroundDarkRes[info.color];
                info.simIconRes[RES_TYPE_BACKGROUND_LIGHT] = sSimBackgroundLightRes[info.color];
				*/
            }
            info.mcc = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.MCC));
            info.mnc = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.MNC));

            logd("[getSubInfoRecord] SubId:" + info.subId + " iccid:" + info.iccId + " slotId:" +
                    info.slotId + " displayName:" + info.displayName + " color:" + info.color +
                    " mcc/mnc:" + info.mcc + "/" + info.mnc);

            return info;
    }

    /**
     * New SubInfoRecord instance and fill in detail info
     * @param cursor
     * @return the query result of desired SubInfoRecord
     */
    private SubscriptionInfo getSubInfoRecord(Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID));
        String iccId = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.ICC_ID));
        int simSlotIndex = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.SIM_SLOT_INDEX));
        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.DISPLAY_NAME));
        String carrierName = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.CARRIER_NAME));
        int nameSource = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.NAME_SOURCE));
        int iconTint = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.COLOR));
        String number = cursor.getString(cursor.getColumnIndexOrThrow(
                SubscriptionManager.NUMBER));
        int dataRoaming = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.DATA_ROAMING));
        // Get the blank bitmap for this SubInfoRecord
        Bitmap iconBitmap = BitmapFactory.decodeResource(mContext.getResources(),
                com.android.internal.R.drawable.ic_sim_card_multi_24px_clr);
        int mcc = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.MCC));
        int mnc = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.MNC));
        // FIXME: consider stick this into database too
        String countryIso = getSubscriptionCountryIso(id);
        int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.SUB_STATE));
        int nwMode = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.NETWORK_MODE));
        int userNwMode = cursor.getInt(cursor.getColumnIndexOrThrow(
                SubscriptionManager.USER_NETWORK_MODE));

        if (DBG) {
            logd("[getSubInfoRecord] id:" + id + " iccid:" + iccId + " simSlotIndex:" + simSlotIndex
                + " displayName:" + displayName + " nameSource:" + nameSource
                + " iconTint:" + iconTint + " dataRoaming:" + dataRoaming
                + " mcc:" + mcc + " mnc:" + mnc + " countIso:" + countryIso +
                " status:" + status + " nwMode:" + nwMode + " userNwMode:" + userNwMode);
        }

        String line1Number = mTelephonyManager.getLine1NumberForSubscriber(id);
        if (!TextUtils.isEmpty(line1Number) && !line1Number.equals(number)) {
            logd("Line1Number is different: " + line1Number);
            number = line1Number;
        }
        return new SubscriptionInfo(id, iccId, simSlotIndex, displayName, carrierName,
                nameSource, iconTint, number, dataRoaming, iconBitmap, mcc, mnc, countryIso,
                status, nwMode, userNwMode);
    }

    /**
     * Get ISO country code for the subscription's provider
     *
     * @param subId The subscription ID
     * @return The ISO country code for the subscription's provider
     */
    private String getSubscriptionCountryIso(int subId) {
        final int phoneId = getPhoneId(subId);
        if (phoneId < 0) {
            return "";
        }
        // FIXME: have a better way to get country code instead of reading from system property
        return TelephonyManager.getTelephonyProperty(
                phoneId, TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY, "");
    }

    /**
     * Query SubInfoRecord(s) from subinfo database
     * @param selection A filter declaring which rows to return
     * @param queryKey query key content
     * @return Array list of queried result from database
     */
     private List<SubInfoRecord> getSubInfoMTK(String selection, Object queryKey) {
        logd("selection:" + selection + " " + queryKey);
        String[] selectionArgs = null;
        if (queryKey != null) {
            selectionArgs = new String[] {queryKey.toString()};
        }
        ArrayList<SubInfoRecord> subList = null;
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, selection, selectionArgs, null);
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubInfoRecord subInfo = getSubInfoRecordMTK(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubInfoRecord>();
                        }
                        subList.add(subInfo);
                }
                }
            } else {
                logd("Query fail");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return subList;
    }

    /**
     * Query SubInfoRecord(s) from subinfo database
     * @param context Context provided by caller
     * @param selection A filter declaring which rows to return
     * @param queryKey query key content
     * @return Array list of queried result from database
     */
     private List<SubscriptionInfo> getSubInfo(String selection, Object queryKey) {
        if (DBG) logd("selection:" + selection + " " + queryKey);
        String[] selectionArgs = null;
        if (queryKey != null) {
            selectionArgs = new String[] {queryKey.toString()};
        }
        ArrayList<SubscriptionInfo> subList = null;
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, selection, selectionArgs, SubscriptionManager.SIM_SLOT_INDEX);
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubscriptionInfo subInfo = getSubInfoRecord(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubscriptionInfo>();
                        }
                        subList.add(subInfo);
                }
                }
            } else {
                if (DBG) logd("Query fail");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return subList;
    }


    /**
     * Get the SubInfoRecord according to an index
     * @param subId The unique SubInfoRecord index in database
     * @return SubInfoRecord, maybe null
     */
    @Override
    public SubInfoRecord getSubInfoForSubscriber(int subId) {
        logd("[getSubInfoForSubscriberx]+ subId:" + subId);
        enforceSubscriptionPermission();
        SubInfoRecord result = null;

        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            subId = getDefaultSubId();
        }
        if (!SubscriptionManager.isValidSubId(subId)) {
            logd("[getSubInfoForSubscriberx]- invalid subId or not ready");
            return null;
        }
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, BaseColumns._ID + "=?", new String[] {Integer.toString(subId)}, null);
        try {
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    logd("[getSubInfoForSubscriberx]- Info detail:");
                    result = getSubInfoRecordMTK(cursor);
                    //When sub manager is not ready, should change slot id back to not inserted.
                    if (!isSubInfoReady()) {
                        logd("[getSubInfoForSubscriberx] !isSubInfoReady(), change slot Id");
                        result.slotId = SubscriptionManager.SIM_NOT_INSERTED;
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        logd("[getSubInfoForSubscriber]- null info return");

        return result;
    }

    /**
     * Get the SubInfoRecord according to an IccId
     * @param iccId the IccId of SIM card
     * @return SubInfoRecord, maybe null
     */
    @Override
    public List<SubInfoRecord> getSubInfoUsingIccId(String iccId) {
        logd("[getSubInfoUsingIccId]+ iccId:" + iccId);
        enforceSubscriptionPermission();

        if (iccId == null || !isSubInfoReady()) {
            logd("[getSubInfoUsingIccId]- null iccid or not ready");
            return null;
        }
        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, SubscriptionManager.ICC_ID + "=?", new String[] {iccId}, null);
        ArrayList<SubInfoRecord> subList = null;
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubInfoRecord subInfo = getSubInfoRecordMTK(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubInfoRecord>();
                        }
                        subList.add(subInfo);
                }
                }
            } else {
                logd("Query fail");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return subList;
    }

    /**
     * Get the SubInfoRecord according to slotId
     * @param slotId the slot which the SIM is inserted
     * @return SubInfoRecord, maybe null
     */
    @Override
    public List<SubInfoRecord> getSubInfoUsingSlotId(int slotId) {
        return getSubInfoUsingSlotIdWithCheck(slotId, true);
    }

    /**
     * Get all the SubInfoRecord(s) in subinfo database
     * @return Array list of all SubInfoRecords in database, include thsoe that were inserted before
     */
    @Override
    public List<SubInfoRecord> getAllSubInfoListMTK() {
        logd("[getAllSubInfoList]+");
        enforceSubscriptionPermission();

        List<SubInfoRecord> subList = null;
        subList = getSubInfoMTK(null, null);
        if (subList != null) {
            logd("[getAllSubInfoList]- " + subList.size() + " infos return");
        } else {
            logd("[getAllSubInfoList]- no info return");
        }

        return subList;
    }

    /**
     * Get the SubInfoRecord(s) of the currently inserted SIM(s)
     * @return Array list of currently inserted SubInfoRecord(s)
     */
    @Override
    public List<SubInfoRecord> getActiveSubInfoList() {
        enforceSubscriptionPermission();
        logdl("[getActiveSubInfoList]+");

        if (!isSubInfoReady()) {
            logdl("[getActiveSubInfoList] Sub Controller not ready");
            return null;
        }

        if (mActiveList != null) {
            logd("[getActiveSubInfoList]- " + mActiveList.size() + " infos return");
        } else {
            logd("[getActiveSubInfoList]- no info return");
        }

        return mActiveList;
    }

    /**
     * Get the active SubscriptionInfo with the subId key
     * @param subId The unique SubscriptionInfo key in database
     * @return SubscriptionInfo, maybe null if its not active
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfo(int subId) {
        enforceSubscriptionPermission();
        if (!SubscriptionManager.isValidSubscriptionId(subId) || !isSubInfoReady()) {
            logd("[getSubInfoUsingSubIdx]- invalid subId or not ready = " + subId);
            return null;
        }

        List<SubscriptionInfo> subList = getActiveSubscriptionInfoList();
        if (subList != null) {
            for (SubscriptionInfo si : subList) {
                if (si.getSubscriptionId() == subId) {
                    if (DBG) logd("[getActiveSubInfoForSubscriber]+ subId=" + subId + " subInfo=" + si);
                    return si;
                }
            }
        }
        if (DBG) {
            logd("[getActiveSubInfoForSubscriber]- subId=" + subId
                    + " subList=" + subList + " subInfo=null");
        }
        return null;
    }

    /**
     * Get the active SubscriptionInfo associated with the iccId
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo, maybe null if its not active
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfoForIccId(String iccId) {
        enforceSubscriptionPermission();

        List<SubscriptionInfo> subList = getActiveSubscriptionInfoList();
        if (subList != null) {
            for (SubscriptionInfo si : subList) {
                if (si.getIccId() == iccId) {
                    if (DBG) logd("[getActiveSubInfoUsingIccId]+ iccId=" + iccId + " subInfo=" + si);
                    return si;
                }
            }
        }
        if (DBG) {
            logd("[getActiveSubInfoUsingIccId]+ iccId=" + iccId
                    + " subList=" + subList + " subInfo=null");
        }
        return null;
    }

    /**
     * Get the active SubscriptionInfo associated with the slotIdx
     * @param slotIdx the slot which the subscription is inserted
     * @return SubscriptionInfo, maybe null if its not active
     */
    @Override
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIdx) {
        enforceSubscriptionPermission();

        List<SubscriptionInfo> subList = getActiveSubscriptionInfoList();
        if (subList != null) {
            for (SubscriptionInfo si : subList) {
                if (si.getSimSlotIndex() == slotIdx) {
                    if (DBG) {
                        logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIdx=" + slotIdx
                            + " subId=" + si);
                    }
                    return si;
                }
            }
            if (DBG) {
                logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIdx=" + slotIdx
                    + " subId=null");
            }
        } else {
            if (DBG) {
                logd("[getActiveSubscriptionInfoForSimSlotIndex]+ subList=null");
            }
        }
        return null;
    }

    /**
     * @return List of all SubscriptionInfo records in database,
     * include those that were inserted before, maybe empty but not null.
     * @hide
     */
    @Override
    public List<SubscriptionInfo> getAllSubInfoList() {
        if (DBG) logd("[getAllSubInfoList]+");
        enforceSubscriptionPermission();

        List<SubscriptionInfo> subList = null;
        subList = getSubInfo(null, null);
        if (subList != null) {
            if (DBG) logd("[getAllSubInfoList]- " + subList.size() + " infos return");
        } else {
            if (DBG) logd("[getAllSubInfoList]- no info return");
        }

        return subList;
    }

    /**
     * Get the SubInfoRecord(s) of the currently inserted SIM(s)
     * @param context Context provided by caller
     * @return Array list of currently inserted SubInfoRecord(s)
     */
    @Override
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        enforceSubscriptionPermission();
        if (DBG) logdl("[getActiveSubInfoList]+");

        List<SubscriptionInfo> subList = null;

        if (!isSubInfoReady()) {
            if (DBG) logdl("[getActiveSubInfoList] Sub Controller not ready");
            return subList;
        }

        subList = getSubInfo(SubscriptionManager.SIM_SLOT_INDEX + ">=0", null);
        if (subList != null) {
            // FIXME: Unnecessary when an insertion sort is used!
            Collections.sort(subList, new Comparator<SubscriptionInfo>() {
                @Override
                public int compare(SubscriptionInfo arg0, SubscriptionInfo arg1) {
                    // Primary sort key on SimSlotIndex
                    int flag = arg0.getSimSlotIndex() - arg1.getSimSlotIndex();
                    if (flag == 0) {
                        // Secondary sort on SubscriptionId
                        return arg0.getSubscriptionId() - arg1.getSubscriptionId();
                    }
                    return flag;
                }
            });

            if (DBG) logdl("[getActiveSubInfoList]- " + subList.size() + " infos return");
        } else {
            if (DBG) logdl("[getActiveSubInfoList]- no info return");
        }

        return subList;
    }

    /**
     * Get the SUB count of active SUB(s)
     * @return active SIM count
     */
    @Override
    public int getActiveSubInfoCount() {
        logd("[getActiveSubInfoCount]+");
        List<SubInfoRecord> records = getActiveSubInfoList();
        if (records == null) {
            logd("[getActiveSubInfoCount] records null");
            return 0;
        }
        logd("[getActiveSubInfoCount]- count: " + records.size());
        return records.size();
    }

    /**
     * Get the SUB count of all SUB(s) in subinfo database
     * @return all SIM count in database, include what was inserted before
     */
    @Override
    public int getAllSubInfoCount() {
        logd("[getAllSubInfoCount]+");
        enforceSubscriptionPermission();

        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, null, null, null);
        try {
            if (cursor != null) {
                int count = cursor.getCount();
                logd("[getAllSubInfoCount]- " + count + " SUB(s) in DB");
                return count;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        logd("[getAllSubInfoCount]- no SUB in DB");

        return 0;
    }

    /**
     * @return the maximum number of subscriptions this device will support at any one time.
     */
    @Override
    public int getActiveSubInfoCountMax() {
        // FIXME: This valid now but change to use TelephonyDevController in the future
        return mTelephonyManager.getSimCount();
    }

    /**
     * Add a new SubInfoRecord to subinfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotId the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     */
    @Override
    public int addSubInfoRecord(String iccId, int slotId) {
        logdl("[addSubInfoRecord]+ iccId:" + iccId + " slotId:" + slotId);
        enforceSubscriptionPermission();

        if (iccId == null) {
            logdl("[addSubInfoRecord]- null iccId");
        }

        int[] subIds = getSubId(slotId);
        if (subIds == null || subIds.length == 0) {
            logdl("[addSubInfoRecord]- getSubId fail");
            return 0;
        }

        String nameToSet;
        SpnOverride mSpnOverride = new SpnOverride();

        String CarrierName = TelephonyManager.getDefault().getSimOperator(subIds[0]);
        logdl("[addSubInfoRecord] CarrierName = " + CarrierName);

        if (mSpnOverride.containsCarrier(CarrierName)) {
            if (mIsOP01) {
                nameToSet = mSpnOverride.lookupOperatorName(subIds[0], CarrierName, true, mContext);
            } else {
                nameToSet = mSpnOverride.lookupOperatorName(subIds[0], CarrierName, true, mContext)
                    + " 0" + Integer.toString(slotId + 1);
            }
            logdl("[addSubInfoRecord] SPN Found, name = " + nameToSet);
        } else {
            nameToSet = SubscriptionManager.SUB_PREFIX + Integer.toString(slotId + 1);
            logdl("[addSubInfoRecord] SPN Not found, name = " + nameToSet);
        }

        ContentResolver resolver = mContext.getContentResolver();
        Cursor cursor = resolver.query(SubscriptionManager.CONTENT_URI, null,
                SubscriptionManager.ICC_ID + "=?", new String[] {iccId}, null);

        Uri uri = null;
        int result = 0;
        int subId = 0;
        SubInfoRecord newRecord;

        try {
            if (cursor == null || !cursor.moveToFirst()) {
                ContentValues value = new ContentValues();
                value.put(SubscriptionManager.ICC_ID, iccId);
                // default SIM color differs between slots
                value.put(SubscriptionManager.COLOR, slotId);
                value.put(SubscriptionManager.SIM_ID, slotId);
                value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                uri = resolver.insert(SubscriptionManager.CONTENT_URI, value);
                logdl("[addSubInfoRecord]- New record created: " + uri);

                newRecord = new SubInfoRecord();
                newRecord.subId = Integer.valueOf(uri.getLastPathSegment());
                newRecord.iccId = iccId;
                newRecord.slotId = slotId;
                newRecord.color = slotId;
                newRecord.displayName = nameToSet;
				/*
                newRecord.simIconRes[RES_TYPE_BACKGROUND_DARK] =
                        sSimBackgroundDarkRes[newRecord.color];
                newRecord.simIconRes[RES_TYPE_BACKGROUND_LIGHT] =
                        sSimBackgroundLightRes[newRecord.color];
				*/
           } else {
                newRecord = getSubInfoRecordMTK(cursor);
                subId = cursor.getInt(cursor.getColumnIndexOrThrow(BaseColumns._ID));;
                int oldSimInfoId = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.SIM_ID));
                int nameSource = cursor.getInt(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.NAME_SOURCE));
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow(
                    SubscriptionManager.DISPLAY_NAME));
                ContentValues value = new ContentValues();

                if (slotId != oldSimInfoId) {
                    value.put(SubscriptionManager.SIM_ID, slotId);
                    newRecord.slotId = slotId;
                    if (mIsOP01) {
                        value.put(SubscriptionManager.COLOR, slotId);
                        newRecord.color = newRecord.slotId;
						/*
                        newRecord.simIconRes[RES_TYPE_BACKGROUND_DARK]
                                = sSimBackgroundDarkRes[newRecord.color];
                        newRecord.simIconRes[RES_TYPE_BACKGROUND_LIGHT]
                                = sSimBackgroundLightRes[newRecord.color];
						*/
                    }
                }

                if (nameSource != SubscriptionManager.NAME_SOURCE_USER_INPUT
                    && displayName.startsWith(SubscriptionManager.SUB_PREFIX)) {
                    value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
                    newRecord.displayName = nameToSet;
                }

                if (value.size() > 0) {
                    result = resolver.update(SubscriptionManager.CONTENT_URI, value,
                            BaseColumns._ID + "=" + Integer.toString(subId), null);
                }

                logdl("[addSubInfoRecord]- Record already exist");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }


        //Add active list
        if (mActiveList == null) {
            mActiveList = new ArrayList();
        }

        mActiveList.add(newRecord);
        logd("[addSubInfoRecord] Active list size=" + mActiveList.size());

        cursor = resolver.query(SubscriptionManager.CONTENT_URI, null,
                SubscriptionManager.SIM_ID + "=?", new String[] {String.valueOf(slotId)}, null);

        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    subId = cursor.getInt(cursor.getColumnIndexOrThrow(BaseColumns._ID));
                    // If sSimInfo already has a valid subId for a slotId/phoneId,
                    // do not add another subId for same slotId/phoneId.
                    Integer currentSubId = sSimInfo.get(slotId);
                    if (currentSubId == null || !SubscriptionManager.isValidSubId(currentSubId)) {
                        // TODO While two subs active, if user deactivats first
                        // one, need to update the default subId with second one.

                        // FIXME: Currently we assume phoneId and slotId may not be true
                        // when we cross map modem or when multiple subs per slot.
                        // But is true at the moment.
                        sSimInfo.put(slotId, subId);
                        int simCount = TelephonyManager.getDefault().getSimCount();
                        int defaultSubId = getDefaultSubId();
                        logdl("[addSubInfoRecord] sSimInfo.size=" + sSimInfo.size()
                                + " slotId=" + slotId + " subId=" + subId
                                + " defaultSubId=" + defaultSubId + " simCount=" + simCount);

                        // Set the default sub if not set or if single sim device
                        if (!SubscriptionManager.isValidSubId(defaultSubId) || simCount == 1
                                || SubscriptionManager.isValidSubId(subId)) {
                            setDefaultSubId(subId);
                        }
                        // If single sim device, set this subscription as the default for everything
                        if (simCount == 1) {
                            logdl("[addSubInfoRecord] one sim set defaults to subId=" + subId);
                            setDefaultDataSubId(subId);
                            setDefaultSmsSubId(subId);
                            setDefaultVoiceSubId(subId);
                        }
                    } else {
                        logdl("[addSubInfoRecord] currentSubId != null && currentSubId is valid, IGNORE");
                    }
                    logdl("[addSubInfoRecord]- hashmap("+slotId+","+subId+")");
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        int size = sSimInfo.size();
        logdl("[addSubInfoRecord]- info size="+size);

        // Once the records are loaded, notify DcTracker
        updateAllDataConnectionTrackers();

        // FIXME this does not match the javadoc
        return 1;
    }

    /**
     * Set SIM color by simInfo index
     * @param color the color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setColor(int color, int subId) {
        logd("[setColor]+ color:" + color + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        int size = 0;  // sSimBackgroundDarkRes.length;
        if (color < 0 || color >= size) {
            logd("[setColor]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.COLOR, color);
        logd("[setColor]- color:" + color + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Integer.toString(subId), null);

        if (mActiveList != null && result > 0) {
            for (SubInfoRecord record : mActiveList) {
                if (record.subId == subId) {
                    record.color = color;
					/*
                    record.simIconRes[RES_TYPE_BACKGROUND_DARK] =
                        sSimBackgroundDarkRes[record.color];
                    record.simIconRes[RES_TYPE_BACKGROUND_LIGHT] =
                        sSimBackgroundLightRes[record.color];
					*/
                }
            }
        }

        broadcastSimInfoContentChanged(subId, SubscriptionManager.COLOR,
                color, SubscriptionManager.DEFAULT_STRING_VALUE);

        return result;
    }

    /**
     * Set display name by simInfo index
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayName(String displayName, int subId) {
        return setDisplayNameUsingSrc(displayName, subId, -1);
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubInfoRecord index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, -1 NAME_SOURCE_UNDEFINED
     * @return the number of records updated
     */
    @Override
    public int setDisplayNameUsingSrc(String displayName, int subId, long nameSource) {
        logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                + " nameSource:" + nameSource);
        enforceSubscriptionPermission();

        validateSubId(subId);
        String nameToSet;
        if (displayName == null) {
            nameToSet = mContext.getString(SubscriptionManager.DEFAULT_NAME_RES);
        } else {
            nameToSet = displayName;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DISPLAY_NAME, nameToSet);
        if (nameSource >= SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE) {
            logd("Set nameSource=" + nameSource);
            value.put(SubscriptionManager.NAME_SOURCE, nameSource);
        }
        logd("[setDisplayName]- mDisplayName:" + nameToSet + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Integer.toString(subId), null);

        if (mActiveList != null && result > 0) {
            for (SubInfoRecord record : mActiveList) {
                if (record.subId == subId) {
                    record.displayName = nameToSet;
                    if (nameSource >= SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE) {
                        record.nameSource = (int) nameSource;
                    }
                }
            }
        }

        broadcastSimInfoContentChanged(subId, SubscriptionManager.DISPLAY_NAME,
                SubscriptionManager.DEFAULT_INT_VALUE, nameToSet);

        return result;
    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayNumber(String number, int subId) {
        return setDisplayNumber(number, subId, true);
    }

    /**
     * Set number display format. 0: none, 1: the first four digits, 2: the last four digits
     * @param format the display format of phone number
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDisplayNumberFormat(int format, int subId) {
        logd("[setDisplayNumberFormat]+ format:" + format + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        if (format < 0) {
            logd("[setDisplayNumberFormat]- fail, return -1");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DISPLAY_NUMBER_FORMAT, format);
        logd("[setDisplayNumberFormat]- format:" + format + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Integer.toString(subId), null);

        if (mActiveList != null && result > 0) {
            for (SubInfoRecord record : mActiveList) {
                if (record.subId == subId) {
                    record.displayNumberFormat = format;
                }
            }
        }

        broadcastSimInfoContentChanged(subId, SubscriptionManager.DISPLAY_NUMBER_FORMAT,
                format, SubscriptionManager.DEFAULT_STRING_VALUE);

        return result;
    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    @Override
    public int setDataRoaming(int roaming, int subId) {
        logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        enforceSubscriptionPermission();

        validateSubId(subId);
        if (roaming < 0) {
            logd("[setDataRoaming]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.DATA_ROAMING, roaming);
        logd("[setDataRoaming]- roaming:" + roaming + " set");

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Integer.toString(subId), null);

        if (mActiveList != null && result > 0) {
            for (SubInfoRecord record : mActiveList) {
                if (record.subId == subId) {
                    record.dataRoaming = roaming;
                }
            }
        }

        broadcastSimInfoContentChanged(subId, SubscriptionManager.DATA_ROAMING,
                roaming, SubscriptionManager.DEFAULT_STRING_VALUE);

        return result;
    }

    /**
     * Set MCC/MNC by subscription ID
     * @param mccMnc MCC/MNC associated with the subscription
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     */
    public int setMccMnc(String mccMnc, int subId) {
        int mcc = 0;
        int mnc = 0;
        try {
            mcc = Integer.parseInt(mccMnc.substring(0,3));
            mnc = Integer.parseInt(mccMnc.substring(3));
        } catch (NumberFormatException e) {
            logd("[setMccMnc] - couldn't parse mcc/mnc: " + mccMnc);
        }
        logd("[setMccMnc]+ mcc/mnc:" + mcc + "/" + mnc + " subId:" + subId);
        ContentValues value = new ContentValues(2);
        value.put(SubscriptionManager.MCC, mcc);
        value.put(SubscriptionManager.MNC, mnc);

        int result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                BaseColumns._ID + "=" + Integer.toString(subId), null);

        if (mActiveList != null && result > 0) {
            for (SubInfoRecord record : mActiveList) {
                if (record.subId == subId) {
                    record.mcc = mcc;
                    record.mnc = mnc;
                }
            }
        }
        broadcastSimInfoContentChanged(subId, SubscriptionManager.MCC, mcc, null);

        return result;
    }


    @Override
    public int getSlotId(int subId) {
        if (VDBG) printStackTrace("[getSlotId] subId=" + subId);

        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            logd("[getSlotId]+ subId == SubscriptionManager.DEFAULT_SUB_ID");
            subId = getDefaultSubId();
        }
        if (!SubscriptionManager.isValidSubId(subId)) {
            logd("[getSlotId]- subId invalid");
            return SubscriptionManager.INVALID_SLOT_ID;
        }

        int size = sSimInfo.size();

        if (size == 0)
        {
            logd("[getSlotId]- size == 0, return SIM_NOT_INSERTED instead, subId =" + subId);
            return SubscriptionManager.SIM_NOT_INSERTED;
        }

        for (Entry<Integer, Integer> entry: sSimInfo.entrySet()) {
            int sim = entry.getKey();
            int sub = entry.getValue();

            if (subId == sub)
            {
                logd("[getSlotId]- return =" + sim + ", subId = " + subId);
                return sim;
            }
        }

        logd("[getSlotId]- return INVALID_SLOT_ID, subId = " + subId);
        return SubscriptionManager.INVALID_SLOT_ID;
    }

    /**
     * Return the subId for specified slot Id.
     * @deprecated
     */
    @Override
    @Deprecated
    public int[] getSubId(int slotId) {
        if (VDBG) printStackTrace("[getSubId] slotId=" + slotId);

        if (slotId == SubscriptionManager.DEFAULT_SLOT_ID) {
            logd("[getSubId]+ slotId == SubscriptionManager.DEFAULT_SLOT_ID");
            slotId = getSlotId(getDefaultSubId());
        }

        //FIXME remove this
        final int[] DUMMY_VALUES = {-1 - slotId, -1 - slotId};

        if (!SubscriptionManager.isValidSlotId(slotId)) {
            logd("[getSubId]- invalid slotId, slotId = " + slotId);
            return null;
        }

        //FIXME remove this
        if (slotId < 0) {
            logd("[getSubId]- slotId < 0, return dummy instead, slotId = " + slotId);
            return DUMMY_VALUES;
        }

        int size = sSimInfo.size();

        if (size == 0) {
            logd("[getSubId]- size == 0, return dummy instead, slotId = " + slotId);
            //FIXME return null
            return DUMMY_VALUES;
        }

        ArrayList<Integer> subIds = new ArrayList<Integer>();
        for (Entry<Integer, Integer> entry: sSimInfo.entrySet()) {
            int slot = entry.getKey();
            int sub = entry.getValue();
            if (slotId == slot) {
                subIds.add(sub);
            }
        }

        logd("[getSubId]-, subIds = " + subIds);
        int numSubIds = subIds.size();

        if (numSubIds == 0) {
            logd("[getSubId]- numSubIds == 0, return dummy instead, slotId = " + slotId);
            return DUMMY_VALUES;
        }

        int[] subIdArr = new int[numSubIds];
        for (int i = 0; i < numSubIds; i++) {
            subIdArr[i] = subIds.get(i);
        }

        return subIdArr;
    }

    @Override
    public int getPhoneId(int subId) {
        if (VDBG) printStackTrace("[getPhoneId] subId=" + subId);
        int phoneId;

        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            subId = getDefaultSubId();
            logd("[getPhoneId] asked for default subId=" + subId);
        }

        if (!SubscriptionManager.isValidSubId(subId)) {
            logd("[getPhoneId]- invalid subId, subId = " + subId);
            return SubscriptionManager.INVALID_PHONE_ID;
        }

        //FIXME remove this
        if (subId < 0) {
            phoneId = (int) (-1 - subId);
            if (VDBG) logdl("[getPhoneId]- map subId=" + subId + " phoneId=" + phoneId);
            return phoneId;
        }

        int size = sSimInfo.size();

        if (size == 0) {
            logd("[getPhoneId]- no sims, returning defaultPhoneId, subId = " + subId);
            return mDefaultPhoneId;
        }

        // FIXME: Assumes phoneId == slotId
        for (Entry<Integer, Integer> entry: sSimInfo.entrySet()) {
            int sim = entry.getKey();
            int sub = entry.getValue();

            if (subId == sub) {
                logd("[getPhoneId]- return phoneId =" + sim + ", subId = " + subId);
                return sim;
            }
        }

        logd("[getPhoneId]- return mDefaultPhoneId, subId = " + subId);
        return mDefaultPhoneId;

    }

    /**
     * @return the number of records cleared
     */
    @Override
    public int clearSubInfo() {
        enforceSubscriptionPermission();
        logd("[clearSubInfo]+");

        int size = sSimInfo.size();

        if (size == 0) {
            logdl("[clearSubInfo]- no simInfo size=" + size);
            return 0;
        }

        sSimInfo.clear();

        size = mActiveList.size();
        mActiveList.clear();

        logd("[clearSubInfo]- clear size=" + size);
        return size;
    }

	/*
    private static int[] setSimResource(int type) {
        // Return same images for all types.
        int[] simResource = new int[] {
                    com.mediatek.internal.R.drawable.sim_indicator_yellow,
                    com.mediatek.internal.R.drawable.sim_indicator_orange,
                    com.mediatek.internal.R.drawable.sim_indicator_green,
                    com.mediatek.internal.R.drawable.sim_indicator_purple
                };

        return simResource;
    }
	*/

    private void logvl(String msg) {
        logv(msg);
        mLocalLog.log(msg);
    }

    private void logv(String msg) {
        Rlog.v(LOG_TAG, msg);
    }

    private void logdl(String msg) {
        logd(msg);
        mLocalLog.log(msg);
    }

    private static void slogd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    private void logel(String msg) {
        loge(msg);
        mLocalLog.log(msg);
    }

    private void loge(String msg) {
        Rlog.e(LOG_TAG, msg);
    }

    @Override
    @Deprecated
    public int getDefaultSubId() {
        //FIXME To remove this api, All clients should be using getDefaultVoiceSubId
        // int subId = Settings.Global.getInt(mContext.getContentResolver(),
        //         Settings.Global.MULTI_SIM_COMMON_SUBSCRIPTION,
        //         -1);
		final int subId = -1;
        if (VDBG) logv("[getDefaultSubId] value = " + subId + ", stub!");
        return subId;
    }

    @Override
    public void setDefaultSmsSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultSmsSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultSmsSubId] subId=" + subId + ", stub!");
        // Settings.Global.putInt(mContext.getContentResolver(),
        //         Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION, subId);
        broadcastDefaultSmsSubIdChanged(subId);
    }

    private void broadcastDefaultSmsSubIdChanged(int subId) {
        // Broadcast an Intent for default sms sub change
        logdl("[broadcastDefaultSmsSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    public int getDefaultSmsSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_SMS_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUB_ID);
        if (VDBG) logd("[getDefaultSmsSubId] subId=" + subId);
        return subId;
    }

    @Override
    public void setDefaultVoiceSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultVoiceSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultVoiceSubId] subId=" + subId);
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION, subId);
        broadcastDefaultVoiceSubIdChanged(subId);
    }

    private void broadcastDefaultVoiceSubIdChanged(int subId) {
        // Broadcast an Intent for default voice sub change
        logdl("[broadcastDefaultVoiceSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_VOICE_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    @Override
    public int getDefaultVoiceSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_VOICE_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUB_ID);
        if (VDBG) logd("[getDefaultVoiceSubId] subId=" + subId);
        return subId;
    }

    @Override
    public int getDefaultDataSubId() {
        int subId = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION,
                SubscriptionManager.INVALID_SUB_ID);
        if (VDBG) logd("[getDefaultDataSubId] subId= " + subId);
        return subId;
    }

    @Override
    public void setDefaultDataSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultDataSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultDataSubId] subId=" + subId);

        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.MULTI_SIM_DATA_CALL_SUBSCRIPTION, subId);
        broadcastDefaultDataSubIdChanged(subId);

        // FIXME is this still needed?
        updateAllDataConnectionTrackers();
    }

    private void updateAllDataConnectionTrackers() {
        // Tell Phone Proxies to update data connection tracker
        int len = sProxyPhones.length;
        logdl("[updateAllDataConnectionTrackers] sProxyPhones.length=" + len);
        for (int phoneId = 0; phoneId < len; phoneId++) {
            logdl("[updateAllDataConnectionTrackers] phoneId=" + phoneId);
            sProxyPhones[phoneId].updateDataConnectionTracker();
        }
    }

    private void broadcastDefaultDataSubIdChanged(int subId) {
        // Broadcast an Intent for default data sub change
        logdl("[broadcastDefaultDataSubIdChanged] subId=" + subId);
        Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /* Sets the default subscription. If only one sub is active that
     * sub is set as default subId. If two or more  sub's are active
     * the first sub is set as default subscription
     */
    // FIXME
    public void setDefaultSubId(int subId) {
        if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("setDefaultSubId called with DEFAULT_SUB_ID");
        }
        logdl("[setDefaultSubId] subId=" + subId);
        if (SubscriptionManager.isValidSubId(subId)) {
            int phoneId = getPhoneId(subId);
            if (phoneId >= 0 && (phoneId < TelephonyManager.getDefault().getPhoneCount()
                    || TelephonyManager.getDefault().getSimCount() == 1)) {
                logdl("[setDefaultSubId] set mDefaultVoiceSubId=" + subId);
                mDefaultVoiceSubId = subId;
                // Settings.Global.putInt(mContext.getContentResolver(),
                //         Settings.Global.MULTI_SIM_COMMON_SUBSCRIPTION, subId);
				logdl("[setDefaultSubId] MULTI_SIM_COMMON_SUBSCRIPTION not implemented!");
                // Update MCC MNC device configuration information
                String defaultMccMnc = TelephonyManager.getDefault().getSimOperator(subId);
                MccTable.updateMccMncConfiguration(mContext, defaultMccMnc, false);

                // Broadcast an Intent for default sub change
                Intent intent = new Intent(TelephonyIntents.ACTION_DEFAULT_SUBSCRIPTION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                SubscriptionManager.putPhoneIdAndSubIdExtra(intent, phoneId, subId);
                if (VDBG) {
                    logdl("[setDefaultSubId] broadcast default subId changed phoneId=" + phoneId
                            + " subId=" + subId);
                }
                mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            } else {
                if (VDBG) {
                    logdl("[setDefaultSubId] not set invalid phoneId=" + phoneId + " subId=" + subId);
                }
            }
        }
    }

    @Override
    public void clearDefaultsForInactiveSubIds() {
        final List<SubInfoRecord> records = getActiveSubInfoList();
        logdl("[clearDefaultsForInactiveSubIds] records: " + records);
        if (shouldDefaultBeCleared(records, getDefaultDataSubId())) {
            logd("[clearDefaultsForInactiveSubIds] clearing default data sub id");
            setDefaultDataSubId(SubscriptionManager.INVALID_SUB_ID);
        }
        if (shouldDefaultBeCleared(records, getDefaultSmsSubId())) {
            logdl("[clearDefaultsForInactiveSubIds] clearing default sms sub id");
            setDefaultSmsSubId(SubscriptionManager.INVALID_SUB_ID);
        }
        if (shouldDefaultBeCleared(records, getDefaultVoiceSubId())) {
            logdl("[clearDefaultsForInactiveSubIds] clearing default voice sub id");
            setDefaultVoiceSubId(SubscriptionManager.INVALID_SUB_ID);
        }
    }

    private boolean shouldDefaultBeCleared(List<SubInfoRecord> records, int subId) {
        logdl("[shouldDefaultBeCleared: subId] " + subId);
        if (records == null) {
            logdl("[shouldDefaultBeCleared] return true no records subId=" + subId);
            return true;
        }
        if (subId == SubscriptionManager.ASK_USER_SUB_ID && records.size() > 1) {
            // Only allow ASK_USER_SUB_ID if there is more than 1 subscription.
            logdl("[shouldDefaultBeCleared] return false only one subId, subId=" + subId);
            return false;
        }
        for (SubInfoRecord record : records) {
            logdl("[shouldDefaultBeCleared] Record.subId: " + record.subId);
            if (record.subId == subId) {
                logdl("[shouldDefaultBeCleared] return false subId is active, subId=" + subId);
                return false;
            }
        }
        logdl("[shouldDefaultBeCleared] return true not active subId=" + subId);
        return true;
    }

    /* This should return int and not int [] since each phone has
     * exactly 1 sub id for now, it could return the 0th element
     * returned from getSubId()
     */
    // FIXME will design a mechanism to manage the relationship between PhoneId/SlotId/SubId
    // since phoneId = SlotId is not always true
    public int getSubIdUsingPhoneId(int phoneId) {
        int[] subIds = getSubId(phoneId);
        if (subIds == null || subIds.length == 0) {
            return SubscriptionManager.INVALID_SUB_ID;
        }
        return subIds[0];
    }

    public int[] getSubIdUsingSlotId(int slotId) {
        return getSubId(slotId);
    }

    public List<SubInfoRecord> getSubInfoUsingSlotIdWithCheck(int slotId, boolean needCheck) {
        logd("[getSubInfoUsingSlotIdWithCheck]+ slotId:" + slotId);
        enforceSubscriptionPermission();

        if (slotId == SubscriptionManager.DEFAULT_SLOT_ID) {
            slotId = getSlotId(getDefaultSubId());
        }
        if (!SubscriptionManager.isValidSlotId(slotId)) {
            logd("[getSubInfoUsingSlotIdWithCheck]- invalid slotId");
            return null;
        }

        if (needCheck && !isSubInfoReady()) {
            logd("[getSubInfoUsingSlotIdWithCheck]- not ready");
            return null;
        }

        Cursor cursor = mContext.getContentResolver().query(SubscriptionManager.CONTENT_URI,
                null, SubscriptionManager.SIM_ID + "=?", new String[] {String.valueOf(slotId)}, null);
        ArrayList<SubInfoRecord> subList = null;
        try {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    SubInfoRecord subInfo = getSubInfoRecordMTK(cursor);
                    if (subInfo != null)
                    {
                        if (subList == null)
                        {
                            subList = new ArrayList<SubInfoRecord>();
                        }
                        subList.add(subInfo);
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        logd("[getSubInfoUsingSlotId]- null info return");

        return subList;
    }

    private void validateSubId(int subId) {
        logd("validateSubId subId: " + subId);
        if (!SubscriptionManager.isValidSubId(subId)) {
            throw new RuntimeException("Invalid sub id passed as parameter");
        } else if (subId == SubscriptionManager.DEFAULT_SUB_ID) {
            throw new RuntimeException("Default sub id passed as parameter");
        }
    }

    public void updatePhonesAvailability(PhoneProxy[] phones) {
        sProxyPhones = phones;
    }

    /**
     * @return the list of subId's that are active, is never null but the length maybe 0.
     */
    @Override
    public int[] getActiveSubIdList() {
        Set<Entry<Integer, Integer>> simInfoSet = sSimInfo.entrySet();
        logdl("[getActiveSubIdList] simInfoSet=" + simInfoSet);

        int[] subIdArr = new int[simInfoSet.size()];
        int i = 0;
        for (Entry<Integer, Integer> entry: simInfoSet) {
            int sub = entry.getValue();
            subIdArr[i] = sub;
            i++;
        }

        logdl("[getActiveSubIdList] X subIdArr.length=" + subIdArr.length);
        return subIdArr;
    }

    private static void printStackTrace(String msg) {
        RuntimeException re = new RuntimeException();
        slogd("StackTrace - " + msg);
        StackTraceElement[] st = re.getStackTrace();
        boolean first = true;
        for (StackTraceElement ste : st) {
            if (first) {
                first = false;
            } else {
                slogd(ste.toString());
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP,
                "Requires DUMP");
        pw.println("SubscriptionController:");
        pw.println(" defaultSubId=" + getDefaultSubId());
        pw.println(" defaultDataSubId=" + getDefaultDataSubId());
        pw.println(" defaultVoiceSubId=" + getDefaultVoiceSubId());
        pw.println(" defaultSmsSubId=" + getDefaultSmsSubId());

        pw.println(" defaultDataPhoneId=" + SubscriptionManager.getDefaultDataPhoneId());
        pw.println(" defaultVoicePhoneId=" + SubscriptionManager.getDefaultVoicePhoneId());
        pw.println(" defaultSmsPhoneId=" + SubscriptionManager.getDefaultSmsPhoneId());
        pw.flush();

        for (Entry<Integer, Integer> entry : sSimInfo.entrySet()) {
            pw.println(" sSimInfo[" + entry.getKey() + "]: subId=" + entry.getValue());
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        List<SubInfoRecord> sirl = getActiveSubInfoList();
        if (sirl != null) {
            pw.println(" ActiveSubInfoList:");
            for (SubInfoRecord entry : sirl) {
                pw.println("  " + entry.toString());
            }
        } else {
            pw.println(" ActiveSubInfoList: is null");
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        sirl = getAllSubInfoListMTK();
        if (sirl != null) {
            pw.println(" AllSubInfoList:");
            for (SubInfoRecord entry : sirl) {
                pw.println("  " + entry.toString());
            }
        } else {
            pw.println(" AllSubInfoList: is null");
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");

        mLocalLog.dump(fd, pw, args);
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
    }

    /**
     * Set phone number by subId.
     * @param number the phone number of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @param writeToSim should write to SIM at the same time
     * @return the number of records updated
     */
    public int setDisplayNumber(String number, int subId, boolean writeToSim) {
        logd("[setDisplayNumber]+ number:" + number + " subId:" + subId
                + ", writeToSim:" + writeToSim);
        enforceSubscriptionPermission();

        validateSubId(subId);
        int result = 0;
        int phoneId = getPhoneId(subId);

        if (number == null || phoneId < 0 ||
                phoneId >= TelephonyManager.getDefault().getPhoneCount()) {
            logd("[setDispalyNumber]- fail");
            return -1;
        }
        ContentValues value = new ContentValues(1);
        value.put(SubscriptionManager.NUMBER, number);
        logd("[setDisplayNumber]- number:" + number + " set");

        if (writeToSim) {
            Phone phone = sProxyPhones[phoneId];
            String alphaTag = TelephonyManager.getDefault().getLine1AlphaTagForSubscriber(subId);

            synchronized (mLock) {
                mSuccess = false;
                Message response = mHandler.obtainMessage(EVENT_WRITE_MSISDN_DONE);

                phone.setLine1Number(alphaTag, number, response);

                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    loge("interrupted while trying to write MSISDN");
                }
            }
        }

        if (mSuccess || !writeToSim) {
            result = mContext.getContentResolver().update(SubscriptionManager.CONTENT_URI, value,
                    BaseColumns._ID + "=" + Integer.toString(subId), null);
            logd("[setDisplayNumber]- update result :" + result);

            if (mActiveList != null && result > 0) {
                for (SubInfoRecord record : mActiveList) {
                    if (record.subId == subId) {
                        record.number = number;
                    }
                }
            }

            broadcastSimInfoContentChanged(subId, SubscriptionManager.NUMBER,
                    SubscriptionManager.DEFAULT_INT_VALUE, number);
        }

        return result;
    }

    /**
     * @return the number of records cleared by slotId
     * @param slotId the slot which cleared
     */
    public int clearSubInfo(int slotId) {
        enforceSubscriptionPermission();
        logd("[clearSubInfo]+, slotId = " + slotId);

        int size = sSimInfo.size();
        logd("[clearSubInfo]- before mapping size=" + size);
        if (size == 0) {
            logdl("[clearSubInfo]- no simInfo size=" + size);
            return 0;
        }

        sSimInfo.remove(slotId);
        size = sSimInfo.size();
        logd("[clearSubInfo]- before mapping size=" + size);

        size = mActiveList.size();
        logd("[clearSubInfo]- before active size=" + size);
        if (mActiveList != null) {
            for (Iterator<SubInfoRecord> records = mActiveList.iterator(); records.hasNext(); ) {
                SubInfoRecord record = records.next();
                if (record.slotId == slotId) {
                    records.remove();
                }
            }
        }

        size = mActiveList.size();
        logd("[clearSubInfo]- after clear size=" + size);
        return size;
    }
}
