// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.backup;

import static io.github.muntashirakon.AppManager.backup.BackupManager.DATA_PREFIX;
import static io.github.muntashirakon.AppManager.backup.BackupManager.KEYSTORE_PLACEHOLDER;
import static io.github.muntashirakon.AppManager.backup.BackupManager.KEYSTORE_PREFIX;
import static io.github.muntashirakon.AppManager.backup.BackupManager.MASTER_KEY;
import static io.github.muntashirakon.AppManager.backup.BackupManager.SOURCE_PREFIX;

import android.app.AppOpsManager;
import android.app.INotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.system.ErrnoException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.github.muntashirakon.AppManager.apk.ApkFile;
import io.github.muntashirakon.AppManager.apk.installer.PackageInstallerCompat;
import io.github.muntashirakon.AppManager.compat.AppOpsManagerCompat;
import io.github.muntashirakon.AppManager.compat.NetworkPolicyManagerCompat;
import io.github.muntashirakon.AppManager.compat.PackageManagerCompat;
import io.github.muntashirakon.AppManager.crypto.Crypto;
import io.github.muntashirakon.AppManager.crypto.CryptoException;
import io.github.muntashirakon.AppManager.ipc.ProxyBinder;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.magisk.MagiskDenyList;
import io.github.muntashirakon.AppManager.magisk.MagiskHide;
import io.github.muntashirakon.AppManager.permission.PermUtils;
import io.github.muntashirakon.AppManager.permission.Permission;
import io.github.muntashirakon.AppManager.rules.PseudoRules;
import io.github.muntashirakon.AppManager.rules.RuleType;
import io.github.muntashirakon.AppManager.rules.RulesImporter;
import io.github.muntashirakon.AppManager.rules.struct.AppOpRule;
import io.github.muntashirakon.AppManager.rules.struct.MagiskDenyListRule;
import io.github.muntashirakon.AppManager.rules.struct.MagiskHideRule;
import io.github.muntashirakon.AppManager.rules.struct.NetPolicyRule;
import io.github.muntashirakon.AppManager.rules.struct.PermissionRule;
import io.github.muntashirakon.AppManager.rules.struct.RuleEntry;
import io.github.muntashirakon.AppManager.rules.struct.SsaidRule;
import io.github.muntashirakon.AppManager.rules.struct.UriGrantRule;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.ssaid.SsaidSettings;
import io.github.muntashirakon.AppManager.uri.UriManager;
import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.DigestUtils;
import io.github.muntashirakon.AppManager.utils.KeyStoreUtils;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.TarUtils;
import io.github.muntashirakon.AppManager.utils.Utils;
import io.github.muntashirakon.io.Path;
import io.github.muntashirakon.io.Paths;
import io.github.muntashirakon.io.UidGidPair;

@WorkerThread
class RestoreOp implements Closeable {
    static final String TAG = RestoreOp.class.getSimpleName();
    private static final Object sLock = new Object();

    @NonNull
    private final Context context = ContextUtils.getContext();
    @NonNull
    private final String packageName;
    @NonNull
    private final BackupFlags backupFlags;
    @NonNull
    private final BackupFlags requestedFlags;
    @NonNull
    private final MetadataManager.Metadata metadata;
    @NonNull
    private final Path backupPath;
    @NonNull
    private final BackupFiles.BackupFile backupFile;
    @Nullable
    private PackageInfo packageInfo;
    @NonNull
    private final Crypto crypto;
    @NonNull
    private final BackupFiles.Checksum checksum;
    private final int userHandle;
    private boolean isInstalled;
    private final List<Path> decryptedFiles = new ArrayList<>();

    private boolean requiresRestart;

