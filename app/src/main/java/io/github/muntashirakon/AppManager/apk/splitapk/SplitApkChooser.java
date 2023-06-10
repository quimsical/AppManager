// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.apk.splitapk;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import aosp.libcore.util.EmptyArray;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.ApkFile;
import io.github.muntashirakon.AppManager.compat.BundleCompat;
import io.github.muntashirakon.AppManager.utils.ArrayUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;

public class SplitApkChooser extends DialogFragment {
    public static final String TAG = SplitApkChooser.class.getSimpleName();

    private static final String EXTRA_APK_FILE_KEY = "EXTRA_APK_FILE_KEY";
    private static final String EXTRA_ACTION_NAME = "EXTRA_ACTION_NAME";
    private static final String EXTRA_APP_INFO = "EXTRA_APP_INFO";
    private static final String EXTRA_VERSION_INFO = "EXTRA_VERSION_INFO";

    @NonNull
    public static SplitApkChooser getNewInstance(int apkFileKey, @NonNull ApplicationInfo info,
                                                 @NonNull String versionInfo, @Nullable String actionName,
                                                 @NonNull OnTriggerInstallInterface installInterface) {
        SplitApkChooser splitApkChooser = new SplitApkChooser();
        Bundle args = new Bundle();
        args.putInt(SplitApkChooser.EXTRA_APK_FILE_KEY, apkFileKey);
        args.putString(SplitApkChooser.EXTRA_ACTION_NAME, actionName);
        args.putParcelable(SplitApkChooser.EXTRA_APP_INFO, info);
        args.putString(SplitApkChooser.EXTRA_VERSION_INFO, versionInfo);
        splitApkChooser.setArguments(args);
        splitApkChooser.setCancelable(false);
        splitApkChooser.setOnTriggerInstall(installInterface);
        return splitApkChooser;
    }

    public interface OnTriggerInstallInterface {
        void triggerInstall();

        void triggerCancel();
    }

