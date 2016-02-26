/*
 * Copyright (C) 2008 The Android Open Source Project
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


package com.android.server.power;

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothManager;
import android.nfc.NfcAdapter;
import android.nfc.INfcAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.SystemVibrator;
import android.os.storage.IMountService;
import android.os.storage.IMountShutdownObserver;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.view.Surface;
import android.net.ConnectivityManager;

import com.android.internal.telephony.ITelephony;
import com.android.server.power.PowerManagerService;

import android.util.Log;
import android.view.WindowManager;
import android.view.IWindowManager;

// Wakelock
import android.os.PowerManager.WakeLock;

// For IPO
import com.android.internal.app.ShutdownManager;
import com.mediatek.common.featureoption.FeatureOption;
import android.provider.Settings;

import com.mediatek.common.bootanim.IBootAnimExt;
import com.mediatek.common.MediatekClassFactory;
import com.mediatek.common.hdmi.IHDMINative; // add for HDMI power control
import dalvik.system.Zygote;

public final class ShutdownThread extends Thread {
    // constants
    private static final String TAG = "ShutdownThread";
    private static final int PHONE_STATE_POLL_SLEEP_MSEC = 500;
    // maximum time we wait for the shutdown broadcast before going on.
    private static final int MAX_BROADCAST_TIME = 10*1000;
    private static final int MAX_SHUTDOWN_WAIT_TIME = 20*1000;
    private static final int MAX_RADIO_WAIT_TIME = 12*1000;

    // length of vibration before shutting down
    private static final int SHUTDOWN_VIBRATE_MS = 500;

    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;

    private static boolean mReboot;
    private static boolean mRebootSafeMode;
    private static String mRebootReason;

    // Provides shutdown assurance in case the system_server is killed
    public static final String SHUTDOWN_ACTION_PROPERTY = "sys.shutdown.requested";

    // Indicates whether we are rebooting into safe mode
    public static final String REBOOT_SAFEMODE_PROPERTY = "persist.sys.safemode";

    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();

    private final Object mActionDoneSync = new Object();
    private boolean mActionDone;
    private Context mContext;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mCpuWakeLock;
    private PowerManager.WakeLock mScreenWakeLock;
    private Handler mHandler;

    private static AlertDialog sConfirmDialog = null;

    // IPO
    private static ProgressDialog pd = null;
    private static Object mShutdownThreadSync = new Object();
    private ShutdownManager stMgr = new ShutdownManager();

    // Shutdown Flow Settings
    private static final int NORMAL_SHUTDOWN_FLOW = 0x0;
    private static final int IPO_SHUTDOWN_FLOW = 0x1;
    private static int mShutdownFlow;

    // Shutdown Animation
    private static final int MIN_SHUTDOWN_ANIMATION_PLAY_TIME = 5*1000; // CU/CMCC operator require 3-5s
    private static long beginAnimationTime = 0;
    private static long endAnimationTime = 0;
    private static boolean bConfirmForAnimation = true;
    private static boolean bPlayaudio = true;

    private static final Object mEnableAnimatingSync = new Object();
    private static boolean mEnableAnimating = true;

    // length of waiting for memory dump if Modem Exception occurred
    private static final int MAX_MEMORY_DUMP_TIME = 60 * 1000;

    private static String command;  //for bypass radioOff
    private static int screen_turn_off_time = 5 * 1000;   //after 5sec  the screen become OFF, you can change the time delay

    private static IHDMINative ImHDMI = null; // for MFR

    private static final boolean mSpew = true;   //debug enable

    private static IBootAnimExt mIBootAnim = null; // for boot animation 

    private ShutdownThread() {
    }

    public static boolean isPowerOffDialogShowing() {
        return (sConfirmDialog != null && sConfirmDialog.isShowing());
    }

    public static void EnableAnimating(boolean enable) {
        synchronized (mEnableAnimatingSync) {
            mEnableAnimating = enable;
        }
    }

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void shutdown(final Context context, boolean confirm) {
        mReboot = false;
        mRebootSafeMode = false;

        Log.d(TAG, "!!! Request to shutdown !!!");

        ShutdownManager.startFtraceCapture();

        if (mSpew) {
            StackTraceElement[] stack = new Throwable().getStackTrace();
            for (StackTraceElement element : stack)
            {
                Log.d(TAG, " 	|----" + element.toString());
            }
        }

        createPreShutdownApi(context);

        if (SystemProperties.getBoolean("ro.monkey", false)) {
            Log.d(TAG, "Cannot request to shutdown when Monkey is running, returning.");
            return;
        }

        shutdownInner(context, confirm);
    }

    static void shutdownInner(final Context context, boolean confirm) {
        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
        }

        Log.d(TAG, "Notifying thread to start radio shutdown");
        bConfirmForAnimation = confirm;
        final int longPressBehavior = context.getResources().getInteger(
                        com.android.internal.R.integer.config_longPressOnPowerBehavior);
        final int resourceId = mRebootSafeMode
                ? com.android.internal.R.string.reboot_safemode_confirm
                : (longPressBehavior == 2
                        ? com.android.internal.R.string.shutdown_confirm_question
                        : com.android.internal.R.string.shutdown_confirm);

        Log.d(TAG, "Notifying thread to start shutdown longPressBehavior=" + longPressBehavior);

        if (confirm) {
            final CloseDialogReceiver closer = new CloseDialogReceiver(context);
            if (sConfirmDialog != null) {
                sConfirmDialog.dismiss();
            }
            if (sConfirmDialog == null) {
                Log.d(TAG, "PowerOff dialog doesn't exist. Create it first");
                sConfirmDialog = new AlertDialog.Builder(context)
                    .setTitle(mRebootSafeMode
                            ? com.android.internal.R.string.reboot_safemode_title
                            : com.android.internal.R.string.power_off)
                    .setMessage(resourceId)
                    .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                            beginShutdownSequence(context);
                            if (sConfirmDialog != null) {
                            sConfirmDialog = null;
                            }
                            }
                            })
                .setNegativeButton(com.android.internal.R.string.no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                        synchronized (sIsStartedGuard) {
                        sIsStarted = false;
                        }
                        if (sConfirmDialog != null) {
                        sConfirmDialog = null;
                        }
                        }
                        })
                .create();
                sConfirmDialog.setCancelable(false);//blocking back key
                sConfirmDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                /*if (!context.getResources().getBoolean(
                  com.android.internal.R.bool.config_sf_slowBlur)) {
                  sConfirmDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                  }*/
                /* To fix video+UI+blur flick issue */
                sConfirmDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            }

            closer.dialog = sConfirmDialog;
            sConfirmDialog.setOnDismissListener(closer);

            if (!sConfirmDialog.isShowing()) {
                sConfirmDialog.show();
            }
        } else {
            beginShutdownSequence(context);
        }
    }

    private static class CloseDialogReceiver extends BroadcastReceiver
            implements DialogInterface.OnDismissListener {
        private Context mContext;
        public Dialog dialog;

        CloseDialogReceiver(Context context) {
            mContext = context;
            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "CloseDialogReceiver: onReceive");
            dialog.cancel();
        }

        public void onDismiss(DialogInterface unused) {
            mContext.unregisterReceiver(this);
        }
    }

    private static Runnable mDelayDim = new Runnable() {   //use for animation, add by how.wang
        public void run() {
            Log.d(TAG, "setBacklightBrightness: Off");
            if (sInstance.mScreenWakeLock != null && sInstance.mScreenWakeLock.isHeld()) {
                sInstance.mScreenWakeLock.release();
                sInstance.mScreenWakeLock = null;
            }
            sInstance.mPowerManager.setBacklightBrightnessOff(true);
        }
    };

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void reboot(final Context context, String reason, boolean confirm) {
        mReboot = true;
        mRebootSafeMode = false;
        mRebootReason = reason;
        Log.d(TAG, "reboot");

        if (mSpew) {
            StackTraceElement[] stack = new Throwable().getStackTrace();
            for (StackTraceElement element : stack)
            {
                Log.d(TAG, " 	|----" + element.toString());
            }
        }

        shutdownInner(context, confirm);
    }

    /**
     * Request a reboot into safe mode.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void rebootSafeMode(final Context context, boolean confirm) {
        mReboot = true;
        mRebootSafeMode = true;
        mRebootReason = null;
        Log.d(TAG, "rebootSafeMode");
        shutdownInner(context, confirm);
    }

    private static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Shutdown sequence already running, returning.");
                return;
            }
            sIsStarted = true;
        }

        // start the thread that initiates shutdown
        sInstance.mContext = context;
        sInstance.mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        sInstance.mHandler = new Handler() {
        };    

        bPlayaudio = true;
        if (!bConfirmForAnimation) {
            if (!sInstance.mPowerManager.isScreenOn()) {
                bPlayaudio = false;
            }
        }

        // throw up an indeterminate system dialog to indicate radio is
        // shutting down.
        beginAnimationTime = 0;
        boolean mShutOffAnimation = false;

        try {
            if (mIBootAnim == null) {
                mIBootAnim = MediatekClassFactory.createInstance(IBootAnimExt.class);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        int screenTurnOffTime = mIBootAnim.getScreenTurnOffTime();
        mShutOffAnimation = mIBootAnim.isCustBootAnim();
        Log.e(TAG, "mIBootAnim get screenTurnOffTime : " + screenTurnOffTime);

        String cust = SystemProperties.get("ro.operator.optr");

        if (cust != null) {
            if (cust.equals("CUST")) {
                mShutOffAnimation = true;
            }
        }

        synchronized (mEnableAnimatingSync) {

            if(!mEnableAnimating) {
                //sInstance.mPowerManager.setBacklightBrightness(PowerManager.BRIGHTNESS_DIM);
            } else {
                if (mShutOffAnimation) {
                    Log.e(TAG, "mIBootAnim.isCustBootAnim() is true");
                    bootanimCust();
                } else {
                    pd = new ProgressDialog(context);
                    pd.setTitle(context.getText(com.android.internal.R.string.power_off));
                    pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
                    pd.setIndeterminate(true);
                    pd.setCancelable(false);
                    pd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
                    /* To fix video+UI+blur flick issue */
                    pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    pd.show();
                }
                sInstance.mHandler.postDelayed(mDelayDim, screenTurnOffTime); 
            }
        }

        // make sure we never fall asleep again
        sInstance.mCpuWakeLock = null;
        try {
            sInstance.mCpuWakeLock = sInstance.mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, TAG + "-cpu");
            sInstance.mCpuWakeLock.setReferenceCounted(false);
            sInstance.mCpuWakeLock.acquire();
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to acquire wake lock", e);
            sInstance.mCpuWakeLock = null;
        }
        Log.d(TAG, "shutdown acquire partial WakeLock: cpu");

        // also make sure the screen stays on for better user experience
        sInstance.mScreenWakeLock = null;
        if (sInstance.mPowerManager.isScreenOn()) {
            try {
                sInstance.mScreenWakeLock = sInstance.mPowerManager.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK, TAG + "-screen");
                sInstance.mScreenWakeLock.setReferenceCounted(false);
                sInstance.mScreenWakeLock.acquire();
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to acquire wake lock", e);
                sInstance.mScreenWakeLock = null;
            }
        }

        // start the thread that initiates shutdown
        if (sInstance.getState() != Thread.State.NEW || sInstance.isAlive()) {
            if (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
                Log.d(TAG, "ShutdownThread exists already");
                checkShutdownFlow();
                synchronized (mShutdownThreadSync) {
                    mShutdownThreadSync.notify();
                }
            } else {
                Log.e(TAG, "Thread state is not normal! froce to shutdown!");
                delayForPlayAnimation();
                //unmout data/cache partitions while performing shutdown    
                //Power.shutdown();
                sInstance.mPowerManager.setBacklightBrightnessOff(true);
                SystemProperties.set("ctl.start", "shutdown");
            }
        } else {
            sInstance.start();
        }
    }

    private static void bootanimCust() {
        // [MTK] fix shutdown animation timing issue
        //==================================================================
        SystemProperties.set("service.shutanim.running","0");
        Log.i(TAG, "set service.shutanim.running to 0");
        //==================================================================
        boolean isRotaionEnabled = false;
        try {
            isRotaionEnabled = Settings.System.getInt(sInstance.mContext.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1) != 0;
            if (isRotaionEnabled) {
                final IWindowManager wm = IWindowManager.Stub.asInterface(
                        ServiceManager.getService(Context.WINDOW_SERVICE));
                if (wm != null) {
                    wm.freezeRotation(Surface.ROTATION_0);
                }
                Settings.System.putInt(sInstance.mContext.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, 0);
                Settings.System.putInt(sInstance.mContext.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION_RESTORE, 1);
            }
        } catch (NullPointerException ex) {
            Log.e(TAG, "check Rotation: sInstance.mContext object is null when get Rotation");
        } catch (RemoteException e) {
        }
        beginAnimationTime = SystemClock.elapsedRealtime() + MIN_SHUTDOWN_ANIMATION_PLAY_TIME;
        // +MediaTek 2012-02-25 Disable key dispatch
        try {
            final IWindowManager wm = IWindowManager.Stub.asInterface(
                    ServiceManager.getService(Context.WINDOW_SERVICE));
            if (wm != null) {
                wm.setEventDispatching(false);
            }
        } catch (RemoteException e) {}
        // -MediaTek 2012-02-25 Disable key dispatch
        startBootAnimation();
    }

    private static void startBootAnimation() {

        try {
            SystemProperties.set("service.bootanim.exit","0");
            Log.d(TAG, "Set 'service.bootanim.exit' = 0).");
        } catch (Exception ex) {
            Log.e(TAG, "Failed to set 'service.bootanim.exit' = 0).");
        }   

        if (bPlayaudio) {
            SystemProperties.set("ctl.start","bootanim:shut mp3");
            Log.d(TAG, "bootanim:shut mp3" );
        } else {
            SystemProperties.set("ctl.start","bootanim:shut nomp3");
            Log.d(TAG, "bootanim:shut nomp3" );
        }
    }

    void actionDone() {
        synchronized (mActionDoneSync) {
            mActionDone = true;
            mActionDoneSync.notifyAll();
        }
    }

    private static void delayForPlayAnimation() {
        if (beginAnimationTime <= 0) {
            return;
        }
        endAnimationTime = beginAnimationTime - SystemClock.elapsedRealtime();
        if (endAnimationTime > 0) {
            try {
                Thread.currentThread().sleep(endAnimationTime);
            } catch ( Exception e) {
                Log.e(TAG, "Shutdown stop bootanimation Thread.currentThread().sleep exception!");
            }
        }
    }

    /*
     * Please make sure that context object is already instantiated already before calling this method.
     * However, we'll still catch null pointer exception here in case.
     */
    private static void checkShutdownFlow() {
        Log.d(TAG, "checkShutdownFlow: IPO_Support=" + FeatureOption.MTK_IPO_SUPPORT + " mReboot=" + mReboot);
        if (FeatureOption.MTK_IPO_SUPPORT == false || mReboot == true || Zygote.systemInSafeMode == true) {
            mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
            return;
        }

        boolean isIPOEnabled;
        
        try {
            isIPOEnabled = Settings.System.getInt(sInstance.mContext.getContentResolver(),
                    Settings.System.IPO_SETTING, 1) == 1;
        } catch (NullPointerException ex) {
            Log.e(TAG, "checkShutdownFlow: sInstance.mContext object is null when get IPO enable/disable Option");
            mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
            return;
        }

        if (isIPOEnabled == true) {
            if ("1".equals(SystemProperties.get("sys.ipo.battlow")))
                mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
            else
                mShutdownFlow = IPO_SHUTDOWN_FLOW;
        } else {
            mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
        }

        // power off auto test, don't modify
        Log.d(TAG, "checkShutdownFlow: isIPOEnabled=" + isIPOEnabled + " mShutdownFlow=" + mShutdownFlow);
        return;
    }

    /**
     * Makes sure we handle the shutdown gracefully.
     * Shuts off power regardless of radio and bluetooth state if the alloted time has passed.
     */
    public void run() {
        checkShutdownFlow();
        while (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
            stMgr.saveStates(mContext);
            stMgr.enterShutdown(mContext);
            running();
        }
        if (mShutdownFlow != IPO_SHUTDOWN_FLOW) {
            stMgr.enterShutdown(mContext);
            running();
        }
    }

    public void running() {
        if(sPreShutdownApi != null){
            try {
                sPreShutdownApi.onPowerOff();
            } catch (RemoteException e) {
                Log.e(TAG, "onPowerOff exception" + e.getMessage());
            }
        }else{
            Log.w(TAG, "sPreShutdownApi is null");
        }

        command = SystemProperties.get("sys.ipo.pwrdncap");

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // We don't allow apps to cancel this, so ignore the result.
                actionDone();
            }
        };

        /*
         * Write a system property in case the system_server reboots before we
         * get to the actual hardware restart. If that happens, we'll retry at
         * the beginning of the SystemServer startup.
         */
        {
            String reason = (mReboot ? "1" : "0") + (mRebootReason != null ? mRebootReason : "");
            SystemProperties.set(SHUTDOWN_ACTION_PROPERTY, reason);
        }

        /*
         * If we are rebooting into safe mode, write a system property
         * indicating so.
         */
        if (mRebootSafeMode) {
            SystemProperties.set(REBOOT_SAFEMODE_PROPERTY, "1");
        }

        Log.i(TAG, "Sending shutdown broadcast...");

        // First send the high-level shut down broadcast.
        mActionDone = false;
        /// M: 2012-05-20 ALPS00286063 @{
        mContext.sendBroadcast(new Intent("android.intent.action.ACTION_PRE_SHUTDOWN"));
        /// @} 2012-05-20
        mContext.sendOrderedBroadcastAsUser((new Intent()).setAction(Intent.ACTION_SHUTDOWN).putExtra("_mode", mShutdownFlow),
                UserHandle.ALL, null, br, mHandler, 0, null, null);

        final long endTime = SystemClock.elapsedRealtime() + MAX_BROADCAST_TIME;
        synchronized (mActionDoneSync) {
            while (!mActionDone) {
                long delay = endTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown broadcast ACTION_SHUTDOWN timed out");
                    if (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
                        Log.d(TAG, "change shutdown flow from ipo to normal: ACTION_SHUTDOWN timeout");
                        mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
                    }
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }

        // Also send ACTION_SHUTDOWN_IPO in IPO shut down flow
        if (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
            mActionDone = false;
            mContext.sendOrderedBroadcast(new Intent("android.intent.action.ACTION_SHUTDOWN_IPO"), null,
                    br, mHandler, 0, null, null);
            final long endTimeIPO = SystemClock.elapsedRealtime() + MAX_BROADCAST_TIME;
            synchronized (mActionDoneSync) {
                while (!mActionDone) {
                    long delay = endTimeIPO - SystemClock.elapsedRealtime();
                    if (delay <= 0) {
                        Log.w(TAG, "Shutdown broadcast ACTION_SHUTDOWN_IPO timed out");
                        if (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
                            Log.d(TAG, "change shutdown flow from ipo to normal: ACTION_SHUTDOWN_IPO timeout");
                            mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
                        }
                        break;
                    }
                    try {
                        mActionDoneSync.wait(delay);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        if (mShutdownFlow != IPO_SHUTDOWN_FLOW) {
            // power off auto test, don't modify
            Log.i(TAG, "Shutting down activity manager...");

            final IActivityManager am =
                ActivityManagerNative.asInterface(ServiceManager.checkService("activity"));
            if (am != null) {
                try {
                    am.shutdown(MAX_BROADCAST_TIME);
                } catch (RemoteException e) {
                }
            }
        }

        // power off auto test, don't modify
        // Shutdown radios.
        Log.i(TAG, "Shutting down radios...");
        shutdownRadios(MAX_RADIO_WAIT_TIME);

        // power off auto test, don't modify
        Log.i(TAG, "Shutting down MountService...");
        if ( (mShutdownFlow == IPO_SHUTDOWN_FLOW) && (command.equals("1")||command.equals("3")) ) {
            Log.i(TAG, "bypass MountService!");
        } else {
            // Shutdown MountService to ensure media is in a safe state
            IMountShutdownObserver observer = new IMountShutdownObserver.Stub() {
                public void onShutDownComplete(int statusCode) throws RemoteException {
                    Log.w(TAG, "Result code " + statusCode + " from MountService.shutdown");
                    if (statusCode < 0) {
                        mShutdownFlow = NORMAL_SHUTDOWN_FLOW; 
                    }
                    actionDone();
                }
            };

            // Set initial variables and time out time.
            mActionDone = false;
            final long endShutTime = SystemClock.elapsedRealtime() + MAX_SHUTDOWN_WAIT_TIME;
            synchronized (mActionDoneSync) {
                try {
                    final IMountService mount = IMountService.Stub.asInterface(
                            ServiceManager.checkService("mount"));
                    if (mount != null) {
                        mount.shutdown(observer);
                    } else {
                        Log.w(TAG, "MountService unavailable for shutdown");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception during MountService shutdown", e);
                }
                while (!mActionDone) {
                    long delay = endShutTime - SystemClock.elapsedRealtime();
                    if (delay <= 0) {
                        Log.w(TAG, "Shutdown wait timed out");
                        if (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
                            Log.d(TAG, "change shutdown flow from ipo to normal: MountService");
                            mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
                        }
                        break;
                    }
                    try {
                        mActionDoneSync.wait(delay);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        // power off auto test, don't modify
        //mountSerivce 
        Log.i(TAG, "MountService shut done...");
        // [MTK] fix shutdown animation timing issue
        //==================================================================
        try {
            SystemProperties.set("service.shutanim.running","1");
            Log.i(TAG, "set service.shutanim.running to 1");

        } catch (Exception ex) {
            Log.e(TAG, "Failed to set 'service.shutanim.running' = 1).");
        }
        //==================================================================

        if (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
            if (SHUTDOWN_VIBRATE_MS > 0) {
                // vibrate before shutting down
                Vibrator vibrator = new SystemVibrator();
                try {
                    vibrator.vibrate(SHUTDOWN_VIBRATE_MS);
                } catch (Exception e) {
                    // Failure to vibrate shouldn't interrupt shutdown.  Just log it.
                    Log.w(TAG, "Failed to vibrate during shutdown.", e);
                }

                // vibrator is asynchronous so we need to wait to avoid shutting down too soon.
                try {
                    Thread.sleep(SHUTDOWN_VIBRATE_MS);
                } catch (InterruptedException unused) {
                }
            }

            // Shutdown power
            // power off auto test, don't modify
            Log.i(TAG, "Performing ipo low-level shutdown...");

            delayForPlayAnimation();

            if (sInstance.mScreenWakeLock != null && sInstance.mScreenWakeLock.isHeld()) {
                sInstance.mScreenWakeLock.release();
                sInstance.mScreenWakeLock = null;
            }

            sInstance.mHandler.removeCallbacks(mDelayDim);
            stMgr.shutdown(mContext);
            stMgr.finishShutdown(mContext);
            ShutdownManager.stopFtraceCapture();

            //To void previous UI flick caused by shutdown animation stopping before BKL turning off
            if (pd != null) {
                pd.dismiss();
                pd = null;
            } else if (beginAnimationTime > 0) {
                try {
                    SystemProperties.set("service.bootanim.exit","1");
                    Log.i(TAG, "set 'service.bootanim.exit' = 1).");
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to set 'service.bootanim.exit' = 1).");
                }  
                //SystemProperties.set("ctl.stop","bootanim");
            }

            synchronized (sIsStartedGuard) {
                sIsStarted = false;
            }

            sInstance.mPowerManager.setBacklightBrightnessOff(false); 
            sInstance.mCpuWakeLock.acquire(2000); 

            synchronized (mShutdownThreadSync) {
                try {
                    mShutdownThreadSync.wait();
                } catch (InterruptedException e) {
                }
            }
        } else {
            ShutdownManager.stopFtraceCapture();
            rebootOrShutdown(mReboot, mRebootReason);
        }
    }

    private void shutdownRadios(int timeout) {
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        final boolean bypassRadioOff = cm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false ||
            ( (mShutdownFlow == IPO_SHUTDOWN_FLOW) && (command.equals("2")||command.equals("3")) );

        // If a radio is wedged, disabling it may hang so we do this work in another thread,
        // just in case.
        final long endTime = SystemClock.elapsedRealtime() + timeout;
        final boolean[] done = new boolean[1];
        Thread t = new Thread() {
            public void run() {
                boolean nfcOff;
                boolean bluetoothOff;
                boolean radioOff;

                Log.w(TAG, "task run");

                final INfcAdapter nfc =
                    INfcAdapter.Stub.asInterface(ServiceManager.checkService("nfc"));
                final ITelephony phone =
                    ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
                final IBluetoothManager bluetooth =
                        IBluetoothManager.Stub.asInterface(ServiceManager.checkService(
                                BluetoothAdapter.BLUETOOTH_MANAGER_SERVICE));

                Log.w(TAG, "Turning off NFC");

                try {
                    nfcOff = nfc == null ||
                        nfc.getState() == NfcAdapter.STATE_OFF;
                    if (!nfcOff) {
                        Log.w(TAG, "Turning off NFC...");
                        nfc.disable(false); // Don't persist new state
                    }
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during NFC shutdown", ex);
                    nfcOff = true;
                }

                Log.w(TAG, "Turning off BT");

                try {
                    bluetoothOff = bluetooth == null || !bluetooth.isEnabled();
                    if (!bluetoothOff) {
                        Log.w(TAG, "Disabling Bluetooth...");
                        bluetooth.disable(false);  // disable but don't persist new state
                    }
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                    bluetoothOff = true;
                }

                try {
                    radioOff = phone == null || !phone.isRadioOn();
                    if (mShutdownFlow != IPO_SHUTDOWN_FLOW) {
                        //if (!radioOff) {
                        //Log.w(TAG, "Turning off radio...");
                        //phone.setRadio(false);
                        //} 

                        // Should always trigger modem shutdown
                        Log.w(TAG, "Turning off radio...");
                        //Judge whether "phone" is ready. 
                        //If we power on under low power condition, Shutdown may run here without adding "phone" service to system yet.
                        //With this scerario, phone object is null pointer.
                        if (phone != null) {
                            phone.setRadioOff();
                        }
                    }
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during radio shutdown", ex);
                    radioOff = true;
                }

                Log.i(TAG, "Waiting for NFC, Bluetooth and Radio...");

                if (bypassRadioOff) {
                    done[0] = true;
                    Log.i(TAG, "bypass RadioOff!");
                } else {
                    while (SystemClock.elapsedRealtime() < endTime) {
                        if (!bluetoothOff) {
                            try {
                                bluetoothOff = !bluetooth.isEnabled();
                            } catch (RemoteException ex) {
                                Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                                bluetoothOff = true;
                            }
                            if (bluetoothOff) {
                                Log.i(TAG, "Bluetooth turned off.");
                            }
                        }
                        if (!radioOff) {
                            try {
                                radioOff = !phone.isRadioOn();
                            } catch (RemoteException ex) {
                                Log.e(TAG, "RemoteException during radio shutdown", ex);
                                radioOff = true;
                            }
                            if (radioOff) {
                                Log.i(TAG, "Radio turned off.");
                            }
                        }
                        if (!nfcOff) {
                            try {
                                nfcOff = nfc.getState() == NfcAdapter.STATE_OFF;
                            } catch (RemoteException ex) {
                                Log.e(TAG, "RemoteException during NFC shutdown", ex);
                                nfcOff = true;
                            }
                            if (radioOff) {
                                Log.i(TAG, "NFC turned off.");
                            }
                        }

                        if (radioOff && bluetoothOff && nfcOff) {
                            Log.i(TAG, "NFC, Radio and Bluetooth shutdown complete.");
                            done[0] = true;
                            break;
                        }
                        SystemClock.sleep(PHONE_STATE_POLL_SLEEP_MSEC);
                    }
                }
            }
        };

        t.start();
        try {
            t.join(timeout);
        } catch (InterruptedException ex) {
        }
        if (!done[0]) {
            Log.w(TAG, "Timed out waiting for NFC, Radio and Bluetooth shutdown.");
            if (mShutdownFlow == IPO_SHUTDOWN_FLOW) {
                Log.d(TAG, "change shutdown flow from ipo to normal: BT/MD");
                mShutdownFlow = NORMAL_SHUTDOWN_FLOW;
            }
            if (SystemProperties.get("debug.mdlogger.Running").equals("1")) {
                Log.d(TAG, "radioOff = false and mdlogger is running now, so wait for memory dump");
                //SystemClock.sleep(Integer.MAX_VALUE);     /* endless wait */
                SystemClock.sleep(MAX_MEMORY_DUMP_TIME);
            }
        }
    }

    /**
     * Do not call this directly. Use {@link #reboot(Context, String, boolean)}
     * or {@link #shutdown(Context, boolean)} instead.
     *
     * @param reboot true to reboot or false to shutdown
     * @param reason reason for reboot
     */
    public static void rebootOrShutdown(boolean reboot, String reason) {
        if (reboot) {
            Log.i(TAG, "Rebooting, reason: " + reason);
            if ( (reason != null) && reason.equals("recovery") ) {
                delayForPlayAnimation();
            }
            try {
                PowerManagerService.lowLevelReboot(reason);
            } catch (Exception e) {
                Log.e(TAG, "Reboot failed, will attempt shutdown instead", e);
            }
        } else if (SHUTDOWN_VIBRATE_MS > 0) {
            // vibrate before shutting down
            Vibrator vibrator = new SystemVibrator();
            try {
                vibrator.vibrate(SHUTDOWN_VIBRATE_MS);
            } catch (Exception e) {
                // Failure to vibrate shouldn't interrupt shutdown.  Just log it.
                Log.w(TAG, "Failed to vibrate during shutdown.", e);
            }

            // vibrator is asynchronous so we need to wait to avoid shutting down too soon.
            try {
                Thread.sleep(SHUTDOWN_VIBRATE_MS);
            } catch (InterruptedException unused) {
            }
        }

        delayForPlayAnimation();
        // Shutdown power
        // power off auto test, don't modify
        Log.i(TAG, "Performing low-level shutdown...");
        //PowerManagerService.lowLevelShutdown();
        //add your func: HDMI off
        //add for MFR
        try {
            if (ImHDMI == null)
                ImHDMI=MediatekClassFactory.createInstance(IHDMINative.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
        ImHDMI.hdmiPowerEnable(false);

        //unmout data/cache partitions while performing shutdown

        sInstance.mPowerManager.setBacklightBrightnessOff(true);
        SystemProperties.set("ctl.start", "shutdown");

        /* sleep for a long time, prevent start another service */
        try {
            Thread.currentThread().sleep(Integer.MAX_VALUE);
        } catch ( Exception e) {
            Log.e(TAG, "Shutdown rebootOrShutdown Thread.currentThread().sleep exception!");
        }
    }

    private static IPreShutdown sPreShutdownApi = null;

    public static void createPreShutdownApi(Context context) {
	    Log.d(TAG, "createPreShutdownApi(), context = " + context);
        Intent intent = new Intent();
        intent.setAction("com.mediatek.IPreShutdown");
        if (context != null) {
            Log.w(TAG, "bindservice:" + intent.getAction());
            context.bindService(intent, sPreShutdownConnection, 0);
        } else {
            Log.w(TAG, "context is null, cannot bindservice");
        }
       
    }

    protected static ServiceConnection sPreShutdownConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.v(TAG, "onServiceConnected() entry");
            sPreShutdownApi = IPreShutdown.Stub.asInterface(service);
            Log.v(TAG, "onServiceConnected() exit");
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.v(TAG, "onServiceDisconnected() entry");
            sPreShutdownApi = null;
            Log.v(TAG, "onServiceDisconnected() exit");
        }
    };   
}