    RestoreOp(@NonNull String packageName, @NonNull MetadataManager metadataManager,
              @NonNull BackupFlags requestedFlags, @NonNull BackupFiles.BackupFile backupFile,
              int userHandle) throws BackupException {
        this.packageName = packageName;
        this.requestedFlags = requestedFlags;
        this.backupFile = backupFile;
        this.backupPath = this.backupFile.getBackupPath();
        this.userHandle = userHandle;
        try {
            metadataManager.readMetadata(this.backupFile);
            metadata = metadataManager.getMetadata();
            backupFlags = metadata.flags;
        } catch (IOException e) {
            throw new BackupException("Failed to read metadata. Possibly due to malformed json file.", e);
        }
        // Setup crypto
        if (!CryptoUtils.isAvailable(metadata.crypto)) {
            throw new BackupException("Mode " + metadata.crypto + " is currently unavailable.");
        }
        try {
            crypto = CryptoUtils.getCrypto(metadata);
        } catch (CryptoException e) {
            throw new BackupException("Failed to get crypto " + metadata.crypto, e);
        }
        Path checksumFile;
        try {
            checksumFile = this.backupFile.getChecksumFile(metadata.crypto);
        } catch (IOException e) {
            throw new BackupException("Could not get encrypted checksum.txt file.", e);
        }
        // Decrypt checksum
        try {
            decrypt(new Path[]{checksumFile});
        } catch (IOException e) {
            throw new BackupException("Failed to decrypt " + checksumFile.getName(), e);
        }
        // Get checksums
        try {
            this.checksum = this.backupFile.getChecksum(CryptoUtils.MODE_NO_ENCRYPTION);
        } catch (Throwable e) {
            this.backupFile.cleanup();
            throw new BackupException("Failed to get checksums.", e);
        }
        // Verify metadata
        if (!requestedFlags.skipSignatureCheck()) {
            Path metadataFile;
            try {
                metadataFile = this.backupFile.getMetadataFile();
            } catch (IOException e) {
                throw new BackupException("Could not get metadata file.", e);
            }
            String checksum = DigestUtils.getHexDigest(metadata.checksumAlgo, metadataFile);
            if (!checksum.equals(this.checksum.get(metadataFile.getName()))) {
                throw new BackupException("Couldn't verify metadata file." +
                        "\nFile: " + metadataFile +
                        "\nFound: " + checksum +
                        "\nRequired: " + this.checksum.get(metadataFile.getName()));
            }
        }
        // Check user handle
        if (metadata.userHandle != userHandle) {
            Log.w(TAG, "Using different user handle.");
        }
        // Get package info
        packageInfo = null;
        try {
            packageInfo = PackageManagerCompat.getPackageInfo(packageName, PackageUtils.flagSigningInfo, userHandle);
        } catch (Exception ignore) {
        }
        isInstalled = packageInfo != null;
    }

    @Override
    public void close() {
        Log.d(TAG, "Close called");
        crypto.close();
        for (Path file : decryptedFiles) {
            Log.d(TAG, "Deleting " + file);
            file.delete();
        }
    }

    @NonNull
    public MetadataManager.Metadata getMetadata() {
        return metadata;
    }

    void runRestore() throws BackupException {
        try {
            if (requestedFlags.backupData() && metadata.keyStore && !requestedFlags.skipSignatureCheck()) {
                // Check checksum of master key first
                checkMasterKey();
            }
            if (requestedFlags.backupApkFiles()) {
                restoreApkFiles();
            }
            if (requestedFlags.backupData()) {
                restoreData();
                if (metadata.keyStore) restoreKeyStore();
            }
            if (requestedFlags.backupExtras()) {
                restoreExtras();
            }
            if (requestedFlags.backupRules()) {
                restoreRules();
            }
        } catch (BackupException e) {
            throw e;
        } catch (Throwable th) {
            throw new BackupException("Unknown error occurred", th);
        }
    }

    public boolean requiresRestart() {
        return requiresRestart;
    }

    private void checkMasterKey() throws BackupException {
        if (true) {
            // TODO: 6/2/22 MasterKey may not actually be necessary.
            return;
        }
        String oldChecksum = checksum.get(MASTER_KEY);
        Path masterKey;
        try {
            masterKey = KeyStoreUtils.getMasterKey(userHandle);
        } catch (FileNotFoundException e) {
            if (oldChecksum == null) return;
            else throw new BackupException("Master key existed when the checksum was made but now it doesn't.");
        }
        if (oldChecksum == null) {
            throw new BackupException("Master key exists but it didn't exist when the backup was made.");
        }
        String newChecksum = DigestUtils.getHexDigest(metadata.checksumAlgo, masterKey.getContentAsString().getBytes());
        if (!newChecksum.equals(oldChecksum)) {
            throw new BackupException("Checksums for master key did not match.");
        }
    }

