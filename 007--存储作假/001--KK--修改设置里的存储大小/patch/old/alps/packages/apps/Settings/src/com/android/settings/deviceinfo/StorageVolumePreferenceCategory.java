/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.settings.deviceinfo;

import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.UserManager;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.text.format.Formatter;
import android.util.Log;

import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.deviceinfo.StorageMeasurement.MeasurementDetails;
import com.android.settings.deviceinfo.StorageMeasurement.MeasurementReceiver;
import com.google.android.collect.Lists;
import com.mediatek.common.featureoption.FeatureOption;
import com.mediatek.xlog.Xlog;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class StorageVolumePreferenceCategory extends PreferenceCategory {

    private static final String TAG = "StorageVolumePreferenceCategory";

    public static final String KEY_CACHE = "cache";

    private static final int ORDER_USAGE_BAR = -2;
    private static final int ORDER_STORAGE_LOW = -1;
    private static final String USB_STORAGE_PATH = "/mnt/usbotg";
    private static final String VOLUME_IS_SWAPPING = "1";

    /** Physical volume being measured, or {@code null} for internal. */
    private StorageVolume mVolume;
    private final StorageMeasurement mMeasure;

    private final Resources mResources;
    private final StorageManager mStorageManager;
    private final UserManager mUserManager;

    private UsageBarPreference mUsageBarPreference;
    private Preference mMountTogglePreference;
    private Preference mFormatPreference;
    private Preference mStorageLow;

    private StorageItemPreference mItemTotal;
    private StorageItemPreference mItemAvailable;
    private StorageItemPreference mItemApps;
    private StorageItemPreference mItemDcim;
    private StorageItemPreference mItemMusic;
    private StorageItemPreference mItemDownloads;
    private StorageItemPreference mItemCache;
    private StorageItemPreference mItemMisc;
    private List<StorageItemPreference> mItemUsers = Lists.newArrayList();

    private boolean mUsbConnected;
    private String mUsbFunction;

    private long mTotalSize;


    /// M: @{
    private boolean mIsUsbStorage;

    private String mVolumeDescription;

    private boolean mIsInternalSD;

    // M: add a valiable to judge whether this is the primary card
    private boolean mIsPrimary;
    /// M: @}


    private static final int MSG_UI_UPDATE_APPROXIMATE = 1;
    private static final int MSG_UI_UPDATE_DETAILS = 2;

    private Handler mUpdateHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UI_UPDATE_APPROXIMATE:
                final long[] size = (long[]) msg.obj;
                updateApproximate(size[0], size[1]);
                break;
            case MSG_UI_UPDATE_DETAILS:
                final MeasurementDetails details = (MeasurementDetails) msg.obj;
                updateDetails(details);
                break;
            }
        }
    };

    /**
     * Build category to summarize internal storage, including any emulated
     * {@link StorageVolume}.
     */
    public static StorageVolumePreferenceCategory buildForInternal(Context context) {
        return new StorageVolumePreferenceCategory(context, null);
    }

    /**
     * Build category to summarize specific physical {@link StorageVolume}.
     */
    public static StorageVolumePreferenceCategory buildForPhysical(
            Context context, StorageVolume volume) {
        return new StorageVolumePreferenceCategory(context, volume);
    }

    public StorageVolumePreferenceCategory(Context context, StorageVolume volume) {
        super(context);

        mVolume = volume;
        mMeasure = StorageMeasurement.getInstance(context, volume);

        mResources = context.getResources();
        mStorageManager = StorageManager.from(context);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);

        /** M: Named shared SD volume name as phone storage */
        if (volume == null) {
            if (FeatureOption.MTK_SHARED_SDCARD && !FeatureOption.MTK_2SDCARD_SWAP) {
                setTitle(context.getText(com.android.internal.R.string.storage_phone));
            } else {
                setTitle(context.getText(R.string.internal_storage));
            }
        } else {
            setTitle(volume.getDescription(context));
        }

        if (volume != null) {
            mIsUsbStorage = USB_STORAGE_PATH.equals(volume.getPath());
            mVolumeDescription = volume.getDescription(context);
            mIsInternalSD = !volume.isRemovable();
            Xlog.d(TAG, "Storage description:" + mVolumeDescription
                    + ", isEmulated: " + volume.isEmulated() + ", isRemovable "
                    + volume.isRemovable());
        }
    }

    private StorageItemPreference buildItem(int titleRes, int colorRes) {
        return new StorageItemPreference(getContext(), titleRes, colorRes);
    }

    public void init() {
        final Context context = getContext();

        final UserInfo currentUser;
        try {
            currentUser = ActivityManagerNative.getDefault().getCurrentUser();
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to get current user");
        }

        final List<UserInfo> otherUsers = getUsersExcluding(currentUser);
        final boolean showUsers = mVolume == null && otherUsers.size() > 0;

        mUsageBarPreference = new UsageBarPreference(context);
        mUsageBarPreference.setOrder(ORDER_USAGE_BAR);
        addPreference(mUsageBarPreference);

        mItemTotal = buildItem(R.string.memory_size, 0);
        mItemAvailable = buildItem(R.string.memory_available, R.color.memory_avail);
        addPreference(mItemTotal);
        addPreference(mItemAvailable);

        mItemApps = buildItem(R.string.memory_apps_usage, R.color.memory_apps_usage);
        mItemDcim = buildItem(R.string.memory_dcim_usage, R.color.memory_dcim);
        mItemMusic = buildItem(R.string.memory_music_usage, R.color.memory_music);
        mItemDownloads = buildItem(R.string.memory_downloads_usage, R.color.memory_downloads);
        mItemCache = buildItem(R.string.memory_media_cache_usage, R.color.memory_cache);
        mItemMisc = buildItem(R.string.memory_media_misc_usage, R.color.memory_misc);

        mItemCache.setKey(KEY_CACHE);

        final boolean showDetails = mVolume == null || mVolume.isPrimary();
        if (showDetails) {
            if (showUsers) {
                addPreference(new PreferenceHeader(context, currentUser.name));
            }

            addPreference(mItemApps);
            addPreference(mItemDcim);
            addPreference(mItemMusic);
            addPreference(mItemDownloads);
            addPreference(mItemCache);
            addPreference(mItemMisc);

            if (showUsers) {
                addPreference(new PreferenceHeader(context, R.string.storage_other_users));

                int count = 0;
                for (UserInfo info : otherUsers) {
                    final int colorRes = count++ % 2 == 0 ? R.color.memory_user_light
                            : R.color.memory_user_dark;
                    final StorageItemPreference userPref = new StorageItemPreference(
                            getContext(), info.name, colorRes, info.id);
                    mItemUsers.add(userPref);
                    addPreference(userPref);
                }
            }
        }

        mMountTogglePreference = new Preference(context);
        mMountTogglePreference.setTitle(getVolumeString(R.string.sd_eject,
                mVolumeDescription));
        mMountTogglePreference.setSummary(getVolumeString(
                R.string.sd_eject_summary, mVolumeDescription));
        addPreference(mMountTogglePreference);

        // Only allow formatting of primary physical storage
        // TODO: enable for non-primary volumes once MTP is fixed
        final boolean allowFormat = mVolume != null && !mVolume.isEmulated();
        if (allowFormat) {
            mFormatPreference = new Preference(getContext());
            if (mIsUsbStorage) {
                mFormatPreference.setTitle(getVolumeString(R.string.sd_format,
                        mResources.getString(R.string.usb_ums_title)));
                mFormatPreference.setSummary(getVolumeString(
                        R.string.sd_format_summary,
                        mResources.getString(R.string.usb_ums_title)));
            } else {
                // / }@
                mFormatPreference.setTitle(getVolumeString(R.string.sd_format,
                        mVolumeDescription));
                mFormatPreference.setSummary(getVolumeString(
                        R.string.sd_format_summary, mVolumeDescription));
            }
            addPreference(mFormatPreference);
        }

        updateLowStoragePreference();
    }

    public StorageVolume getStorageVolume() {
        return mVolume;
    }

    public void setStorageVolume(StorageVolume volume) {
        mVolume = volume;
    }

    public void updateStorageVolumePrefCategory() {
        Xlog.d(TAG, "sd swap ---- updateStorageVolumePrefCategory");
        // update title
        setTitle(mVolume != null ? mVolume
                .getDescription(getContext()) : mResources
                .getText(R.string.internal_storage));

        // re-measure
        measure();

        // update volume description
        if (mVolume != null) {
            mVolumeDescription = mVolume.getDescription(getContext());
            Xlog.d(TAG, "mVolumeDescription is " + mVolumeDescription);
            mIsInternalSD = !mVolume.isRemovable();
            Xlog.d(TAG, "mIsInternalSD is " + mIsInternalSD);
        }

        //update LowStoragePreference
        if (mStorageLow != null) {
            removePreference(mStorageLow);
            mStorageLow = null;
        }
        updateLowStoragePreference();

        //update mount/unmount preference (decide whether need to add/re)
        final boolean isRemovable = mVolume != null ? mVolume.isRemovable() : false;
        if (isRemovable) {
            if (mMountTogglePreference == null) {
                mMountTogglePreference = new Preference(getContext());
                mMountTogglePreference.setTitle(getVolumeString(R.string.sd_eject,
                        mVolumeDescription));
                mMountTogglePreference.setSummary(getVolumeString(
                        R.string.sd_eject_summary, mVolumeDescription));
                addPreference(mMountTogglePreference);
            }
        }

        // update Format pref
        final boolean allowFormat = mVolume != null && !mVolume.isEmulated();
        if (allowFormat) {
            if (mFormatPreference == null) {
                mFormatPreference = new Preference(getContext());
            }
            mFormatPreference.setTitle(getVolumeString(R.string.sd_format,
                    mVolumeDescription));
            mFormatPreference.setSummary(getVolumeString(
                    R.string.sd_format_summary, mVolumeDescription));
        } else {
            if (mFormatPreference != null) {
                removePreference(mFormatPreference);
                mFormatPreference = null;
            }
        }

        // update the mount/unmount pref title and summary according to the sd state
        updatePreferencesFromState();
    }

    // / M: only if it is internal storage or In sd share, it is a primary
    // card, add a low storage
    private void updateLowStoragePreference() {
        if (mVolume == null
                || (Utils.isSomeStorageEmulated() && mIsInternalSD)) {
            final IPackageManager pm = ActivityThread.getPackageManager();
            try {
                if (pm.isStorageLow()) {
                    mStorageLow = new Preference(getContext());
                    mStorageLow.setOrder(ORDER_STORAGE_LOW);
                    mStorageLow.setTitle(R.string.storage_low_title);
                    mStorageLow.setSummary(R.string.storage_low_summary);
                    addPreference(mStorageLow);
                } else if (mStorageLow != null) {
                    removePreference(mStorageLow);
                    mStorageLow = null;
                }
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Successive mounts can change the list of visible preferences. This makes
     * sure all preferences are visible and displayed in the right order.
     */
    private void resetPreferences() {

        if (mUsageBarPreference != null) {
            removePreference(mUsageBarPreference);
        }
        if (mItemTotal != null) {
            removePreference(mItemTotal);
        }
        if (mItemAvailable != null) {
            removePreference(mItemAvailable);
        }
        if (mItemApps != null) {
            removePreference(mItemApps);
        }
        if (mItemDcim != null) {
            removePreference(mItemDcim);
        }
        if (mItemMusic != null) {
            removePreference(mItemMusic);
        }
        if (mItemDownloads != null) {
            removePreference(mItemDownloads);
        }
        if (mItemCache != null) {
            removePreference(mItemCache);
        }
        if (mItemMisc != null) {
            removePreference(mItemMisc);
        }

        removePreference(mMountTogglePreference);
        if (mFormatPreference != null) {
            removePreference(mFormatPreference);
        }

        addPreference(mUsageBarPreference);

        addPreference(mItemTotal);
        addPreference(mItemAvailable);

        addPreference(mItemApps);
        addPreference(mItemDcim);
        addPreference(mItemMusic);
        addPreference(mItemDownloads);
        addPreference(mItemCache);
        addPreference(mItemMisc);

        addPreference(mMountTogglePreference);
        if (mFormatPreference != null) {
            addPreference(mFormatPreference);
        }

        mMountTogglePreference.setEnabled(true);
    }

    private void updatePreferencesFromState() {

        // Only update for physical volumes
        if (mVolume == null) {
            if (mMountTogglePreference != null) {
                removePreference(mMountTogglePreference);
            }
            return;
        }

        resetPreferences();

        final String state = mStorageManager.getVolumeState(mVolume.getPath());
        Log.d(TAG, "updatePreferencesFromState, state is " + state);

        if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            mItemAvailable.setTitle(R.string.memory_available_read_only);
            if (mFormatPreference != null) {
                removePreference(mFormatPreference);
            }
        } else {
            mItemAvailable.setTitle(R.string.memory_available);
        }

        if (!mVolume.isRemovable() && !Environment.MEDIA_UNMOUNTED.equals(state)) {
            // This device has built-in storage that is not removable.
            // There is no reason for the user to unmount it.
            removePreference(mMountTogglePreference);
        }

        if (Environment.MEDIA_MOUNTED.equals(state)
                || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            mMountTogglePreference.setEnabled(true);
            if (mIsUsbStorage) {
                mMountTogglePreference.setTitle(mResources
                        .getString(R.string.sd_eject_usbstorage));
                mMountTogglePreference.setSummary(mResources
                        .getString(R.string.sd_eject_usbstorage_summary));
            } else {
                mMountTogglePreference.setTitle(getVolumeString(
                        R.string.sd_eject, mVolumeDescription));
                mMountTogglePreference.setSummary(getVolumeString(
                        R.string.sd_eject_summary, mVolumeDescription));
            }
        } else {
            if (Environment.MEDIA_UNMOUNTED.equals(state)
                    || Environment.MEDIA_NOFS.equals(state)
                    || Environment.MEDIA_UNMOUNTABLE.equals(state)) {
                /** M: Swap UI protection, disable mountToggle preference when show mount
                 *     button if it is in swapping state. CR ALPS00440062{ */
                if (FeatureOption.MTK_2SDCARD_SWAP) {
                    boolean isSwapping = mIsUsbStorage ? false : getSwappingState();
                    mMountTogglePreference.setEnabled(!isSwapping);
                    /** @} */
                } else {
                    mMountTogglePreference.setEnabled(true);
                }
                if (mIsUsbStorage) {
                    mMountTogglePreference.setTitle(mResources
                            .getString(R.string.sd_mount_usbstorage));
                    mMountTogglePreference.setSummary(mResources
                            .getString(R.string.sd_mount_summary));

                } else {
                    mMountTogglePreference.setTitle(getVolumeString(
                            R.string.sd_mount, mVolumeDescription));
                    mMountTogglePreference.setSummary(getVolumeString(
                            R.string.sd_mount_summary, mVolumeDescription));
                }

            } else {
                mMountTogglePreference.setEnabled(false);
                if (mIsUsbStorage) {
                    mMountTogglePreference.setTitle(mResources
                            .getString(R.string.sd_mount_usbstorage));
                    mMountTogglePreference.setSummary(mResources
                            .getString(R.string.sd_insert_usb_summary));

                } else {
                    mMountTogglePreference.setTitle(getVolumeString(
                            R.string.sd_mount, mVolumeDescription));
                    mMountTogglePreference.setSummary(getVolumeString(
                            R.string.sd_insert_summary, mVolumeDescription));
                }
            }
            removePreference(mUsageBarPreference);
            removePreference(mItemTotal);
            removePreference(mItemAvailable);
            if (mFormatPreference != null) {
                removePreference(mFormatPreference);
            }
        }

        if (mUsbConnected && (UsbManager.USB_FUNCTION_MTP.equals(mUsbFunction) ||
                UsbManager.USB_FUNCTION_PTP.equals(mUsbFunction))) {
            mMountTogglePreference.setEnabled(false);
            if (Environment.MEDIA_MOUNTED.equals(state)
                    || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                mMountTogglePreference.setSummary(
                        mResources.getString(R.string.mtp_ptp_mode_summary));
            }

            if (mFormatPreference != null) {
                mFormatPreference.setEnabled(false);
                mFormatPreference.setSummary(mResources.getString(R.string.mtp_ptp_mode_summary));
            }
        } else if (mFormatPreference != null) {
            mFormatPreference.setEnabled(true);
            mFormatPreference.setSummary(getVolumeString(
                    R.string.sd_format_summary, mVolumeDescription));
        }
    }

    public void updateApproximate(long totalSize, long availSize) {
        Xlog.d(TAG, mVolumeDescription + " : total size is " + totalSize
                + ", avail size is " + availSize);
        mItemTotal.setSummary(formatSize(totalSize));
        mItemAvailable.setSummary(formatSize(availSize));

        mTotalSize = totalSize;

        final long usedSize = totalSize - availSize;

        mUsageBarPreference.clear();
        mUsageBarPreference.addEntry(0, usedSize / (float) totalSize, android.graphics.Color.GRAY);
        mUsageBarPreference.commit();

         updatePreferencesFromState();
    }

    private static long totalValues(HashMap<String, Long> map, String... keys) {
        long total = 0;
        for (String key : keys) {
            total += map.get(key);
        }
        return total;
    }

    public void updateDetails(MeasurementDetails details) {
        if (mVolume != null) {
            Xlog.d(TAG,
                    "updateDetails, " + mVolume.getDescription(getContext())
                            + ", isPrimary is " + mVolume.isPrimary());
        } else {
            Xlog.d(TAG, "updateDetails, mVolume is null");
        }
        final boolean showDetails = mVolume == null || mVolume.isPrimary();
        if (!showDetails) {
            if (mItemApps != null) {
                removePreference(mItemApps);
            }
            if (mItemDcim != null) {
                removePreference(mItemDcim);
            }
            if (mItemMusic != null) {
                removePreference(mItemMusic);
            }
            if (mItemDownloads != null) {
                removePreference(mItemDownloads);
            }
            if (mItemCache != null) {
                removePreference(mItemCache);
            }
            if (mItemMisc != null) {
                removePreference(mItemMisc);
            }
            return;
        }

        // Count caches as available space, since system manages them
        mItemTotal.setSummary(formatSize(details.totalSize));
        mItemAvailable.setSummary(formatSize(details.availSize));

        mUsageBarPreference.clear();

        updatePreference(mItemApps, details.appsSize);

        final boolean measureMedia = (mVolume == null && Environment.isExternalStorageEmulated())
        || (mVolume != null && mVolume.isPrimary());
        Xlog.d(TAG, "measureMedia is " + measureMedia);
        if (measureMedia) {
            final long dcimSize = totalValues(details.mediaSize,
                    Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES,
                    Environment.DIRECTORY_PICTURES);
            Xlog.d(TAG, "mDcim size is " + mItemDcim);
            updatePreference(mItemDcim, dcimSize);

            final long musicSize = totalValues(details.mediaSize,
                    Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_ALARMS,
                    Environment.DIRECTORY_NOTIFICATIONS,
                    Environment.DIRECTORY_RINGTONES,
                    Environment.DIRECTORY_PODCASTS);
            Xlog.d(TAG, "music size is " + musicSize);
            updatePreference(mItemMusic, musicSize);

            final long downloadsSize = totalValues(details.mediaSize,
                    Environment.DIRECTORY_DOWNLOADS);
            Xlog.d(TAG, "downloads size is " + downloadsSize);
            updatePreference(mItemDownloads, downloadsSize);
        } else {
            updatePreference(mItemDcim, 0);
            updatePreference(mItemMusic, 0);
            updatePreference(mItemDownloads, 0);
        }

        updatePreference(mItemCache, details.cacheSize);
        updatePreference(mItemMisc, details.miscSize);

        for (StorageItemPreference userPref : mItemUsers) {
            final long userSize = details.usersSize.get(userPref.userHandle);
            updatePreference(userPref, userSize);
        }

        mUsageBarPreference.commit();
    }

    private void updatePreference(StorageItemPreference pref, long size) {
        if (size > 0) {
            pref.setSummary(formatSize(size));
            final int order = pref.getOrder();
            mUsageBarPreference.addEntry(order, size / (float) mTotalSize, pref.color);
        } else {
            removePreference(pref);
        }
    }

    private void measure() {
        mMeasure.invalidate();
        mMeasure.measure();
    }

    public void onResume() {
        Log.d(TAG, "onResume");
        mMeasure.setReceiver(mReceiver);
        measure();
        updatePreferencesFromState();
    }

    public void onStorageStateChanged() {
        Log.d(TAG, "onStorageStateChanged");
        measure();
        updatePreferencesFromState();
    }

    public void onUsbStateChanged(boolean isUsbConnected, String usbFunction) {
        Log.d(TAG, "onUsbStateChanged");
        mUsbConnected = isUsbConnected;
        mUsbFunction = usbFunction;
        measure();
        updatePreferencesFromState();
    }

    public void onMediaScannerFinished() {
        Log.d(TAG, "onMediaScannerFinished");
        measure();
    }

    public void onCacheCleared() {
        measure();
    }

    public void onPause() {
        mMeasure.cleanUp();
    }

    private String formatSize(long size) {
        return Formatter.formatFileSize(getContext(), size);
    }

    private MeasurementReceiver mReceiver = new MeasurementReceiver() {
        @Override
        public void updateApproximate(StorageMeasurement meas, long totalSize, long availSize) {
            mUpdateHandler.obtainMessage(MSG_UI_UPDATE_APPROXIMATE, new long[] {
                    totalSize, availSize }).sendToTarget();
        }

        @Override
        public void updateDetails(StorageMeasurement meas, MeasurementDetails details) {
            mUpdateHandler.obtainMessage(MSG_UI_UPDATE_DETAILS, details).sendToTarget();
        }
    };

    public boolean mountToggleClicked(Preference preference) {
        return preference == mMountTogglePreference;
    }

    public Intent intentForClick(Preference pref) {
        Intent intent = null;

        // TODO The current "delete" story is not fully handled by the
        // respective applications.
        // When it is done, make sure the intent types below are correct.
        // If that cannot be done, remove these intents.
        final String key = pref.getKey();
        if (pref == mFormatPreference) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setClass(getContext(),
                    com.android.settings.MediaFormat.class);
            Bundle bundle = new Bundle();
            bundle.putParcelable("volume", mVolume);
            bundle.putBoolean("IsUsbStorage", mIsUsbStorage);
            intent.putExtras(bundle);
        } else if (pref == mItemApps) {
            intent = new Intent(Intent.ACTION_MANAGE_PACKAGE_STORAGE);
            intent.setClass(
                    getContext(),
                    com.android.settings.Settings.ManageApplicationsActivity.class);
        } else if (pref == mItemDownloads) {
            intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .putExtra(DownloadManager.INTENT_EXTRAS_SORT_BY_SIZE, true);
        } else if (pref == mItemMusic) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("audio/mp3");
        } else if (pref == mItemDcim) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            // TODO Create a Videos category, type =
            // vnd.android.cursor.dir/video
            intent.setType("vnd.android.cursor.dir/image");
        } else if (pref == mItemMisc) {
            Context context = getContext().getApplicationContext();
            intent = new Intent(context, MiscFilesHandler.class);
            intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, mVolume);
        }

        return intent;
    }

    public static class PreferenceHeader extends Preference {
        public PreferenceHeader(Context context, int titleRes) {
            super(context, null, com.android.internal.R.attr.preferenceCategoryStyle);
            setTitle(titleRes);
        }

        public PreferenceHeader(Context context, CharSequence title) {
            super(context, null, com.android.internal.R.attr.preferenceCategoryStyle);
            setTitle(title);
        }

        @Override
        public boolean isEnabled() {
            return false;
        }
    }

    /**
     * Return list of other users, excluding the current user.
     */
    private List<UserInfo> getUsersExcluding(UserInfo excluding) {
        final List<UserInfo> users = mUserManager.getUsers();
        final Iterator<UserInfo> i = users.iterator();
        while (i.hasNext()) {
            if (i.next().id == excluding.id) {
                i.remove();
            }
        }
        return users;
    }

    private String getVolumeString(int stringId, String description) {

        if (description == null || (!mIsInternalSD && !mIsUsbStorage)) {
            return mResources.getString(stringId);
        }
        //SD card string
        String sdCardString = mResources.getString(R.string.sdcard_setting);
        String str = mResources.getString(stringId).replace(sdCardString,
                description);
        // maybe it is in lower case, no replacement try another
        if (str != null && str.equals(mResources.getString(stringId))) {
            sdCardString = sdCardString.toLowerCase();
            // restore to SD
            sdCardString = sdCardString.replace("sd", "SD");
            str = mResources.getString(stringId).replace(sdCardString, description);
        }

        if (str != null && str.equals(mResources.getString(stringId))) {
            str = mResources.getString(stringId).replace("SD",
                    description);
            Xlog.d(TAG, "Can not replace SD card, Replace SD, str is " + str);
        }
        Locale tr = Locale.getDefault();
        // For chinese there is no space
        if (tr.getCountry().equals(Locale.CHINA.getCountry())
                || tr.getCountry().equals(Locale.TAIWAN.getCountry())) {
            // delete the space
            str = str.replace(" " + description, description);
        }
        return str;
    }

    private boolean getSwappingState() {
        String propertyStr = "0";
        boolean isSwapping = false ;
        try {
            propertyStr = SystemProperties.get("is_swap_ongoing");
            Log.i(TAG, "getSwappingState from Property path=" + propertyStr);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "IllegalArgumentException when get is_swapping:" + e);
        }
        isSwapping = VOLUME_IS_SWAPPING.equals(propertyStr);
        Log.i(TAG, "isSwapping = " + isSwapping);
        return isSwapping;
    }
}