    @Nullable
    private OnTriggerInstallInterface installInterface;
    private ApkFile apkFile;
    private PackageManager pm;
    private final HashMap<String /* feature */, HashSet<Integer> /* seen types */> seenSplits = new HashMap<>();

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        int apkFileKey = requireArguments().getInt(EXTRA_APK_FILE_KEY, -1);
        String actionName = requireArguments().getString(EXTRA_ACTION_NAME);
        ApplicationInfo appInfo = BundleCompat.getParcelable(requireArguments(), EXTRA_APP_INFO, ApplicationInfo.class);
        String versionInfo = requireArguments().getString(EXTRA_VERSION_INFO);
        pm = requireActivity().getPackageManager();
        if (apkFileKey == -1 || appInfo == null) {
            throw new IllegalArgumentException("ApkFile cannot be empty.");
        }
        apkFile = ApkFile.getInstance(apkFileKey);
        if (!apkFile.isSplit()) {
            throw new RuntimeException("Apk file does not contain any split.");
        }
        List<ApkFile.Entry> apkEntries = apkFile.getEntries();
        CharSequence[] entryNames = new CharSequence[apkEntries.size()];
        for (int i = 0; i < apkEntries.size(); ++i) {
            entryNames[i] = apkEntries.get(i).toLocalizedString(requireActivity());
        }
        if (installInterface == null) {
            throw new RuntimeException("No install action has been set.");
        }
        SearchableMultiChoiceDialogBuilder<ApkFile.Entry> builder = new SearchableMultiChoiceDialogBuilder<>(
                requireActivity(), apkEntries, entryNames)
                .setTitle(UIUtils.getDialogTitle(requireActivity(), pm.getApplicationLabel(appInfo),
                        pm.getApplicationIcon(appInfo), versionInfo))
                .showSelectAll(false)
                .addSelections(getInitialSelections(apkEntries))
                .addDisabledItems(getUnsupportedOrRequiredSplits())
                .setPositiveButton(actionName == null ? getString(R.string.install) : actionName, (dialog, which, selectedItems) ->
                        installInterface.triggerInstall())
                .setNegativeButton(R.string.cancel, (dialog, which, selectedItems) -> onCancel(dialog))
                .setCancelable(false);
        return builder.setOnMultiChoiceClickListener((dialog, which, item, isChecked) -> {
            if (isChecked) {
                builder.addSelections(select(which));
            } else {
                int[] itemsToDeselect = deselect(which);
                if (itemsToDeselect == null) {
                    // Reselect the item
                    builder.addSelections(new int[]{which});
                } else {
                    builder.removeSelections(itemsToDeselect);
                }
            }
            builder.reloadListUi();
        }).create();
    }

    @Override
    public void onDestroy() {
        apkFile.close();
        super.onDestroy();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        Objects.requireNonNull(installInterface).triggerCancel();
    }

    public void setOnTriggerInstall(@Nullable OnTriggerInstallInterface installInterface) {
        this.installInterface = installInterface;
    }

    @NonNull
    public int[] getInitialSelections(@NonNull final List<ApkFile.Entry> apkEntries) {
        List<Integer> selections = new ArrayList<>();
        try {
            // See if the app has been installed
            ApplicationInfo info = pm.getApplicationInfo(apkFile.getPackageName(), 0);
            HashSet<String> splitNames = new HashSet<>();
            try (ApkFile installedApkFile = ApkFile.getInstance(ApkFile.createInstance(info))) {
                for (ApkFile.Entry apkEntry : installedApkFile.getEntries()) {
                    splitNames.add(apkEntry.name);
                }
            }
            if (splitNames.size() > 0) {
                for (int i = 0; i < apkEntries.size(); ++i) {
                    ApkFile.Entry apkEntry = apkEntries.get(i);
                    if (splitNames.contains(apkEntry.name) || apkEntry.type == ApkFile.APK_BASE) {
                        HashSet<Integer> types = seenSplits.get(apkEntry.getFeature());
                        if (types == null) {
                            types = new HashSet<>();
                            seenSplits.put(apkEntry.getFeature(), types);
                        }
                        types.add(apkEntry.type);
                        apkFile.select(i);
                    }
                }
                // Fall-through deliberately to see if there are any new requirements
            }
        } catch (PackageManager.NameNotFoundException | ApkFile.ApkFileException ignored) {
        }
        // Set up features
        for (int i = 0; i < apkEntries.size(); ++i) {
            ApkFile.Entry apkEntry = apkEntries.get(i);
            if (apkEntry.isSelected() || apkEntry.isRequired()) {
                selections.add(i);
                if (apkEntry.type == ApkFile.APK_BASE || apkEntry.type == ApkFile.APK_SPLIT_FEATURE) {
                    // Set feature
                    if (seenSplits.get(apkEntry.getFeature()) == null) {
                        seenSplits.put(apkEntry.getFeature(), new HashSet<>());
                    }
                }
            }
        }
        // Only selecting the first item works because the splits are already ranked.
        for (int i = 0; i < apkEntries.size(); ++i) {
            ApkFile.Entry apkEntry = apkEntries.get(i);
            if (apkEntry.isSelected() || apkEntry.isRequired()) {
                // Already selected
                continue;
            }
            HashSet<Integer> types = seenSplits.get(apkEntry.getFeature());
            if (types == null) {
                // This feature was not selected earlier
                continue;
            }
            switch (apkEntry.type) {
                case ApkFile.APK_BASE:
                case ApkFile.APK_SPLIT_FEATURE:
                case ApkFile.APK_SPLIT_UNKNOWN:
                case ApkFile.APK_SPLIT:
                    break;
                case ApkFile.APK_SPLIT_DENSITY:
                    if (!types.contains(ApkFile.APK_SPLIT_DENSITY)) {
                        types.add(ApkFile.APK_SPLIT_DENSITY);
                        selections.add(i);
                        apkFile.select(i);
                    }
                    break;
                case ApkFile.APK_SPLIT_ABI:
                    if (!types.contains(ApkFile.APK_SPLIT_ABI)) {
                        types.add(ApkFile.APK_SPLIT_ABI);
                        selections.add(i);
                        apkFile.select(i);
                    }
                    break;
                case ApkFile.APK_SPLIT_LOCALE:
                    if (!types.contains(ApkFile.APK_SPLIT_LOCALE)) {
                        types.add(ApkFile.APK_SPLIT_LOCALE);
                        selections.add(i);
                        apkFile.select(i);
                    }
                    break;
                default:
                    throw new RuntimeException("Invalid split type.");
            }
        }
        return ArrayUtils.convertToIntArray(selections);
    }

    @NonNull
    private List<ApkFile.Entry> getUnsupportedOrRequiredSplits() {
        List<ApkFile.Entry> apkEntries = apkFile.getEntries();
        List<ApkFile.Entry> unsupportedOrRequiredSplits = new ArrayList<>();
        for (ApkFile.Entry apkEntry : apkEntries) {
            if (!apkEntry.supported() || apkEntry.isRequired()) {
                unsupportedOrRequiredSplits.add(apkEntry);
            }
        }
        return unsupportedOrRequiredSplits;
    }

    @NonNull
    private int[] select(int index) {
        List<Integer> selections = new ArrayList<>();
        apkFile.select(index);
        List<ApkFile.Entry> apkEntries = apkFile.getEntries();
        ApkFile.Entry entry = apkEntries.get(index);
        String feature = entry.getFeature();
        HashSet<Integer> types = seenSplits.get(feature);
        if (types == null) {
            types = new HashSet<>();
            seenSplits.put(feature, types);
        }
        for (int i = 0; i < apkEntries.size(); ++i) {
            ApkFile.Entry apkEntry = apkEntries.get(i);
            if (Objects.equals(apkEntry.getFeature(), feature) && apkEntry.type != entry.type) {
                if (apkEntry.isSelected()) {
                    // Deselect unwanted items
                    apkFile.deselect(i);
                }
                // Select wanted items only
                switch (apkEntry.type) {
                    case ApkFile.APK_BASE:
                    case ApkFile.APK_SPLIT_FEATURE:
                        apkFile.select(i);
                        break;
                    case ApkFile.APK_SPLIT_UNKNOWN:
                    case ApkFile.APK_SPLIT:
                        break;
                    case ApkFile.APK_SPLIT_DENSITY:
                        if (!types.contains(ApkFile.APK_SPLIT_DENSITY)) {
                            types.add(ApkFile.APK_SPLIT_DENSITY);
                            selections.add(i);
                            apkFile.select(i);
                        }
                        break;
                    case ApkFile.APK_SPLIT_ABI:
                        if (!types.contains(ApkFile.APK_SPLIT_ABI)) {
                            types.add(ApkFile.APK_SPLIT_ABI);
                            selections.add(i);
                            apkFile.select(i);
                        }
                        break;
                    case ApkFile.APK_SPLIT_LOCALE:
                        if (!types.contains(ApkFile.APK_SPLIT_LOCALE)) {
                            types.add(ApkFile.APK_SPLIT_LOCALE);
                            selections.add(i);
                            apkFile.select(i);
                        }
                        break;
                    default:
                        throw new RuntimeException("Invalid split type.");
                }
            }
        }
        return ArrayUtils.convertToIntArray(selections);
    }

    @Nullable
    private int[] deselect(int index) {
        List<ApkFile.Entry> apkEntries = apkFile.getEntries();
        ApkFile.Entry entry = apkEntries.get(index);
        if (entry.isRequired()) {
            // 1. This is a required split, can't be unselected
            return null;
        }
        boolean featureSplit = entry.type == ApkFile.APK_SPLIT_FEATURE;
        String feature = entry.getFeature();
        if (featureSplit) {
            // 2. If this is a feature split (base.apk is always a required split), unselect all the associated splits
            List<Integer> deselectedSplits = new ArrayList<>();
            seenSplits.remove(feature);
            for (int i = 0; i < apkEntries.size(); ++i) {
                ApkFile.Entry apkEntry = apkEntries.get(i);
                if (Objects.equals(apkEntry.getFeature(), feature)) {
                    // Split has the same feature
                    if (apkEntry.isSelected()) {
                        deselectedSplits.add(i);
                        apkFile.deselect(i);
                    }
                }
            }
            return ArrayUtils.convertToIntArray(deselectedSplits);
        } else {
            // 3. This isn't a feature split. Find all the splits by the same type and see if at least one split is
            // selected. If not, this split can't be unselected.
            boolean selectedAnySplits = false;
            for (int i = 0; i < apkEntries.size(); ++i) {
                ApkFile.Entry apkEntry = apkEntries.get(i);
                if (i != index
                        && entry.type == apkEntry.type
                        && Objects.equals(apkEntry.getFeature(), feature)
                        && apkEntry.isSelected()) {
                    // Split has the same type and is selected
                    selectedAnySplits = true;
                    break;
                }
            }
            if (selectedAnySplits) {
                // At least one item is selected, deselect the current one
                apkFile.deselect(index);
                return EmptyArray.INT;
            }
            return null;
        }
    }
}