    private void restoreApkFiles() throws BackupException {
        if (!backupFlags.backupApkFiles()) {
            throw new BackupException("APK restore is requested but backup doesn't contain any source files.");
        }
        Path[] backupSourceFiles = getSourceFiles(backupPath);
        if (backupSourceFiles.length == 0) {
            // No source backup found
            throw new BackupException("Source restore is requested but there are no source files.");
        }
        boolean isVerified = true;
        if (packageInfo != null) {
            // Check signature of the installed app
            List<String> certChecksumList = Arrays.asList(PackageUtils.getSigningCertChecksums(metadata.checksumAlgo, packageInfo, false));
            String[] certChecksums = BackupFiles.Checksum.getCertChecksums(checksum);
            for (String checksum : certChecksums) {
                if (certChecksumList.contains(checksum)) continue;
                isVerified = false;
                if (!requestedFlags.skipSignatureCheck()) {
                    throw new BackupException("Signing info verification failed." +
                            "\nInstalled: " + certChecksumList +
                            "\nBackup: " + Arrays.toString(certChecksums));
                }
            }
        }
        if (!requestedFlags.skipSignatureCheck()) {
            String checksum;
            for (Path file : backupSourceFiles) {
                checksum = DigestUtils.getHexDigest(metadata.checksumAlgo, file);
                if (!checksum.equals(this.checksum.get(file.getName()))) {
                    throw new BackupException("Source file verification failed." +
                            "\nFile: " + file +
                            "\nFound: " + checksum +
                            "\nRequired: " + this.checksum.get(file.getName()));
                }
            }
        }
        if (!isVerified) {
            // Signature verification failed but still here because signature check is disabled.
            // The only way to restore is to reinstall the app
            synchronized (sLock) {
                PackageInstallerCompat installer = PackageInstallerCompat.getNewInstance();
                if (installer.uninstall(packageName, userHandle, false)) {
                    throw new BackupException("An uninstallation was necessary but couldn't perform it.");
                }
            }
        }
        // Setup package staging directory
        Path packageStagingDirectory;
        if (Ops.isPrivileged()) {
            try {
                synchronized (sLock) {
                    PackageUtils.ensurePackageStagingDirectoryPrivileged();
                }
                packageStagingDirectory = Paths.get(PackageUtils.PACKAGE_STAGING_DIRECTORY);
            } catch (Exception e) {
                throw new BackupException("Could not ensure the existence of /data/local/tmp", e);
            }
        } else {
            packageStagingDirectory = backupPath;
        }
        synchronized (sLock) {
            // Setup apk files, including split apk
            final int splitCount = metadata.splitConfigs.length;
            String[] allApkNames = new String[splitCount + 1];
            Path[] allApks = new Path[splitCount + 1];
            try {
                Path baseApk = packageStagingDirectory.createNewFile(metadata.apkName, null);
                allApks[0] = baseApk;
                allApkNames[0] = metadata.apkName;
                for (int i = 1; i < allApkNames.length; ++i) {
                    allApkNames[i] = metadata.splitConfigs[i - 1];
                    allApks[i] = packageStagingDirectory.createNewFile(allApkNames[i], null);
                }
            } catch (IOException e) {
                throw new BackupException("Could not create staging files", e);
            }
            // Decrypt sources
            try {
                backupSourceFiles = decrypt(backupSourceFiles);
            } catch (IOException e) {
                throw new BackupException("Failed to decrypt " + Arrays.toString(backupSourceFiles), e);
            }
            // Extract apk files to the package staging directory
            try {
                TarUtils.extract(metadata.tarType, backupSourceFiles, packageStagingDirectory, allApkNames, null, null);
            } catch (Throwable th) {
                throw new BackupException("Failed to extract the apk file(s).", th);
            }
            // A normal update will do it now
            PackageInstallerCompat packageInstaller = PackageInstallerCompat.getNewInstance(metadata.installer);
            packageInstaller.setOnInstallListener(new PackageInstallerCompat.OnInstallListener() {
                @Override
                public void onStartInstall(int sessionId, String packageName) {
                }

                @Override
                public void onAnotherAttemptInMiui(@Nullable ApkFile apkFile) {
                    // This works because the parent install method still remains active until a final status is
                    // received after all the attempts are finished, which is, then, returned to the parent.
                    packageInstaller.install(allApks, packageName, userHandle);
                }

                @Override
                public void onFinishedInstall(int sessionId, String packageName, int result, @Nullable String blockingPackage, @Nullable String statusMessage) {
                }
            });
            try {
                if (!packageInstaller.install(allApks, packageName, userHandle)) {
                    throw new BackupException("A (re)install was necessary but couldn't perform it.");
                }
            } finally {
                deleteFiles(allApks);  // Clean up apk files
            }
            // Get package info, again
            try {
                packageInfo = PackageManagerCompat.getPackageInfo(packageName, PackageUtils.flagSigningInfo, userHandle);
                isInstalled = true;
            } catch (Exception e) {
                throw new BackupException("Apparently the install wasn't complete in the previous section.", e);
            }
        }
    }

