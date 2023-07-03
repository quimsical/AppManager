// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.UserHandleHidden;
import android.text.SpannableStringBuilder;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.transition.MaterialSharedAxis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.db.entity.App;
import io.github.muntashirakon.AppManager.db.utils.AppDb;
import io.github.muntashirakon.AppManager.oneclickops.ItemCount;
import io.github.muntashirakon.AppManager.rules.RulesTypeSelectionDialogFragment;
import io.github.muntashirakon.AppManager.rules.compontents.ExternalComponentsImporter;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.PackageUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.dialog.SearchableItemsDialogBuilder;
import io.github.muntashirakon.dialog.SearchableMultiChoiceDialogBuilder;

public class ImportExportRulesPreferences extends PreferenceFragment {
    private static final String MIME_JSON = "application/json";
    private static final String MIME_TSV = "text/tab-separated-values";
    private static final String MIME_XML = "text/xml";

    private final int mUserHandle = UserHandleHidden.myUserId();
    private SettingsActivity mActivity;
    private final ActivityResultLauncher<String> mExportRules = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(MIME_TSV),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                RulesTypeSelectionDialogFragment dialogFragment = new RulesTypeSelectionDialogFragment();
                Bundle args = new Bundle();
                args.putInt(RulesTypeSelectionDialogFragment.ARG_MODE, RulesTypeSelectionDialogFragment.MODE_EXPORT);
                args.putParcelable(RulesTypeSelectionDialogFragment.ARG_URI, uri);
                args.putStringArrayList(RulesTypeSelectionDialogFragment.ARG_PKG, null);
                args.putIntArray(RulesTypeSelectionDialogFragment.ARG_USERS, Users.getUsersIds());
                dialogFragment.setArguments(args);
                dialogFragment.show(getParentFragmentManager(), RulesTypeSelectionDialogFragment.TAG);
            });
    private final ActivityResultLauncher<String> mImportRules = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) {
                    // Back button pressed.
                    return;
                }
                RulesTypeSelectionDialogFragment dialogFragment = new RulesTypeSelectionDialogFragment();
                Bundle args = new Bundle();
                args.putInt(RulesTypeSelectionDialogFragment.ARG_MODE, RulesTypeSelectionDialogFragment.MODE_IMPORT);
                args.putParcelable(RulesTypeSelectionDialogFragment.ARG_URI, uri);
                args.putStringArrayList(RulesTypeSelectionDialogFragment.ARG_PKG, null);
                args.putIntArray(RulesTypeSelectionDialogFragment.ARG_USERS, Users.getUsersIds());
                dialogFragment.setArguments(args);
                dialogFragment.show(getParentFragmentManager(), RulesTypeSelectionDialogFragment.TAG);
            });
    private final ActivityResultLauncher<String> mImportFromWatt = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(),
            uris -> {
                if (uris == null) {
                    // Back button pressed.
                    return;
                }
                new Thread(() -> {
                    List<String> failedFiles = ExternalComponentsImporter.applyFromWatt(uris, Users.getUsersIds());
                    if (isDetached()) return;
                    mActivity.runOnUiThread(() -> displayImportExternalRulesFailedPackagesDialog(failedFiles));
                }).start();
            });
    private final ActivityResultLauncher<String> mImportFromBlocker = registerForActivityResult(
            new ActivityResultContracts.GetMultipleContents(),
            uris -> {
                if (uris == null) {
                    // Back button pressed.
                    return;
                }
                new Thread(() -> {
                    List<String> failedFiles = ExternalComponentsImporter.applyFromBlocker(uris, Users.getUsersIds());
                    if (isDetached()) return;
                    mActivity.runOnUiThread(() -> displayImportExternalRulesFailedPackagesDialog(failedFiles));
                }).start();
            });

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.preferences_rules_import_export);
        getPreferenceManager().setPreferenceDataStore(new SettingsDataStore());
        mActivity = (SettingsActivity) requireActivity();
        ((Preference) Objects.requireNonNull(findPreference("export")))
                .setOnPreferenceClickListener(preference -> {
                    final String fileName = "app_manager_rules_export-" + DateUtils.formatDateTime(mActivity, System.currentTimeMillis()) + ".am.tsv";
                    mExportRules.launch(fileName);
                    return true;
                });
        ((Preference) Objects.requireNonNull(findPreference("import")))
                .setOnPreferenceClickListener(preference -> {
                    mImportRules.launch(MIME_TSV);
                    return true;
                });
        ((Preference) Objects.requireNonNull(findPreference("import_existing")))
                .setOnPreferenceClickListener(preference -> {
                    new MaterialAlertDialogBuilder(requireActivity())
                            .setTitle(R.string.pref_import_existing)
                            .setMessage(R.string.apply_to_system_apps_question)
                            .setPositiveButton(R.string.no, (dialog, which) -> importExistingRules(false))
                            .setNegativeButton(R.string.yes, ((dialog, which) -> importExistingRules(true)))
                            .show();
                    return true;
                });
        ((Preference) Objects.requireNonNull(findPreference("import_watt")))
                .setOnPreferenceClickListener(preference -> {
                    mImportFromWatt.launch(MIME_XML);
                    return true;
                });
        ((Preference) Objects.requireNonNull(findPreference("import_blocker")))
                .setOnPreferenceClickListener(preference -> {
                    mImportFromBlocker.launch(MIME_JSON);
                    return true;
                });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
        setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));
    }

    @Override
    public int getTitle() {
        return R.string.pref_import_export_blocking_rules;
    }

    private void importExistingRules(final boolean systemApps) {
        if (!SelfPermissions.canModifyAppComponentStates(UserHandleHidden.myUserId(), null, true)) {
            Toast.makeText(requireContext(), R.string.only_works_in_root_or_adb_mode, Toast.LENGTH_SHORT).show();
            return;
        }
        mActivity.progressIndicator.show();
        new Thread(() -> {
            final List<ItemCount> itemCounts = new ArrayList<>();
            ItemCount itemCount;
            for (App app : new AppDb().getAllInstalledApplications()) {
                if (isDetached()) return;
                if (!systemApps && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    continue;
                itemCount = new ItemCount();
                itemCount.packageName = app.packageName;
                itemCount.packageLabel = app.packageLabel;
                itemCount.count = PackageUtils.getUserDisabledComponentsForPackage(app.packageName, mUserHandle).size();
                if (itemCount.count > 0) itemCounts.add(itemCount);
            }
            ThreadUtils.postOnMainThread(() -> displayImportExistingRulesPackageSelectionDialog(itemCounts));
        }).start();
    }

    private void displayImportExistingRulesPackageSelectionDialog(@NonNull List<ItemCount> itemCounts) {
        if (!itemCounts.isEmpty()) {
            final List<String> packages = new ArrayList<>();
            final CharSequence[] packagesWithItemCounts = new CharSequence[itemCounts.size()];
            ItemCount itemCount;
            for (int i = 0; i < itemCounts.size(); ++i) {
                itemCount = itemCounts.get(i);
                packages.add(itemCount.packageName);
                packagesWithItemCounts[i] = new SpannableStringBuilder(itemCount.packageLabel).append("\n")
                        .append(UIUtils.getSmallerText(UIUtils.getSecondaryText(requireContext(), getResources()
                                .getQuantityString(R.plurals.no_of_components, itemCount.count,
                                        itemCount.count))));
            }
            mActivity.progressIndicator.hide();
            new SearchableMultiChoiceDialogBuilder<>(requireActivity(), packages, packagesWithItemCounts)
                    .setTitle(R.string.filtered_packages)
                    .setPositiveButton(R.string.apply, (dialog, which, selectedPackages) -> {
                        mActivity.progressIndicator.show();
                        new Thread(() -> {
                            List<String> failedPackages = ExternalComponentsImporter
                                    .applyFromExistingBlockList(selectedPackages, mUserHandle);
                            ThreadUtils.postOnMainThread(() -> displayImportExistingRulesFailedPackagesDialog(failedPackages));
                        }).start();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            mActivity.progressIndicator.hide();
            Toast.makeText(requireContext(), R.string.no_matching_package_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void displayImportExistingRulesFailedPackagesDialog(@NonNull List<String> failedPackages) {
        mActivity.progressIndicator.hide();
        if (failedPackages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.the_import_was_successful, Toast.LENGTH_SHORT).show();
            return;
        }
        new SearchableItemsDialogBuilder<>(requireActivity(), failedPackages)
                .setTitle(R.string.failed_packages)
                .setNegativeButton(R.string.ok, null)
                .show();
    }

    private void displayImportExternalRulesFailedPackagesDialog(@NonNull List<String> failedFiles) {
        mActivity.progressIndicator.hide();
        if (failedFiles.isEmpty()) {
            Toast.makeText(requireContext(), R.string.the_import_was_successful, Toast.LENGTH_SHORT).show();
            return;
        }
        new SearchableItemsDialogBuilder<>(requireActivity(), failedFiles)
                .setTitle(getResources().getQuantityString(R.plurals.failed_to_import_files, failedFiles.size(),
                        failedFiles.size()))
                .setNegativeButton(R.string.close, null)
                .show();
    }
}
