// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.collection.SparseArrayCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.AppManager.AppManager;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.explorer.AppExplorerActivity;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerActivity;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.details.manifest.ManifestViewerActivity;
import io.github.muntashirakon.AppManager.intercept.ActivityInterceptor;
import io.github.muntashirakon.AppManager.logcat.LogViewerActivity;
import io.github.muntashirakon.AppManager.scanner.ScannerActivity;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.PermissionUtils;

public class FeatureController {
    @IntDef(flag = true, value = {
            FEAT_INTERCEPTOR,
            FEAT_MANIFEST,
            FEAT_SCANNER,
            FEAT_INSTALLER,
            FEAT_USAGE_ACCESS,
            FEAT_LOG_VIEWER,
            FEAT_INTERNET,
            FEAT_APP_EXPLORER,
            FEAT_APP_INFO,
    })
    public @interface FeatureFlags {
    }

    public static final int FEAT_INTERCEPTOR = 1;
    public static final int FEAT_MANIFEST = 1 << 1;
    public static final int FEAT_SCANNER = 1 << 2;
    public static final int FEAT_INSTALLER = 1 << 3;
    public static final int FEAT_USAGE_ACCESS = 1 << 4;
    public static final int FEAT_LOG_VIEWER = 1 << 5;
    public static final int FEAT_INTERNET = 1 << 6;
    public static final int FEAT_APP_EXPLORER = 1 << 7;
    public static final int FEAT_APP_INFO = 1 << 8;

    @NonNull
    public static FeatureController getInstance() {
        return new FeatureController();
    }

    @FeatureFlags
    public static final List<Integer> featureFlags = new ArrayList<>();

    private static final LinkedHashMap<Integer, Integer> featureFlagsMap = new LinkedHashMap<Integer, Integer>() {
        {
            featureFlags.add(FEAT_INTERCEPTOR);
            put(FEAT_INTERCEPTOR, R.string.interceptor);
            featureFlags.add(FEAT_MANIFEST);
            put(FEAT_MANIFEST, R.string.manifest_viewer);
            featureFlags.add(FEAT_SCANNER);
            put(FEAT_SCANNER, R.string.scanner);
            featureFlags.add(FEAT_INSTALLER);
            put(FEAT_INSTALLER, R.string.package_installer);
            featureFlags.add(FEAT_USAGE_ACCESS);
            put(FEAT_USAGE_ACCESS, R.string.usage_access);
            featureFlags.add(FEAT_LOG_VIEWER);
            put(FEAT_LOG_VIEWER, R.string.log_viewer);
            featureFlags.add(FEAT_APP_EXPLORER);
            put(FEAT_APP_EXPLORER, R.string.app_explorer);
            featureFlags.add(FEAT_APP_INFO);
            put(FEAT_APP_INFO, R.string.app_info);
            featureFlags.add(FEAT_INTERNET);
            put(FEAT_INTERNET, R.string.toggle_internet);
        }
    };

    @NonNull
    public static CharSequence[] getFormattedFlagNames(@NonNull Context context) {
        CharSequence[] flagNames = new CharSequence[featureFlags.size()];
        for (int i = 0; i < flagNames.length; ++i) {
            flagNames[i] = context.getText(Objects.requireNonNull(featureFlagsMap.get(featureFlags.get(i))));
        }
        return flagNames;
    }

    private static final String packageName = AppManager.getContext().getPackageName();
    private static final SparseArrayCompat<ComponentName> componentCache = new SparseArrayCompat<>(4);

    private final PackageManager pm;
    private int flags;

    private FeatureController() {
        pm = AppManager.getContext().getPackageManager();
        flags = AppPref.getInt(AppPref.PrefKey.PREF_ENABLED_FEATURES_INT);
    }

    public int getFlags() {
        return flags;
    }

    public static boolean isInterceptorEnabled() {
        return getInstance().isEnabled(FEAT_INTERCEPTOR);
    }

    public static boolean isManifestEnabled() {
        return getInstance().isEnabled(FEAT_MANIFEST);
    }

    public static boolean isScannerEnabled() {
        return getInstance().isEnabled(FEAT_SCANNER);
    }

    public static boolean isInstallerEnabled() {
        return getInstance().isEnabled(FEAT_INSTALLER);
    }