    private void restoreKeyStore() throws BackupException {
        if (packageInfo == null) {
            throw new BackupException("KeyStore restore is requested but the app isn't installed.");
        }
        Path[] keyStoreFiles = getKeyStoreFiles(backupPath);
        if (keyStoreFiles.length == 0) {
            throw new BackupException("KeyStore files should've existed but they didn't");
        }
        if (!requestedFlags.skipSignatureCheck()) {
            String checksum;
            for (Path file : keyStoreFiles) {
                checksum = DigestUtils.getHexDigest(metadata.checksumAlgo, file);
                if (!checksum.equals(this.checksum.get(file.getName()))) {
                    throw new BackupException("KeyStore file verification failed." +
                            "\nFile: " + file +
                            "\nFound: " + checksum +
                            "\nRequired: " + this.checksum.get(file.getName()));
                }
            }
        }
        // Decrypt sources
        try {
            keyStoreFiles = decrypt(keyStoreFiles);
        } catch (IOException e) {
            throw new BackupException("Failed to decrypt " + Arrays.toString(keyStoreFiles), e);
        }
        // Restore KeyStore files to the /data/misc/keystore folder
        Path keyStorePath = KeyStoreUtils.getKeyStorePath(userHandle);
        // Note down UID/GID
        UidGidPair uidGidPair;
        int mode;
        try {
            uidGidPair = Objects.requireNonNull(keyStorePath.getFile()).getUidGid();
            mode = keyStorePath.getFile().getMode();
        } catch (ErrnoException e) {
            throw new BackupException("Failed to access properties of the KeyStore folder.", e);
        }
        try {
            TarUtils.extract(metadata.tarType, keyStoreFiles, keyStorePath, null, null, null);
            // Restore folder permission
            Paths.chown(keyStorePath, uidGidPair.uid, uidGidPair.gid);
            //noinspection OctalInteger
            Paths.chmod(keyStorePath, mode & 0777);
        } catch (Throwable th) {
            throw new BackupException("Failed to restore the KeyStore files.", th);
        }
        // Rename files
        int uid = packageInfo.applicationInfo.uid;
        List<String> keyStoreFileNames = KeyStoreUtils.getKeyStoreFiles(KEYSTORE_PLACEHOLDER, userHandle);
        for (String keyStoreFileName : keyStoreFileNames) {
            try {
                String newFilename = Utils.replaceOnce(keyStoreFileName, String.valueOf(KEYSTORE_PLACEHOLDER), String.valueOf(uid));
                keyStorePath.findFile(keyStoreFileName).renameTo(newFilename);
                Path targetFile = keyStorePath.findFile(newFilename);
                // Restore file permission
                Paths.chown(targetFile, uidGidPair.uid, uidGidPair.gid);
                //noinspection OctalInteger
                Paths.chmod(targetFile, 0600);
            } catch (IOException | ErrnoException e) {
                throw new BackupException("Failed to rename KeyStore files", e);
            }
        }
        Runner.runCommand(new String[]{"restorecon", "-R", keyStorePath.getFilePath()});
    }

