// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.collection.ArrayMap;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;

import com.google.android.material.transition.MaterialSharedAxis;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.misc.DeviceInfo2;
import io.github.muntashirakon.AppManager.servermanager.ServerConfig;
import io.github.muntashirakon.AppManager.utils.LangUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.appearance.AppearanceUtils;
import io.github.muntashirakon.dialog.AlertDialogBuilder;
import io.github.muntashirakon.dialog.SearchableSingleChoiceDialogBuilder;
import io.github.muntashirakon.dialog.TextInputDialogBuilder;

public class MainPreferences extends PreferenceFragment {
    @NonNull
    public static MainPreferences getInstance(@Nullable String key) {
        MainPreferences preferences = new MainPreferences();
        Bundle args = new Bundle();
        args.putString(PREF_KEY, key);
        preferences.setArguments(args);
        return preferences;
    }

    private static final List<String> MODE_NAMES = Arrays.asList(
            Ops.MODE_AUTO,
            Ops.MODE_ROOT,
            Ops.MODE_ADB_OVER_TCP,
            Ops.MODE_ADB_WIFI,
            Ops.MODE_NO_ROOT);

    private FragmentActivity activity;
    private String currentLang;
    @Ops.Mode
    private String currentMode;
    private MainPreferencesViewModel model;
    private AlertDialog modeOfOpsAlertDialog;
    private Preference modePref;
    private String[] modes;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey);
        getPreferenceManager().setPreferenceDataStore(new SettingsDataStore());
        model = new ViewModelProvider(requireActivity()).get(MainPreferencesViewModel.class);
        activity = requireActivity();
        // Custom locale
        currentLang = Prefs.Appearance.getLanguage();
        ArrayMap<String, Locale> locales = LangUtils.getAppLanguages(activity);
        final CharSequence[] languageNames = getLanguagesL(locales);
        final String[] languages = new String[languageNames.length];
        for (int i = 0; i < locales.size(); ++i) {
            languages[i] = locales.keyAt(i);
        }
        int localeIndex = locales.indexOfKey(currentLang);
        if (localeIndex < 0) {
            localeIndex = locales.indexOfKey(LangUtils.LANG_AUTO);
        }
        Preference locale = Objects.requireNonNull(findPreference("custom_locale"));
        locale.setSummary(languageNames[localeIndex]);
        int finalLocaleIndex = localeIndex;
        locale.setOnPreferenceClickListener(preference -> {
            new SearchableSingleChoiceDialogBuilder<>(activity, languages, languageNames)
                    .setTitle(R.string.choose_language)
                    .setSelectionIndex(finalLocaleIndex)
                    .setPositiveButton(R.string.apply, (dialog, which, selectedItem) -> {
                        if (selectedItem != null) {
                            currentLang = selectedItem;
                            Prefs.Appearance.setLanguage(currentLang);
                            AppearanceUtils.applyConfigurationChangesToActivities();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
        // Mode of operation
        modePref = Objects.requireNonNull(findPreference("mode_of_operations"));
        modeOfOpsAlertDialog = UIUtils.getProgressDialog(activity, getString(R.string.loading));
        modes = getResources().getStringArray(R.array.modes);
        currentMode = Ops.getMode();
        modePref.setSummary(getString(R.string.mode_of_op_with_inferred_mode_of_op, modes[MODE_NAMES.indexOf(currentMode)],
                getInferredMode()));
        modePref.setOnPreferenceClickListener(preference -> {
            new SearchableSingleChoiceDialogBuilder<>(activity, MODE_NAMES, modes)
                    .setTitle(R.string.pref_mode_of_operations)
                    .setSelection(currentMode)
                    .addDisabledItems(Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                            Collections.singletonList(Ops.MODE_ADB_WIFI) : Collections.emptyList())
                    .setPositiveButton(R.string.apply, (dialog, which, selectedItem) -> {
                        if (selectedItem != null) {
                            currentMode = selectedItem;
                            if (Ops.MODE_ADB_OVER_TCP.equals(currentMode)) {
                                ServerConfig.setAdbPort(ServerConfig.DEFAULT_ADB_PORT);
                            }
                            Ops.setMode(currentMode);
                            modePref.setSummary(modes[MODE_NAMES.indexOf(currentMode)]);
                            modeOfOpsAlertDialog.show();
                            model.setModeOfOps();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });
        // VT API key
        ((Preference) Objects.requireNonNull(findPreference("vt_apikey"))).setOnPreferenceClickListener(preference -> {
            new TextInputDialogBuilder(activity, null)
                    .setTitle(R.string.pref_vt_apikey)
                    .setHelperText(getString(R.string.pref_vt_apikey_description) + "\n\n" + getString(R.string.vt_disclaimer))
                    .setInputText(Prefs.VirusTotal.getApiKey())
                    .setCheckboxLabel(R.string.pref_vt_prompt_before_uploading)
                    .setChecked(Prefs.VirusTotal.promptBeforeUpload())
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.save, (dialog, which, inputText, isChecked) -> {
                        if (inputText != null) {
                            Prefs.VirusTotal.setApiKey(inputText.toString());
                        }
                        Prefs.VirusTotal.setPromptBeforeUpload(isChecked);
                    })
                    .show();
            return true;
        });
        // About device
        ((Preference) Objects.requireNonNull(findPreference("about_device")))
                .setOnPreferenceClickListener(preference -> {
                    model.loadDeviceInfo(new DeviceInfo2(activity));
                    return true;
                });

        // Hide preferences for disabled features
        if (!FeatureController.isInstallerEnabled()) {
            ((Preference) Objects.requireNonNull(findPreference("installer"))).setVisible(false);
        }
        if (!FeatureController.isLogViewerEnabled()) {
            ((Preference) Objects.requireNonNull(findPreference("log_viewer_prefs"))).setVisible(false);
        }
        model.getOperationCompletedLiveData().observe(requireActivity(), completed -> {
            if (requireActivity() instanceof SettingsActivity) {
                ((SettingsActivity) requireActivity()).progressIndicator.hide();
            }
            Toast.makeText(activity, R.string.the_operation_was_successful, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
        setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Preference loaders
        // Mode of ops
        model.getModeOfOpsStatus().observe(getViewLifecycleOwner(), status -> {
            switch (status) {
                case Ops.STATUS_AUTO_CONNECT_WIRELESS_DEBUGGING:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        model.autoConnectAdb(Ops.STATUS_WIRELESS_DEBUGGING_CHOOSER_REQUIRED);
                        return;
                    } // fall-through
                case Ops.STATUS_WIRELESS_DEBUGGING_CHOOSER_REQUIRED:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        modeOfOpsAlertDialog.dismiss();
                        Ops.connectWirelessDebugging(activity, model);
                        return;
                    } // fall-through
                case Ops.STATUS_ADB_CONNECT_REQUIRED:
                    modeOfOpsAlertDialog.dismiss();
                    Ops.connectAdbInput(activity, model);
                    return;
                case Ops.STATUS_ADB_PAIRING_REQUIRED:
                    modeOfOpsAlertDialog.dismiss();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Ops.pairAdbInput(activity, model);
                        return;
                    } // fall-through
                case Ops.STATUS_FAILURE_ADB_NEED_MORE_PERMS:
                    Ops.displayIncompleteUsbDebuggingMessage(requireActivity());
                case Ops.STATUS_SUCCESS:
                case Ops.STATUS_FAILURE:
                    modeOfOpsAlertDialog.dismiss();
                    modePref.setSummary(getString(R.string.mode_of_op_with_inferred_mode_of_op,
                            modes[MODE_NAMES.indexOf(currentMode)], getInferredMode()));
            }
        });
        // Device info
        model.getDeviceInfo().observe(getViewLifecycleOwner(), deviceInfo -> {
            View v = View.inflate(activity, io.github.muntashirakon.ui.R.layout.dialog_scrollable_text_view, null);
            ((TextView) v.findViewById(android.R.id.content)).setText(deviceInfo.toLocalizedString(activity));
            v.findViewById(android.R.id.checkbox).setVisibility(View.GONE);
            new AlertDialogBuilder(activity, true).setTitle(R.string.about_device).setView(v).show();
        });
    }

    @Override
    public int getTitle() {
        return R.string.settings;
    }

    @NonNull
    private CharSequence[] getLanguagesL(@NonNull ArrayMap<String, Locale> locales) {
        CharSequence[] localesL = new CharSequence[locales.size()];
        Locale locale;
        for (int i = 0; i < locales.size(); ++i) {
            locale = locales.valueAt(i);
            if (LangUtils.LANG_AUTO.equals(locales.keyAt(i))) {
                localesL[i] = activity.getString(R.string.auto);
            } else localesL[i] = locale.getDisplayName(locale);
        }
        return localesL;
    }

    @NonNull
    private CharSequence getInferredMode() {
        if (Ops.isRoot()) {
            return getString(R.string.root);
        }
        if (Ops.isAdb()) {
            return "ADB";
        }
        return getString(R.string.no_root);
    }
}
