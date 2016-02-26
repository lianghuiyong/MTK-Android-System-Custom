/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

/**
 * Manages creating, showing, hiding and resetting the keyguard.  Calls back
 * via {@link KeyguardViewMediator.ViewMediatorCallback} to poke
 * the wake lock and report that the keyguard is done, which is in turn,
 * reported to this class by the current {@link KeyguardViewBase}.
 */
public class KeyguardViewManager {
    private final static boolean DEBUG = KeyguardViewMediator.DEBUG;
    private static String TAG = "KeyguardViewManager";
    public static boolean USE_UPPER_CASE = true;

    // Timeout used for keypresses
    static final int DIGIT_PRESS_WAKE_MILLIS = 5000;

    private final Context mContext;
    private final ViewManager mViewManager;
    private final KeyguardViewMediator.ViewMediatorCallback mViewMediatorCallback;

    private WindowManager.LayoutParams mWindowLayoutParams;
    private boolean mNeedsInput = false;

    private FrameLayout mKeyguardHost;
    private KeyguardHostView mKeyguardView;

    private boolean mScreenOn = false;
    private LockPatternUtils mLockPatternUtils;

    public interface ShowListener {
        void onShown(IBinder windowToken);
    };

    /**
     * @param context Used to create views.
     * @param viewManager Keyguard will be attached to this.
     * @param callback Used to notify of changes.
     * @param lockPatternUtils
     */
    public KeyguardViewManager(Context context, ViewManager viewManager,
            KeyguardViewMediator.ViewMediatorCallback callback,
            LockPatternUtils lockPatternUtils) {
        mContext = context;
        mViewManager = viewManager;
        mViewMediatorCallback = callback;
        mLockPatternUtils = lockPatternUtils;
    }

    /**
     * Show the keyguard.  Will handle creating and attaching to the view manager
     * lazily.
     */
    public synchronized void show(Bundle options) {
        if (DEBUG) KeyguardUtils.xlogD(TAG, "show(); mKeyguardView=" + mKeyguardView);

        boolean enableScreenRotation = shouldEnableScreenRotation();
        if (DEBUG) KeyguardUtils.xlogD(TAG, "show() query screen rotation after");

        /// M: Incoming Indicator for Keyguard Rotation @{
        KeyguardUpdateMonitor.getInstance(mContext).setQueryBaseTime();
        /// @}
        maybeCreateKeyguardLocked(enableScreenRotation, false, options);
        
        if (DEBUG) KeyguardUtils.xlogD(TAG, "show() maybeCreateKeyguardLocked finish");
        
        maybeEnableScreenRotation(enableScreenRotation);

        // Disable common aspects of the system/status/navigation bars that are not appropriate or
        // useful on any keyguard screen but can be re-shown by dialogs or SHOW_WHEN_LOCKED
        // activities. Other disabled bits are handled by the KeyguardViewMediator talking
        // directly to the status bar service.
        final int visFlags = View.STATUS_BAR_DISABLE_HOME;
        if (DEBUG) KeyguardUtils.xlogD(TAG, "show:setSystemUiVisibility(" + Integer.toHexString(visFlags)+")");
        mKeyguardHost.setSystemUiVisibility(visFlags);

        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
        mKeyguardHost.setVisibility(View.VISIBLE);
        mKeyguardView.show();
        mKeyguardView.requestFocus();
        if (DEBUG) KeyguardUtils.xlogD(TAG, "show() exit; mKeyguardView=" + mKeyguardView);
    }

    private boolean shouldEnableScreenRotation() {
        Resources res = mContext.getResources();
        return SystemProperties.getBoolean("lockscreen.rot_override",false)
                || res.getBoolean(com.android.internal.R.bool.config_enableLockScreenRotation);
    }

    class ViewManagerHost extends FrameLayout {
        
        public ViewManagerHost(Context context) {
            super(context);
            setFitsSystemWindows(true);
            /// M: Save initial config when view created
            mCreateOrientation = context.getResources().getConfiguration().orientation;
        }

