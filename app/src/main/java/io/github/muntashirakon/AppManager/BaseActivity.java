// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.CallSuper;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import java.util.Objects;

import io.github.muntashirakon.AppManager.crypto.ks.KeyStoreActivity;
import io.github.muntashirakon.AppManager.crypto.ks.KeyStoreManager;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.self.life.BuildExpiryChecker;
import io.github.muntashirakon.AppManager.settings.Ops;
import io.github.muntashirakon.AppManager.settings.SecurityAndOpsViewModel;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.appearance.AppearanceUtils;

public abstract class BaseActivity extends AppCompatActivity {
    public static final String TAG = BaseActivity.class.getSimpleName();

    @Nullable
    private AlertDialog mAlertDialog;
    @Nullable
    private SecurityAndOpsViewModel mViewModel;

    private final ActivityResultLauncher<Intent> mKeyStoreActivity = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                // Need authentication and/or verify mode of operation
                ensureSecurityAndModeOfOp();
            });
    private final ActivityResultLauncher<Intent> mAuthActivity = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    // Success
                    handleMigrationAndModeOfOp();
                } else {
                    // Authentication failed
                    finishAndRemoveTask();
                }
            });

    @Override
    protected final void onCreate(@Nullable Bundle savedInstanceState) {
        AppearanceUtils.applyToActivity(this, getTransparentBackground());
        super.onCreate(savedInstanceState);
        if (Ops.isAuthenticated()) {
            Log.d(TAG, "Already authenticated.");
            onAuthenticated(savedInstanceState);
            return;
        }
        if (Boolean.TRUE.equals(BuildExpiryChecker.buildExpired())) {
            // Build has expired
            BuildExpiryChecker.getBuildExpiredDialog(this).show();
            return;
        }
        // Run authentication
        mViewModel = new ViewModelProvider(this).get(SecurityAndOpsViewModel.class);
        mAlertDialog = UIUtils.getProgressDialog(this, getString(R.string.initializing));
        Log.d(TAG, "Waiting to be authenticated.");
        mViewModel.authenticationStatus().observe(this, status -> {
            switch (status) {
                case Ops.STATUS_AUTO_CONNECT_WIRELESS_DEBUGGING:
                    Log.d(TAG, "Try auto-connecting to wireless debugging.");
                    mAlertDialog = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        mViewModel.autoConnectAdb(Ops.STATUS_WIRELESS_DEBUGGING_CHOOSER_REQUIRED);
                        return;
                    } // fall-through
                case Ops.STATUS_WIRELESS_DEBUGGING_CHOOSER_REQUIRED:
                    Log.d(TAG, "Display wireless debugging chooser (pair or connect)");
                    mAlertDialog = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Ops.connectWirelessDebugging(this, mViewModel);
                        return;
                    } // fall-through
                case Ops.STATUS_ADB_CONNECT_REQUIRED:
                    Log.d(TAG, "Display connect dialog.");
                    mAlertDialog = null;
                    Ops.connectAdbInput(this, mViewModel);
                    return;
                case Ops.STATUS_ADB_PAIRING_REQUIRED:
                    Log.d(TAG, "Display pairing dialog.");
                    mAlertDialog = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Ops.pairAdbInput(this, mViewModel);
                        return;
                    } // fall-through
                case Ops.STATUS_FAILURE_ADB_NEED_MORE_PERMS:
                    Ops.displayIncompleteUsbDebuggingMessage(this);
                case Ops.STATUS_SUCCESS:
                case Ops.STATUS_FAILURE:
                    Log.d(TAG, "Authentication completed.");
                    mViewModel.setAuthenticating(false);
                    if (mAlertDialog != null) mAlertDialog.dismiss();
                    Ops.setAuthenticated(true);
                    onAuthenticated(savedInstanceState);
            }
        });
        if (!mViewModel.isAuthenticating()) {
            mViewModel.setAuthenticating(true);
            authenticate();
        }
    }

    protected abstract void onAuthenticated(@Nullable Bundle savedInstanceState);

    protected boolean getTransparentBackground() {
        return false;
    }

    @CallSuper
    @SuppressLint("RestrictedApi")
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @CallSuper
    @Override
    protected void onStart() {
        super.onStart();
        if (mViewModel != null && mViewModel.isAuthenticating() && mAlertDialog != null) {
            mAlertDialog.show();
        }
    }

    @CallSuper
    @Override
    protected void onStop() {
        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
        }
        super.onStop();
    }

    protected void clearBackStack() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0) {
            FragmentManager.BackStackEntry entry = fragmentManager.getBackStackEntryAt(0);
            fragmentManager.popBackStackImmediate(entry.getId(), FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    protected void removeCurrentFragment(@IdRes int id) {
        Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if (fragment != null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .remove(fragment)
                    .commit();
        }
    }

    private void authenticate() {
        // Check KeyStore
        if (KeyStoreManager.hasKeyStorePassword()) {
            // We already have a working keystore password.
            // Only need authentication and/or verify mode of operation.
            ensureSecurityAndModeOfOp();
            return;
        }
        Intent keyStoreIntent = new Intent(this, KeyStoreActivity.class)
                .putExtra(KeyStoreActivity.EXTRA_KS, true);
        mKeyStoreActivity.launch(keyStoreIntent);
    }

    private void ensureSecurityAndModeOfOp() {
        if (!AppPref.getBoolean(AppPref.PrefKey.PREF_ENABLE_SCREEN_LOCK_BOOL)) {
            // No security enabled
            handleMigrationAndModeOfOp();
            return;
        }
        Log.d(TAG, "Security enabled.");
        KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguardManager.isKeyguardSecure()) {
            // Screen lock enabled
            Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(getString(R.string.unlock_app_manager), null);
            mAuthActivity.launch(intent);
        } else {
            // Screen lock disabled
            UIUtils.displayLongToast(R.string.screen_lock_not_enabled);
            finishAndRemoveTask();
        }
    }

    private void handleMigrationAndModeOfOp() {
        // Authentication was successful
        Log.d(TAG, "Authenticated");
        // Set mode of operation
        Objects.requireNonNull(mViewModel).setModeOfOps();
    }
}