    public static boolean isUsageAccessEnabled() {
        return getInstance().isEnabled(FEAT_USAGE_ACCESS);
    }

    public static boolean isLogViewerEnabled() {
        return getInstance().isEnabled(FEAT_LOG_VIEWER);
    }

    public static boolean isInternetEnabled() {
        return getInstance().isEnabled(FEAT_INTERNET);
    }

    public static boolean isAppExplorerEnabled() {
        return getInstance().isEnabled(FEAT_APP_EXPLORER);
    }

    public boolean isEnabled(@FeatureFlags int key) {
        ComponentName cn;
        switch (key) {
            case FEAT_INSTALLER:
                cn = getComponentName(key, PackageInstallerActivity.class);
                break;
            case FEAT_INTERCEPTOR:
                cn = getComponentName(key, ActivityInterceptor.class);
                break;
            case FEAT_MANIFEST:
                cn = getComponentName(key, ManifestViewerActivity.class);
                break;
            case FEAT_SCANNER:
                cn = getComponentName(key, ScannerActivity.class);
                break;
            case FEAT_USAGE_ACCESS:
                // Only depends on flag
                return (flags & key) != 0;
            case FEAT_INTERNET:
                return (flags & key) != 0 && PermissionUtils.hasSelfPermission(Manifest.permission.INTERNET);
            case FEAT_LOG_VIEWER:
                cn = getComponentName(key, LogViewerActivity.class);
                break;
            case FEAT_APP_EXPLORER:
                cn = getComponentName(key, AppExplorerActivity.class);
                break;
            case FEAT_APP_INFO:
                cn = getComponentName(key, AppDetailsActivity.ALIAS_APP_INFO);
                break;
            default:
                throw new IllegalArgumentException();
        }
        return isComponentEnabled(cn) && (flags & key) != 0;
    }

    public void modifyState(@FeatureFlags int key, boolean enabled) {
        switch (key) {
            case FEAT_INSTALLER:
                modifyState(key, PackageInstallerActivity.class, enabled);
                break;
            case FEAT_INTERCEPTOR:
                modifyState(key, ActivityInterceptor.class, enabled);
                break;
            case FEAT_MANIFEST:
                modifyState(key, ManifestViewerActivity.class, enabled);
                break;
            case FEAT_SCANNER:
                modifyState(key, ScannerActivity.class, enabled);
                break;
            case FEAT_USAGE_ACCESS:
            case FEAT_INTERNET:
                // Only depends on flag
                break;
            case FEAT_LOG_VIEWER:
                modifyState(key, LogViewerActivity.class, enabled);
                break;
            case FEAT_APP_EXPLORER:
                modifyState(key, AppExplorerActivity.class, enabled);
                break;
            case FEAT_APP_INFO:
                modifyState(key, AppDetailsActivity.ALIAS_APP_INFO, enabled);
                break;
        }
        // Modify flags
        flags = enabled ? (flags | key) : (flags & ~key);
        // Save to pref
        AppPref.set(AppPref.PrefKey.PREF_ENABLED_FEATURES_INT, flags);
    }

    private void modifyState(@FeatureFlags int key, @Nullable Class<? extends AppCompatActivity> clazz, boolean enabled) {
        ComponentName cn = getComponentName(key, clazz);
        if (cn == null) return;
        int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
    }

    private void modifyState(@FeatureFlags int key, @Nullable String name, boolean enabled) {
        ComponentName cn = getComponentName(key, name);
        if (cn == null) return;
        int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
    }

    @Nullable
    private ComponentName getComponentName(@FeatureFlags int key, @Nullable Class<? extends AppCompatActivity> clazz) {
        if (clazz == null) return null;
        ComponentName cn = componentCache.get(key);
        if (cn == null) {
            cn = new ComponentName(packageName, clazz.getName());
            componentCache.put(key, cn);
        }
        return cn;
    }

    @Nullable
    private ComponentName getComponentName(@FeatureFlags int key, @Nullable String name) {
        if (name == null) return null;
        ComponentName cn = componentCache.get(key);
        if (cn == null) {
            cn = new ComponentName(packageName, name);
            componentCache.put(key, cn);
        }
        return cn;
    }

    private boolean isComponentEnabled(@Nullable ComponentName componentName) {
        if (componentName == null) return true;
        int status = pm.getComponentEnabledSetting(componentName);
        return status == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }
}