        @Override
        protected boolean fitSystemWindows(Rect insets) {
            Log.v("TAG", "bug 7643792: fitSystemWindows(" + insets.toShortString() + ")");
            return super.fitSystemWindows(insets);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            if (DEBUG) {
                KeyguardUtils.xlogD(TAG, "onConfigurationChanged, old orientation=" + mCreateOrientation +
                        ", new orientation=" + newConfig.orientation);
            }
            /// M: Optimization, only create views when orientation changed
            if (mCreateOrientation != newConfig.orientation) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (KeyguardViewManager.this) {
                            if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                                // only propagate configuration messages if we're currently showing
                                maybeCreateKeyguardLocked(shouldEnableScreenRotation(), true, null);
                            } else {
                                if (DEBUG) KeyguardUtils.xlogD(TAG, "onConfigurationChanged: view not visible");
                            }
                        }
                    }
                });
            } else {
                if (DEBUG) KeyguardUtils.xlogD(TAG, "onConfigurationChanged: orientation not changed");
            }
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (mKeyguardView != null) {
                // Always process back and menu keys, regardless of focus
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    if (keyCode == KeyEvent.KEYCODE_BACK && mKeyguardView.handleBackKey()) {
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MENU && mKeyguardView.handleMenuKey()) {
                        return true;
                    }
                }
                // Always process media keys, regardless of focus
                /// M: [ALPS00601974] Avoid dispatch keyevent twice.
                return mKeyguardView.dispatchKeyEvent(event);
            }
            return super.dispatchKeyEvent(event);
        }
    }

    SparseArray<Parcelable> mStateContainer = new SparseArray<Parcelable>();

    private void maybeCreateKeyguardLocked(boolean enableScreenRotation, boolean force,
            Bundle options) {
        final boolean isActivity = (mContext instanceof Activity); // for test activity

        if (mKeyguardHost != null) {
            mKeyguardHost.saveHierarchyState(mStateContainer);
        }

        if (mKeyguardHost == null) {
            if (DEBUG) KeyguardUtils.xlogD(TAG, "keyguard host is null, creating it...");

            mKeyguardHost = new ViewManagerHost(mContext);

            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;

            /// M: Modify to support alarm clock. @{
            KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
			if (KeyguardUpdateMonitor.isAlarmBoot()) {
                if (DEBUG) KeyguardUtils.xlogD(TAG, "show(); AlarmBoot ");
                flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                flags |= WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
            }
            /// M: @}
            if (!mNeedsInput) {
                flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
            if (ActivityManager.isHighEndGfx()) {
                flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            }

            final int stretch = ViewGroup.LayoutParams.MATCH_PARENT;
            final int type = isActivity ? WindowManager.LayoutParams.TYPE_APPLICATION
                    : WindowManager.LayoutParams.TYPE_KEYGUARD;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    stretch, stretch, type, flags, PixelFormat.TRANSLUCENT);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            lp.windowAnimations = com.android.internal.R.style.Animation_LockScreen;
            if (ActivityManager.isHighEndGfx()) {
                lp.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
                lp.privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
            }
            lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SET_NEEDS_MENU_KEY;
            if (isActivity) {
                lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            }
            /// M: Poke user activity when operating Keyguard
            //lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
            lp.setTitle(isActivity ? "KeyguardMock" : "Keyguard");
            mWindowLayoutParams = lp;
            mViewManager.addView(mKeyguardHost, lp);
        }
        
        /// M: If force and keyguardView is not null, we should relase memory hold by old keyguardview
        if (force && mKeyguardView != null) {
            mKeyguardView.cleanUp();
        }

        if (force || mKeyguardView == null) {
            inflateKeyguardView(options);
            mKeyguardView.requestFocus();
        }
        updateUserActivityTimeoutInWindowLayoutParams();
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);

        mKeyguardHost.restoreHierarchyState(mStateContainer);
    }

    private void inflateKeyguardView(Bundle options) {
        /// M: add for power-off alarm @{
        int resId = R.id.keyguard_host_view;
        int layoutId = R.layout.keyguard_host_view;
        if(KeyguardUpdateMonitor.isAlarmBoot()){
            layoutId = com.mediatek.internal.R.layout.power_off_alarm_host_view;
            resId = com.mediatek.internal.R.id.keyguard_host_view;
        }
        /// @}
        View v = mKeyguardHost.findViewById(resId);
        if (v != null) {
            mKeyguardHost.removeView(v);
        }
        // TODO: Remove once b/7094175 is fixed
        if (false) Slog.d(TAG, "inflateKeyguardView: b/7094175 mContext.config="
                + mContext.getResources().getConfiguration());
        
        /// M: Save new orientation
        mCreateOrientation = mContext.getResources().getConfiguration().orientation;
        
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        View view = inflater.inflate(layoutId, mKeyguardHost, true);
        mKeyguardView = (KeyguardHostView) view.findViewById(resId);
        mKeyguardView.setLockPatternUtils(mLockPatternUtils);
        mKeyguardView.setViewMediatorCallback(mViewMediatorCallback);

        // HACK
        // The keyguard view will have set up window flags in onFinishInflate before we set
        // the view mediator callback. Make sure it knows the correct IME state.
        if (mViewMediatorCallback != null) {
            KeyguardPasswordView kpv = (KeyguardPasswordView) mKeyguardView.findViewById(
                    R.id.keyguard_password_view);

            if (kpv != null) {
                mViewMediatorCallback.setNeedsInput(kpv.needsInput());
            }
        }

        /// Extract this block to a single function
        updateKeyguardViewFromOptions(options);
    }

    public void updateUserActivityTimeout() {
        updateUserActivityTimeoutInWindowLayoutParams();
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    private void updateUserActivityTimeoutInWindowLayoutParams() {
        // Use the user activity timeout requested by the keyguard view, if any.
        if (mKeyguardView != null) {
            long timeout = mKeyguardView.getUserActivityTimeout();
            if (timeout >= 0) {
                mWindowLayoutParams.userActivityTimeout = timeout;
                return;
            }
        }

        // Otherwise, use the default timeout.
        mWindowLayoutParams.userActivityTimeout = KeyguardViewMediator.AWAKE_INTERVAL_DEFAULT_MS;
    }

    private void maybeEnableScreenRotation(boolean enableScreenRotation) {
        // TODO: move this outside
        if (enableScreenRotation) {
            if (DEBUG) KeyguardUtils.xlogD(TAG, "Rotation sensor for lock screen On!");
            mWindowLayoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_USER;
        } else {
            if (DEBUG) KeyguardUtils.xlogD(TAG, "Rotation sensor for lock screen Off!");
            mWindowLayoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
        }
        mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
    }

    public void setNeedsInput(boolean needsInput) {
        mNeedsInput = needsInput;
        if (mWindowLayoutParams != null) {
            if (needsInput) {
                mWindowLayoutParams.flags &=
                    ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                mWindowLayoutParams.flags |=
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }

            try {
                mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
            } catch (java.lang.IllegalArgumentException e) {
                // TODO: Ensure this method isn't called on views that are changing...
                Log.w(TAG,"Can't update input method on " + mKeyguardHost + " window not attached");
            }
        }
    }

    /**
     * Reset the state of the view.
     */
    public synchronized void reset(Bundle options) {
        // User might have switched, check if we need to go back to keyguard
        // TODO: It's preferable to stay and show the correct lockscreen or unlock if none
        /// M: Avoid remove/add view when mKeyguardView is not null
        /// M: Also check if Dm lock is enabled, if dm lock is on, we should also force reset
        boolean forceReCreate = false;
        if (options != null) {
            if (options.getBoolean(KeyguardViewMediator.RESET_FOR_ANTITHEFT_LOCK)
                || options.getBoolean(LockPatternUtils.KEYGUARD_SHOW_USER_SWITCHER)) {
                forceReCreate = true;
            }
        }
        if (DEBUG) KeyguardUtils.xlogD(TAG, "reset() mKeyguardView=" + mKeyguardView + ", forceReCreate=" + forceReCreate);
        if (!forceReCreate && mKeyguardView != null) {
            mKeyguardView.reset();
            updateKeyguardViewFromOptions(options);
        } else {
            maybeCreateKeyguardLocked(shouldEnableScreenRotation(), true, options);
        }
    }

    public synchronized void onScreenTurnedOff() {
        if (DEBUG) KeyguardUtils.xlogD(TAG, "onScreenTurnedOff()");
        mScreenOn = false;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOff();
        }
    }

    public synchronized void onScreenTurnedOn(
            final KeyguardViewManager.ShowListener showListener) {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOn()");
        mScreenOn = true;
        if (mKeyguardView != null) {
            mKeyguardView.onScreenTurnedOn();

            if (mCreateOrientation != mContext.getResources().getConfiguration().orientation) {
                if (DEBUG) KeyguardUtils.xlogD(TAG, "onScreenTurnedOn orientation is different, recreate it. mCreateOrientation="+mCreateOrientation
                    +", newConfig="+mContext.getResources().getConfiguration().orientation);
                maybeCreateKeyguardLocked(shouldEnableScreenRotation(), true, null);
            }
            // Caller should wait for this window to be shown before turning
            // on the screen.
            if (showListener != null) {
                if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                    // Keyguard may be in the process of being shown, but not yet
                    // updated with the window manager...  give it a chance to do so.
                    if (DEBUG) KeyguardUtils.xlogD(TAG, "onScreenTurnedOn mKeyguardView visible, post runnable");
                    mKeyguardHost.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mKeyguardHost.getVisibility() == View.VISIBLE) {
                                if (DEBUG) KeyguardUtils.xlogD(TAG, "onScreenTurnedOn mKeyguardView visible, showListener.onShown");
                                showListener.onShown(mKeyguardHost.getWindowToken());
                            } else {
                                if (DEBUG) KeyguardUtils.xlogD(TAG, "onScreenTurnedOn mKeyguardView !visible showListener.onShown");
                                showListener.onShown(null);
                            }
                        }
                    });
                } else {
                    if (DEBUG) KeyguardUtils.xlogD(TAG, "onScreenTurnedOn else mKeyguardView !visible showListener.onShown");
                    showListener.onShown(null);
                }
            }
        } else if (showListener != null) {
            KeyguardUtils.xlogD(TAG, "onScreenTurnedOn mKeyguardView=null showListener.onShown");
            showListener.onShown(null);
        }
    }

    public synchronized void verifyUnlock() {
        if (DEBUG) KeyguardUtils.xlogD(TAG, "verifyUnlock()");
        show(null);
        mKeyguardView.verifyUnlock();
    }

    /**
     * A key has woken the device.  We use this to potentially adjust the state
     * of the lock screen based on the key.
     *
     * The 'Tq' suffix is per the documentation in {@link android.view.WindowManagerPolicy}.
     * Be sure not to take any action that takes a long time; any significant
     * action should be posted to a handler.
     *
     * @param keyCode The wake key.  May be {@link KeyEvent#KEYCODE_UNKNOWN} if waking
     * for a reason other than a key press.
     */
    public boolean wakeWhenReadyTq(int keyCode) {
        if (DEBUG) KeyguardUtils.xlogD(TAG, "wakeWhenReady(" + keyCode + ")");
        if (mKeyguardView != null) {
            mKeyguardView.wakeWhenReadyTq(keyCode);
            return true;
        }
        KeyguardUtils.xlogD(TAG, "mKeyguardView is null in wakeWhenReadyTq");
        return false;
    }

    /**
     * Hides the keyguard view
     */
    public synchronized void hide() {
        if (DEBUG) KeyguardUtils.xlogD(TAG, "hide() mKeyguardView=" + mKeyguardView);

        if (mKeyguardHost != null) {
            mKeyguardHost.setVisibility(View.GONE);

            // We really only want to preserve keyguard state for configuration changes. Hence
            // we should clear state of widgets (e.g. Music) when we hide keyguard so it can
            // start with a fresh state when we return.
            mStateContainer.clear();

            // Don't do this right away, so we can let the view continue to animate
            // as it goes away.
            if (mKeyguardView != null) {
                final KeyguardViewBase lastView = mKeyguardView;
                mKeyguardView = null;
                mKeyguardHost.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (KeyguardViewManager.this) {
                            if (DEBUG) KeyguardUtils.xlogD(TAG, "hide() runnable lastView=" + lastView);
                            lastView.cleanUp();
                            mKeyguardHost.removeView(lastView);
                        }
                    }
                }, 500);
            }
        }
    }

    /**
     * Dismisses the keyguard by going to the next screen or making it gone.
     */
    public synchronized void dismiss() {
        KeyguardUtils.xlogD(TAG, "dismiss mScreenOn=" + mScreenOn);
        if (mScreenOn) {
            mKeyguardView.dismiss();
        }
    }

    /**
     * @return Whether the keyguard is showing
     */
    public synchronized boolean isShowing() {
        return (mKeyguardHost != null && mKeyguardHost.getVisibility() == View.VISIBLE);
    }

    public void showAssistant() {
        if (mKeyguardView != null) {
            mKeyguardView.showAssistant();
        }
    }

    /**
     * M: Update layout for KeyguardView, for DM lock/unlock to show/hide statsubar
     * 
     * @param dmLock
     */
    public void reLayoutScreen(boolean dmLock) {
        if (mWindowLayoutParams != null) {
            KeyguardUtils.xlogD(TAG, "reLayoutScreen, dmLock=" + dmLock + ", isAlarmBoot=" + KeyguardUpdateMonitor.isAlarmBoot());
            if (dmLock) {
                mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
                mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
            } else if (KeyguardUpdateMonitor.isAlarmBoot()) {
                mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
            } else {
                mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
                mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
            }
            mViewManager.updateViewLayout(mKeyguardHost, mWindowLayoutParams);
        }
    }

    private void updateKeyguardViewFromOptions(Bundle options) {
        if (options != null) {
            int widgetToShow = options.getInt(LockPatternUtils.KEYGUARD_SHOW_APPWIDGET,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            if (widgetToShow != AppWidgetManager.INVALID_APPWIDGET_ID) {
                mKeyguardView.goToWidget(widgetToShow);
            }
        }
    }    

    /// M: add for ipo shut down update process
    public void ipoShutDownUpdate() {
        if (null != mKeyguardView) {
            mKeyguardView.ipoShutDownUpdate();
        }
    }

    /**
     * M: add for power-off alarm
     *
     * @return
     */
    public boolean isAlarmUnlockScreen() {
        if (null != mKeyguardView) {
            return mKeyguardView.isAlarmUnlockScreen();
        }
        return false;
    }
    
    // M: Save current orientation, so that we will only recreate views when orientation changed
    private int mCreateOrientation;
}
