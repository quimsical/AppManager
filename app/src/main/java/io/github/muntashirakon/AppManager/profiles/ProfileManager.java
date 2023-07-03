// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.profiles;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandleHidden;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.github.muntashirakon.AppManager.backup.BackupFlags;
import io.github.muntashirakon.AppManager.batchops.BatchOpsManager;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.progress.ProgressHandler;
import io.github.muntashirakon.AppManager.types.UserPackagePair;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.io.Path;

public class ProfileManager {
    public static final String TAG = "ProfileManager";

    @NonNull
    public static ArrayList<String> getProfileNames() {
        Path profilesPath = ProfileMetaManager.getProfilesDir();
        String[] profilesFiles = profilesPath.listFileNames((dir, name) -> name.endsWith(ProfileMetaManager.PROFILE_EXT));
        ArrayList<String> profileNames = new ArrayList<>(profilesFiles.length);
        for (String profile : profilesFiles) {
            int index = profile.indexOf(ProfileMetaManager.PROFILE_EXT);
            profile = profile.substring(0, index);
            profileNames.add(profile);
        }
        return profileNames;
    }

    @NonNull
    public static HashMap<String, CharSequence> getProfileSummaries(@NonNull Context context) {
        Path profilesPath = ProfileMetaManager.getProfilesDir();
        String[] profilesFiles = profilesPath.listFileNames((dir, name) -> name.endsWith(ProfileMetaManager.PROFILE_EXT));
        HashMap<String, CharSequence> profiles = new HashMap<>(profilesFiles.length);
        for (String profile : profilesFiles) {
            int index = profile.indexOf(ProfileMetaManager.PROFILE_EXT);
            profile = profile.substring(0, index);
            ProfileMetaManager metaManager = new ProfileMetaManager(profile);
            profiles.put(metaManager.getProfileName(), metaManager.toLocalizedString(context));
        }
        return profiles;
    }

    @NonNull
    public static List<ProfileMetaManager> getProfileMetadata() {
        Path profilesPath = ProfileMetaManager.getProfilesDir();
        String[] profilesFiles = profilesPath.listFileNames((dir, name) -> name.endsWith(ProfileMetaManager.PROFILE_EXT));
        List<ProfileMetaManager> profiles = new ArrayList<>(profilesFiles.length);
        for (String profile : profilesFiles) {
            int index = profile.indexOf(ProfileMetaManager.PROFILE_EXT);
            profile = profile.substring(0, index);
            profiles.add(new ProfileMetaManager(profile));
        }
        return profiles;
    }

    @NonNull
    private final ProfileMetaManager.Profile mProfile;
    @Nullable
    private ProfileLogger mLogger;
    private boolean mRequiresRestart;

