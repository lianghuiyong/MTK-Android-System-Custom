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

package com.android.launcher3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.launcher3.R;

import com.mediatek.launcher3.ext.LauncherLog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Stores the list of all applications for the all apps view.
 */
class AllAppsList {
    private static final String TAG = "AllAppsList";
    
    /// M: add for top packages.
    private static final String TAG_TOPPACKAGES = "toppackages";
    private static final String WIFI_SETTINGPKGNAME = "com.android.settings";
    private static final String WIFI_SETTINGCLASSNAME = "com.android.settings.Settings$WifiSettingsActivity";

    private static final boolean DEBUG_LOADERS_REORDER = false;
    public static final int DEFAULT_APPLICATIONS_NUMBER = 42;

    /** The list off all apps. */
    public ArrayList<AppInfo> data =
            new ArrayList<AppInfo>(DEFAULT_APPLICATIONS_NUMBER);
    /** The list of apps that have been added since the last notify() call. */
    public ArrayList<AppInfo> added =
            new ArrayList<AppInfo>(DEFAULT_APPLICATIONS_NUMBER);
    /** The list of apps that have been removed since the last notify() call. */
    public ArrayList<AppInfo> removed = new ArrayList<AppInfo>();
    /** The list of apps that have been modified since the last notify() call. */
    public ArrayList<AppInfo> modified = new ArrayList<AppInfo>();

    private IconCache mIconCache;

    private AppFilter mAppFilter;

    /// M: add for top packages.
    static ArrayList<TopPackage> sTopPackages = null;

    static class TopPackage {
        public TopPackage(String pkgName, String clsName, int index) {
            packageName = pkgName;
            className = clsName;
            order = index;
        }

        String packageName;
        String className;
        int order;
    }

    /**
     * Boring constructor.
     */
    public AllAppsList(IconCache iconCache, AppFilter appFilter) {
        mIconCache = iconCache;
        mAppFilter = appFilter;
    }

