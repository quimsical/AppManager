// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import android.annotation.UserIdInt;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.RemoteException;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import io.github.muntashirakon.AppManager.compat.ApplicationInfoCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.settings.Prefs;

public final class FreezeUtils {
    @IntDef({FREEZE_DISABLE, FREEZE_SUSPEND, FREEZE_HIDE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FreezeType {
    }

    public static final int FREEZE_DISABLE = 1;
    public static final int FREEZE_SUSPEND = 1 << 1;
    public static final int FREEZE_HIDE = 1 << 2;

    public static boolean isFrozen(@NonNull ApplicationInfo applicationInfo) {
        // An app is frozen if one of the following operations return true: suspend, disable or hide
        if (!applicationInfo.enabled) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0) {
            return true;
        }
        return (ApplicationInfoCompat.getPrivateFlags(applicationInfo) & ApplicationInfoCompat.PRIVATE_FLAG_HIDDEN) != 0;
    }

    public static void freeze(@NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        freeze(packageName, userId, Prefs.Blocking.getDefaultFreezingMethod());
    }

    private static void freeze(@NonNull String packageName, @UserIdInt int userId, @FreezeType int freezeType)
            throws RemoteException {
        if (freezeType == FREEZE_HIDE) {
            PackageManagerCompat.hidePackage(packageName, userId, true);
        } else if (freezeType == FREEZE_SUSPEND && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, true);
        } else {
            PackageManagerCompat.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0, userId);
        }
    }

    public static void unfreeze(@NonNull String packageName, @UserIdInt int userId) throws RemoteException {
        // Ignore checking preference, unfreeze for all types
        if (PackageManagerCompat.isPackageHidden(packageName, userId)) {
            PackageManagerCompat.hidePackage(packageName, userId, false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && PackageManagerCompat.isPackageSuspended(packageName, userId)) {
            PackageManagerCompat.suspendPackages(new String[]{packageName}, userId, false);
        }
        if (PackageManagerCompat.getApplicationEnabledSetting(packageName, userId) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            PackageManagerCompat.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, userId);
        }
    }
}