    public ProfileManager(@NonNull ProfileMetaManager metaManager) {
        mProfile = metaManager.getProfile();
        try {
            mLogger = new ProfileLogger(mProfile.name);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean requiresRestart() {
        return mRequiresRestart;
    }

    @SuppressLint("SwitchIntDef")
    public void applyProfile(@Nullable String state, @Nullable ProgressHandler progressHandler) {
        // Set state
        if (state == null) state = mProfile.state;

        log("====> Started execution with state " + state);

        if (mProfile.packages.length == 0) return;
        int[] users = mProfile.users == null ? Users.getUsersIds() : mProfile.users;
        List<UserPackagePair> userPackagePairs = new ArrayList<>();
        for (String packageName : mProfile.packages) {
            for (int user : users) {
                userPackagePairs.add(new UserPackagePair(packageName, user));
            }
        }
        // Send progress
        if (progressHandler != null) {
            progressHandler.postUpdate(calculateMaxProgress(userPackagePairs), 0);
        }
        BatchOpsManager batchOpsManager = new BatchOpsManager(mLogger);
        BatchOpsManager.Result result;
        // Apply component blocking
        String[] components = mProfile.components;
        if (components != null) {
            log("====> Started block/unblock components. State: " + state);
            Bundle args = new Bundle();
            args.putStringArray(BatchOpsManager.ARG_SIGNATURES, components);
            batchOpsManager.setArgs(args);
            switch (state) {
                case ProfileMetaManager.STATE_ON:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_BLOCK_COMPONENTS, userPackagePairs, progressHandler);
                    break;
                case ProfileMetaManager.STATE_OFF:
                default:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_UNBLOCK_COMPONENTS, userPackagePairs, progressHandler);
            }
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped components.");
        // Apply app ops blocking
        int[] appOps = mProfile.appOps;
        if (appOps != null) {
            log("====> Started ignore/default components. State: " + state);
            Bundle args = new Bundle();
            args.putIntArray(BatchOpsManager.ARG_APP_OPS, appOps);
            switch (state) {
                case ProfileMetaManager.STATE_ON:
                    args.putInt(BatchOpsManager.ARG_APP_OP_MODE, AppOpsManager.MODE_IGNORED);
                    break;
                case ProfileMetaManager.STATE_OFF:
                default:
                    args.putInt(BatchOpsManager.ARG_APP_OP_MODE, AppOpsManager.MODE_DEFAULT);
            }
            batchOpsManager.setArgs(args);
            result = batchOpsManager.performOp(BatchOpsManager.OP_SET_APP_OPS, userPackagePairs, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped app ops.");
        // Apply permissions
        String[] permissions = mProfile.permissions;
        if (permissions != null) {
            log("====> Started grant/revoke permissions.");
            Bundle args = new Bundle();
            args.putStringArray(BatchOpsManager.ARG_PERMISSIONS, permissions);
            batchOpsManager.setArgs(args);
            switch (state) {
                case ProfileMetaManager.STATE_ON:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_REVOKE_PERMISSIONS, userPackagePairs, progressHandler);
                    break;
                case ProfileMetaManager.STATE_OFF:
                default:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_GRANT_PERMISSIONS, userPackagePairs, progressHandler);
            }
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped permissions.");
        // Backup rules
        Integer rulesFlag = mProfile.exportRules;
        if (rulesFlag != null) {
            log("====> Not implemented export rules.");
            // TODO(18/11/20): Export rules
        } else Log.d(TAG, "Skipped export rules.");
        // Disable/enable
        if (mProfile.freeze) {
            log("====> Started freeze/unfreeze. State: " + state);
            switch (state) {
                case ProfileMetaManager.STATE_ON:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_FREEZE, userPackagePairs, progressHandler);
                    break;
                case ProfileMetaManager.STATE_OFF:
                default:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_UNFREEZE, userPackagePairs, progressHandler);
            }
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped disable/enable.");
        // Force-stop
        if (mProfile.forceStop) {
            log("====> Started force-stop.");
            result = batchOpsManager.performOp(BatchOpsManager.OP_FORCE_STOP, userPackagePairs, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped force stop.");
        // Clear cache
        if (mProfile.clearCache) {
            log("====> Started clear cache.");
            result = batchOpsManager.performOp(BatchOpsManager.OP_CLEAR_CACHE, userPackagePairs, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped clear cache.");
        // Clear data
        if (mProfile.clearData) {
            log("====> Started clear data.");
            result = batchOpsManager.performOp(BatchOpsManager.OP_CLEAR_DATA, userPackagePairs, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped clear data.");
        // Block trackers
        if (mProfile.blockTrackers) {
            log("====> Started block trackers. State: " + state);
            switch (state) {
                case ProfileMetaManager.STATE_ON:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_BLOCK_TRACKERS, userPackagePairs, progressHandler);
                    break;
                case ProfileMetaManager.STATE_OFF:
                default:
                    result = batchOpsManager.performOp(BatchOpsManager.OP_UNBLOCK_TRACKERS, userPackagePairs, progressHandler);
            }
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped block trackers.");
        // Backup apk
        if (mProfile.saveApk) {
            log("====> Started backup apk.");
            result = batchOpsManager.performOp(BatchOpsManager.OP_BACKUP_APK, userPackagePairs, progressHandler);
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped backup apk.");
        // Backup/restore data
        ProfileMetaManager.Profile.BackupInfo backupInfo = mProfile.backupData;
        if (backupInfo != null) {
            log("====> Started backup/restore.");
            BackupFlags backupFlags = new BackupFlags(backupInfo.flags);
            Bundle args = new Bundle();
            if (backupFlags.backupMultiple() && backupInfo.name != null) {
                if (state.equals(ProfileMetaManager.STATE_OFF)) {
                    args.putStringArray(BatchOpsManager.ARG_BACKUP_NAMES, new String[]{UserHandleHidden.myUserId()
                            + '_' + backupInfo.name});
                } else {
                    args.putStringArray(BatchOpsManager.ARG_BACKUP_NAMES, new String[]{backupInfo.name});
                }
            }
            // Always add backup custom users
            backupFlags.addFlag(BackupFlags.BACKUP_CUSTOM_USERS);
            args.putInt(BatchOpsManager.ARG_FLAGS, backupFlags.getFlags());
            batchOpsManager.setArgs(args);
            switch (state) {
                case ProfileMetaManager.STATE_ON:  // Take backup
                    result = batchOpsManager.performOp(BatchOpsManager.OP_BACKUP, userPackagePairs, progressHandler);
                    break;
                case ProfileMetaManager.STATE_OFF:  // Restore backup
                    result = batchOpsManager.performOp(BatchOpsManager.OP_RESTORE_BACKUP, userPackagePairs, progressHandler);
                    mRequiresRestart |= result.requiresRestart();
                    break;
                default:
                    result = new BatchOpsManager.Result(userPackagePairs);
            }
            if (!result.isSuccessful()) {
                Log.d(TAG, "Failed packages: " + result);
            }
        } else Log.d(TAG, "Skipped backup/restore.");
        log("====> Execution completed.");
        batchOpsManager.conclude();
    }

    public void conclude() {
        if (mLogger != null) {
            mLogger.close();
        }
    }

    private int calculateMaxProgress(@NonNull List<UserPackagePair> userPackagePairs) {
        int packageCount = userPackagePairs.size();
        int opCount = 0;
        if (mProfile.components != null) ++opCount;
        if (mProfile.appOps != null) ++opCount;
        if (mProfile.permissions != null) ++opCount;
        // if (profile.exportRules != null) ++opCount; todo
        if (mProfile.freeze) ++opCount;
        if (mProfile.forceStop) ++opCount;
        if (mProfile.clearCache) ++opCount;
        if (mProfile.clearData) ++opCount;
        if (mProfile.blockTrackers) ++opCount;
        if (mProfile.saveApk) ++opCount;
        if (mProfile.backupData != null) ++opCount;
        return opCount * packageCount;
    }

    private void log(@Nullable String message) {
        if (mLogger != null) {
            mLogger.println(message);
        }
    }
}