    private void restoreData() throws BackupException {
        // Data restore is requested: Data restore is only possible if the app is actually
        // installed. So, check if it's installed first.
        if (packageInfo == null) {
            throw new BackupException("Data restore is requested but the app isn't installed.");
        }
        if (!requestedFlags.skipSignatureCheck()) {
            // Verify integrity of the data backups
            String checksum;
            for (int i = 0; i < metadata.dataDirs.length; ++i) {
                Path[] dataFiles = getDataFiles(backupPath, i);
                if (dataFiles.length == 0) {
                    throw new BackupException("Data restore is requested but there are no data files for index " + i + ".");
                }
                for (Path file : dataFiles) {
                    checksum = DigestUtils.getHexDigest(metadata.checksumAlgo, file);
                    if (!checksum.equals(this.checksum.get(file.getName()))) {
                        throw new BackupException("Data file verification failed for index " + i + "." +
                                "\nFile: " + file +
                                "\nFound: " + checksum +
                                "\nRequired: " + this.checksum.get(file.getName()));
                    }
                }
            }
        }
        // Force-stop and clear app data
        PackageManagerCompat.clearApplicationUserData(packageName, userHandle);
        // Restore backups
        for (int i = 0; i < metadata.dataDirs.length; ++i) {
            String dataSource = BackupUtils.getWritableDataDirectory(metadata.dataDirs[i], metadata.userHandle, userHandle);
            BackupDataDirectoryInfo dataDirectoryInfo = BackupDataDirectoryInfo.getInfo(dataSource, userHandle);
            Path dataSourceFile = Paths.get(dataSource);

            Path[] dataFiles = getDataFiles(backupPath, i);
            if (dataFiles.length == 0) {
                throw new BackupException("Data restore is requested but there are no data files for index " + i + ".");
            }
            UidGidPair uidGidPair = dataSourceFile.getUidGid();
            if (uidGidPair == null) {
                // Fallback to app UID
                uidGidPair = new UidGidPair(packageInfo.applicationInfo.uid, packageInfo.applicationInfo.uid);
            }
            if (dataDirectoryInfo.isExternal()) {
                // Skip if external data restore is not requested
                switch (dataDirectoryInfo.subtype) {
                    case BackupDataDirectoryInfo.TYPE_ANDROID_DATA:
                        // Skip restoring Android/data directory if not requested
                        if (!requestedFlags.backupExternalData()) {
                            continue;
                        }
                        break;
                    case BackupDataDirectoryInfo.TYPE_ANDROID_OBB:
                    case BackupDataDirectoryInfo.TYPE_ANDROID_MEDIA:
                        // Skip restoring Android/data or Android/media if media/obb restore not requested
                        if (!requestedFlags.backupMediaObb()) {
                            continue;
                        }
                        break;
                    case BackupDataDirectoryInfo.TYPE_CREDENTIAL_PROTECTED:
                    case BackupDataDirectoryInfo.TYPE_CUSTOM:
                    case BackupDataDirectoryInfo.TYPE_DEVICE_PROTECTED:
                        // NOP
                        break;
                }
            } else {
                // Skip if internal data restore is not requested.
                if (!requestedFlags.backupInternalData()) continue;
            }
            // Create data folder if not exists
            if (!dataSourceFile.exists()) {
                if (dataDirectoryInfo.isExternal() && !dataDirectoryInfo.isMounted) {
                    throw new BackupException("External directory containing " + dataSource + " is not mounted.");
                }
                dataSourceFile.mkdirs();
                if (!dataDirectoryInfo.isExternal()) {
                    // Restore UID, GID
                    dataSourceFile.setUidGid(uidGidPair);
                }
            }
            // Decrypt data
            try {
                dataFiles = decrypt(dataFiles);
            } catch (IOException e) {
                throw new BackupException("Failed to decrypt " + Arrays.toString(dataFiles), e);
            }
            // Extract data to the data directory
            try {
                String publicSourceDir = new File(packageInfo.applicationInfo.publicSourceDir).getParent();
                TarUtils.extract(metadata.tarType, dataFiles, dataSourceFile, null, BackupUtils
                        .getExcludeDirs(!requestedFlags.backupCache(), null), publicSourceDir);
            } catch (Throwable th) {
                throw new BackupException("Failed to restore data files for index " + i + ".", th);
            }
            // Restore UID and GID
            if (!Runner.runCommand(String.format(Locale.ROOT, "chown -R %d:%d \"%s\"", uidGidPair.uid, uidGidPair.gid, dataSource)).isSuccessful()) {
                throw new BackupException("Failed to restore ownership info for index " + i + ".");
            }
            // Restore context
            if (!dataDirectoryInfo.isExternal()) {
                Runner.runCommand(new String[]{"restorecon", "-R", dataSource});
            }
        }
    }