    /**
     * Add the supplied ApplicationInfo objects to the list, and enqueue it into the
     * list to broadcast when notify() is called.
     *
     * If the app is already in the list, doesn't add it.
     */
    public void add(AppInfo info) {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "Add application in app list: app = " + info.componentName
                    + ", title = " + info.title);
        }

        if (mAppFilter != null && !mAppFilter.shouldShowApp(info.componentName)) {
            return;
        }
        if (findActivity(data, info.componentName)) {
            LauncherLog.i(TAG, "Application " + info + " already exists in app list, app = " + info);
            return;
        }
        data.add(info);
        added.add(info);
    }

    public void clear() {
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "clear all data in app list: app size = " + data.size());
        }

        data.clear();
        // TODO: do we clear these too?
        added.clear();
        removed.clear();
        modified.clear();
    }

    public int size() {
        return data.size();
    }

    public AppInfo get(int index) {
        return data.get(index);
    }

    /**
     * Add the icons for the supplied apk called packageName.
     */
    public void addPackage(Context context, String packageName) {
        final List<ResolveInfo> matches = findActivitiesForPackage(context, packageName);

        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "addPackage: packageName = " + packageName + ", matches = " + matches.size());
        }

        if (matches.size() > 0) {
            for (ResolveInfo info : matches) {
                add(new AppInfo(context.getPackageManager(), info, mIconCache, null));
            }
        }
    }

    /**
     * Remove the apps for the given apk identified by packageName.
     */
    public void removePackage(String packageName) {
        final List<AppInfo> data = this.data;
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "removePackage: packageName = " + packageName + ", data size = " + data.size());
        }

        for (int i = data.size() - 1; i >= 0; i--) {
            AppInfo info = data.get(i);
            final ComponentName component = info.intent.getComponent();
            if (packageName.equals(component.getPackageName())) {
                removed.add(info);
                data.remove(i);
            }
        }
        // This is more aggressive than it needs to be.
        mIconCache.flush();
    }

    /**
     * Add and remove icons for this package which has been updated.
     */
    public void updatePackage(Context context, String packageName) {
        final List<ResolveInfo> matches = findActivitiesForPackage(context, packageName);
        if (LauncherLog.DEBUG) {
            LauncherLog.d(TAG, "updatePackage: packageName = " + packageName + ", matches = " + matches.size());
        }

        if (matches.size() > 0) {
            // Find disabled/removed activities and remove them from data and add them
            // to the removed list.
            for (int i = data.size() - 1; i >= 0; i--) {
                final AppInfo applicationInfo = data.get(i);
                final ComponentName component = applicationInfo.intent.getComponent();
                if (packageName.equals(component.getPackageName())) {
                    if (!findActivity(matches, component)) {
                        removed.add(applicationInfo);
                        mIconCache.remove(component);
                        data.remove(i);
                    }
                }
            }

            // Find enabled activities and add them to the adapter
            // Also updates existing activities with new labels/icons
            int count = matches.size();
            for (int i = 0; i < count; i++) {
                final ResolveInfo info = matches.get(i);
                final String pkgName = info.activityInfo.applicationInfo.packageName;
                final String className = info.activityInfo.name;

                AppInfo applicationInfo = findApplicationInfoLocked(
                        info.activityInfo.applicationInfo.packageName,
                        info.activityInfo.name);
                if (applicationInfo == null) {
                    add(new AppInfo(context.getPackageManager(), info, mIconCache, null));
                } else {
                    mIconCache.remove(applicationInfo.componentName);
                    mIconCache.getTitleAndIcon(applicationInfo, info, null);
                    modified.add(applicationInfo);
                }
            }
        } else {
            // Remove all data for this package.
            for (int i = data.size() - 1; i >= 0; i--) {
                final AppInfo applicationInfo = data.get(i);
                final ComponentName component = applicationInfo.intent.getComponent();
                if (packageName.equals(component.getPackageName())) {
                    if (LauncherLog.DEBUG) {
                        LauncherLog.d(TAG, "Remove application from launcher: component = " + component);
                    }
                    removed.add(applicationInfo);
                    mIconCache.remove(component);
                    data.remove(i);
                }
            }
        }
    }

    /**
     * Query the package manager for MAIN/LAUNCHER activities in the supplied package.
     */
    // mtk modify to default
    static List<ResolveInfo> findActivitiesForPackage(Context context, String packageName) {
        final PackageManager packageManager = context.getPackageManager();

        final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        mainIntent.setPackage(packageName);

        final List<ResolveInfo> apps = packageManager.queryIntentActivities(mainIntent, 0);
        return apps != null ? apps : new ArrayList<ResolveInfo>();
    }

    /**
     * Returns whether <em>apps</em> contains <em>component</em>.
     */
    // mtk modify to default
    static boolean findActivity(List<ResolveInfo> apps, ComponentName component) {
        final String className = component.getClassName();
        for (ResolveInfo info : apps) {
            final ActivityInfo activityInfo = info.activityInfo;
            if (activityInfo.name.equals(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether <em>apps</em> contains <em>component</em>.
     */
    private static boolean findActivity(ArrayList<AppInfo> apps, ComponentName component) {
        final int N = apps.size();
        for (int i=0; i<N; i++) {
            final AppInfo info = apps.get(i);
            if (info.componentName.equals(component)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find an ApplicationInfo object for the given packageName and className.
     */
    private AppInfo findApplicationInfoLocked(String packageName, String className) {
        for (AppInfo info: data) {
            final ComponentName component = info.intent.getComponent();
            if (packageName.equals(component.getPackageName())
                    && className.equals(component.getClassName())) {
                return info;
            }
        }
        return null;
    }

    /**
     * M: Load the default set of default top packages from an xml file.
     *
     * @param context
     * @return true if load successful.
     */
    static boolean loadTopPackage(final Context context) {
        boolean bRet = false;
        if (sTopPackages != null) {
            return bRet;
        }

        sTopPackages = new ArrayList<TopPackage>();

        try {
            XmlResourceParser parser = context.getResources().getXml(R.xml.default_toppackage);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            XmlUtils.beginDocument(parser, TAG_TOPPACKAGES);

            final int depth = parser.getDepth();

            int type = -1;
            while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && type != XmlPullParser.END_DOCUMENT) {

                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TopPackage);

                sTopPackages.add(new TopPackage(a.getString(R.styleable.TopPackage_topPackageName),
                        a.getString(R.styleable.TopPackage_topClassName), a.getInt(
                                R.styleable.TopPackage_topOrder, 0)));

                LauncherLog.d(TAG, "loadTopPackage: packageName = "
                        + a.getString(R.styleable.TopPackage_topPackageName)
                        + ", className = "
                        + a.getString(R.styleable.TopPackage_topClassName));

                a.recycle();
            }
        } catch (XmlPullParserException e) {
            LauncherLog.w(TAG, "Got XmlPullParserException while parsing toppackage.", e);
        } catch (IOException e) {
            LauncherLog.w(TAG, "Got IOException while parsing toppackage.", e);
        }

        return bRet;
    }

    /**
     * M: Get the index for the given appInfo in the top packages.
     *
     * @param appInfo
     * @return the index of the given appInfo.
     */
    static int getTopPackageIndex(final AppInfo appInfo) {
        int retIndex = -1;
        if (sTopPackages == null || sTopPackages.isEmpty() || appInfo == null) {
            return retIndex;
        }

        for (TopPackage tp : sTopPackages) {
            if (appInfo.componentName.getPackageName().equals(tp.packageName)
                    && appInfo.componentName.getClassName().equals(tp.className)) {
                retIndex = tp.order;
                break;
            }
        }

        return retIndex;
    }

    /**
     * M: Reorder all apps index according to TopPackages.
     */
    void reorderApplist() {
        final long sortTime = DEBUG_LOADERS_REORDER ? SystemClock.uptimeMillis() : 0;

        if (sTopPackages == null || sTopPackages.isEmpty()) {
            return;
        }
        ensureTopPackageOrdered();

        final ArrayList<AppInfo> dataReorder = new ArrayList<AppInfo>(
                DEFAULT_APPLICATIONS_NUMBER);

        for (TopPackage tp : sTopPackages) {
            int loop = 0;
            for (AppInfo ai : added) {
                if (DEBUG_LOADERS_REORDER) {
                    LauncherLog.d(TAG, "reorderApplist: remove loop = " + loop);
                }

                if (ai.componentName.getPackageName().equals(tp.packageName)
                        && ai.componentName.getClassName().equals(tp.className)) {
                    if (DEBUG_LOADERS_REORDER) {
                        LauncherLog.d(TAG, "reorderApplist: remove packageName = "
                                + ai.componentName.getPackageName());
                    }
                    data.remove(ai);
                    dataReorder.add(ai);
                    dumpData();
                    break;
                }
                loop++;
            }
        }

        for (TopPackage tp : sTopPackages) {
            int loop = 0;
            int newIndex = 0;
            for (AppInfo ai : dataReorder) {
                if (DEBUG_LOADERS_REORDER) {
                    LauncherLog.d(TAG, "reorderApplist: added loop = " + loop + ", packageName = "
                            + ai.componentName.getPackageName());
                }

                if (ai.componentName.getPackageName().equals(tp.packageName)
                        && ai.componentName.getClassName().equals(tp.className)) {
                    newIndex = Math.min(Math.max(tp.order, 0), added.size());
                    if (DEBUG_LOADERS_REORDER) {
                        LauncherLog.d(TAG, "reorderApplist: added newIndex = " + newIndex);
                    }
                    /// M: make sure the array list not out of bound
                    if (newIndex < data.size()) {
                        data.add(newIndex, ai);
                    } else {
                        data.add(ai);
                    }
                    dumpData();
                    break;
                }
                loop++;
            }
        }

        if (added.size() == data.size()) {
            added = (ArrayList<AppInfo>) data.clone();
            LauncherLog.d(TAG, "reorderApplist added.size() == data.size()");
        }

        if (DEBUG_LOADERS_REORDER) {
            LauncherLog.d(TAG, "sort and reorder took " + (SystemClock.uptimeMillis() - sortTime) + "ms");
        }
    }

    /**
     * Dump application informations in data.
     */
    void dumpData() {
        int loop2 = 0;
        for (AppInfo ai : data) {
            if (DEBUG_LOADERS_REORDER) {
                LauncherLog.d(TAG, "reorderApplist data loop2 = " + loop2);
                LauncherLog.d(TAG, "reorderApplist data packageName = "
                        + ai.componentName.getPackageName());
            }
            loop2++;
        }
    }

    /*
     * M: ensure the items from top_package.xml is in order,
     * for some special case of top_package.xml will make the arraylist out of bound.
     */

    static void ensureTopPackageOrdered() {
        ArrayList<TopPackage> tpOrderList = new ArrayList<TopPackage>(DEFAULT_APPLICATIONS_NUMBER);
        boolean bFirst = true;
        for (TopPackage tp : sTopPackages) {
            if (bFirst) {
                tpOrderList.add(tp);
                bFirst = false;
            } else {
                for (int i = tpOrderList.size() - 1; i >= 0; i--) {
                    TopPackage tpItor = tpOrderList.get(i);
                    if (0 == i) {
                        if (tp.order < tpOrderList.get(0).order) {
                            tpOrderList.add(0, tp);
                        } else {
                            tpOrderList.add(1, tp);
                        }
                        break;
                    }

                    if ((tp.order < tpOrderList.get(i).order)
                        && (tp.order >= tpOrderList.get(i - 1).order)) {
                        tpOrderList.add(i, tp);
                        break;
                    } else if (tp.order > tpOrderList.get(i).order) {
                        tpOrderList.add(i + 1, tp);
                        break;
                    }
                }
            }
        }

        if (sTopPackages.size() == tpOrderList.size()) {
            sTopPackages = (ArrayList<TopPackage>) tpOrderList.clone();
            tpOrderList = null;
            LauncherLog.d(TAG, "ensureTopPackageOrdered done");
        } else {
            LauncherLog.d(TAG, "some mistake may occur when ensureTopPackageOrdered");
        }
    }

    /**
     * M: add an application, only add to data list, not for added list.
     *
     * @param info
     */
    public void addApp(final AppInfo info) {
        if (LauncherLog.DEBUG_EDIT) {
            LauncherLog.d(TAG, "Add application to data list: app = " + info.componentName);
        }

        if (findActivity(data, info.componentName)) {
            LauncherLog.i(TAG, "The app " + info + " is already exist in data list.");
            return;
        }
        data.add(info);
    }

    /**
     * M: whether the all apps list is empty.
     *
     * @return
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }
}