    private synchronized void restoreExtras() throws BackupException {
        if (!isInstalled) {
            throw new BackupException("Misc restore is requested but the app isn't installed.");
        }
        PseudoRules rules = new PseudoRules(packageName, userHandle);
        // Backward compatibility for restoring permissions
        loadMiscRules(rules);
        // Apply rules
        List<RuleEntry> entries = rules.getAll();
        AppOpsManagerCompat appOpsManager = new AppOpsManagerCompat(context);
        INotificationManager notificationManager = INotificationManager.Stub.asInterface(ProxyBinder.getService(Context.NOTIFICATION_SERVICE));
        boolean magiskHideAvailable = MagiskHide.available();
        for (RuleEntry entry : entries) {
            try {
                switch (entry.type) {
                    case APP_OP:
                        appOpsManager.setMode(Integer.parseInt(entry.name), packageInfo.applicationInfo.uid,
                                packageName, ((AppOpRule) entry).getMode());
                        break;
                    case NET_POLICY:
                        NetworkPolicyManagerCompat.setUidPolicy(packageInfo.applicationInfo.uid,
                                ((NetPolicyRule) entry).getPolicies());
                        break;
                    case PERMISSION: {
                        PermissionRule permissionRule = (PermissionRule) entry;
                        Permission permission = permissionRule.getPermission(true);
                        permission.setAppOpAllowed(permission.getAppOp() != AppOpsManagerCompat.OP_NONE && appOpsManager
                                .checkOperation(permission.getAppOp(), packageInfo.applicationInfo.uid,
                                        packageName) == AppOpsManager.MODE_ALLOWED);
                        if (permissionRule.isGranted()) {
                            PermUtils.grantPermission(packageInfo, permission, appOpsManager, true, true);
                        } else {
                            PermUtils.revokePermission(packageInfo, permission, appOpsManager, true);
                        }
                        break;
                    }
                    case BATTERY_OPT:
                        Runner.runCommand(new String[]{"dumpsys", "deviceidle", "whitelist", "+" + packageName});
                        break;
                    case MAGISK_HIDE: {
                        MagiskHideRule magiskHideRule = (MagiskHideRule) entry;
                        if (magiskHideAvailable) {
                            MagiskHide.apply(magiskHideRule.getMagiskProcess());
                        } else {
                            // Fall-back to Magisk DenyList
                            MagiskDenyList.apply(magiskHideRule.getMagiskProcess());
                        }
                        break;
                    }
                    case MAGISK_DENY_LIST: {
                        MagiskDenyList.apply(((MagiskDenyListRule) entry).getMagiskProcess());
                        break;
                    }
                    case NOTIFICATION:
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            notificationManager.setNotificationListenerAccessGrantedForUser(
                                    new ComponentName(packageName, entry.name), userHandle, true);
                        }
                        break;
                    case URI_GRANT:
                        UriManager.UriGrant uriGrant = ((UriGrantRule) entry).getUriGrant();
                        UriManager.UriGrant newUriGrant = new UriManager.UriGrant(
                                uriGrant.sourceUserId, userHandle, uriGrant.userHandle,
                                uriGrant.sourcePkg, uriGrant.targetPkg, uriGrant.uri,
                                uriGrant.prefix, uriGrant.modeFlags, uriGrant.createdTime);
                        UriManager uriManager = new UriManager();
                        uriManager.grantUri(newUriGrant);
                        uriManager.writeGrantedUriPermissions();
                        requiresRestart = true;
                        break;
                    case SSAID:
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            new SsaidSettings(userHandle).setSsaid(packageName, packageInfo.applicationInfo.uid,
                                    ((SsaidRule) entry).getSsaid());
                            requiresRestart = true;
                        }
                        break;
                }
            } catch (Throwable e) {
                // There are several reason restoring these things go wrong, especially when
                // downgrading from an Android to another. It's better to simply suppress these
                // exceptions instead of causing a failure or worse, a crash
                Log.e(TAG, e);
            }
        }
    }

    private void loadMiscRules(final PseudoRules rules) throws BackupException {
        Path miscFile;
        try {
            miscFile = backupFile.getMiscFile(metadata.crypto);
        } catch (IOException e) {
            // There are no permissions, just skip
            return;
        }
        if (!requestedFlags.skipSignatureCheck()) {
            String checksum = DigestUtils.getHexDigest(metadata.checksumAlgo, miscFile);
            if (!checksum.equals(this.checksum.get(miscFile.getName()))) {
                throw new BackupException("Couldn't verify misc file." +
                        "\nFile: " + miscFile +
                        "\nFound: " + checksum +
                        "\nRequired: " + this.checksum.get(miscFile.getName()));
            }
        }
        // Decrypt permission file
        try {
            decrypt(new Path[]{miscFile});
        } catch (IOException e) {
            throw new BackupException("Failed to decrypt " + miscFile.getName(), e);
        }
        // Get decrypted file
        try {
            miscFile = backupFile.getMiscFile(CryptoUtils.MODE_NO_ENCRYPTION);
        } catch (IOException e) {
            throw new BackupException("Could not get decrypted misc file", e);
        }
        try {
            rules.loadExternalEntries(miscFile);
        } catch (Throwable e) {
            throw new BackupException("Failed to load rules from misc.", e);
        }
    }

    private void restoreRules() throws BackupException {
        // Apply rules
        if (!isInstalled) {
            throw new BackupException("Rules restore is requested but the app isn't installed.");
        }
        Path rulesFile;
        try {
            rulesFile = backupFile.getRulesFile(metadata.crypto);
        } catch (IOException e) {
            if (metadata.hasRules) {
                throw new BackupException("Rules file is missing.", e);
            } else {
                // There are no rules, just skip
                return;
            }
        }
        if (!requestedFlags.skipSignatureCheck()) {
            String checksum = DigestUtils.getHexDigest(metadata.checksumAlgo, rulesFile);
            if (!checksum.equals(this.checksum.get(rulesFile.getName()))) {
                throw new BackupException("Couldn't verify permission file." +
                        "\nFile: " + rulesFile +
                        "\nFound: " + checksum +
                        "\nRequired: " + this.checksum.get(rulesFile.getName()));
            }
        }
        // Decrypt rules file
        try {
            decrypt(new Path[]{rulesFile});
        } catch (IOException e) {
            throw new BackupException("Failed to decrypt " + rulesFile.getName(), e);
        }
        // Get decrypted file
        try {
            rulesFile = backupFile.getRulesFile(CryptoUtils.MODE_NO_ENCRYPTION);
        } catch (IOException e) {
            throw new BackupException("Could not get decrypted rules file", e);
        }
        try (RulesImporter importer = new RulesImporter(Arrays.asList(RuleType.values()), new int[]{userHandle})) {
            importer.addRulesFromPath(rulesFile);
            importer.setPackagesToImport(Collections.singletonList(packageName));
            importer.applyRules(true);
        } catch (IOException e) {
            throw new BackupException("Failed to restore rules file.", e);
        }
    }

    @NonNull
    private Path[] getSourceFiles(@NonNull Path backupPath) {
        String mode = CryptoUtils.getExtension(metadata.crypto);
        return backupPath.listFiles((dir, name) -> name.startsWith(SOURCE_PREFIX) && name.endsWith(mode));
    }

    private void deleteFiles(@NonNull Path[] files) {
        for (Path file : files) {
            file.delete();
        }
    }

    @NonNull
    private Path[] getKeyStoreFiles(@NonNull Path backupPath) {
        String mode = CryptoUtils.getExtension(metadata.crypto);
        return backupPath.listFiles((dir, name) -> name.startsWith(KEYSTORE_PREFIX) && name.endsWith(mode));
    }

    @NonNull
    private Path[] getDataFiles(@NonNull Path backupPath, int index) {
        String mode = CryptoUtils.getExtension(metadata.crypto);
        final String dataPrefix = DATA_PREFIX + index;
        return backupPath.listFiles((dir, name) -> name.startsWith(dataPrefix) && name.endsWith(mode));
    }

    @NonNull
    private Path[] decrypt(@NonNull Path[] files) throws IOException {
        Path[] newFiles;
        synchronized (Crypto.class) {
            crypto.decrypt(files);
            newFiles = crypto.getNewFiles();
        }
        decryptedFiles.addAll(Arrays.asList(newFiles));
        return newFiles.length > 0 ? newFiles : files;
    }
}