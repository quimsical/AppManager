// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.apk.explorer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.fm.FmProvider;
import io.github.muntashirakon.AppManager.intercept.IntentCompat;
import io.github.muntashirakon.AppManager.scanner.ClassViewerActivity;

public class AppExplorerActivity extends BaseActivity {
    AppExplorerViewModel model;

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_fm);
        setSupportActionBar(findViewById(R.id.toolbar));
        model = new ViewModelProvider(this).get(AppExplorerViewModel.class);
        findViewById(R.id.progress_linear).setVisibility(View.GONE);
        Uri uri = IntentCompat.getDataUri(getIntent());
        if (uri == null) {
            finish();
            return;
        }
        model.setUri(uri);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(model.getName());
        }
        loadNewFragment(AppExplorerFragment.getNewInstance(null));
        model.observeModification().observe(this, modified -> {
            if (actionBar != null) actionBar.setTitle("* " + model.getName());
        });
        model.observeOpen().observe(this, adapterItem -> {
            Intent intent;
            if ("smali".equals(adapterItem.path.getExtension())) {
                intent = new Intent(this, ClassViewerActivity.class);
                intent.putExtra(ClassViewerActivity.EXTRA_APP_NAME, model.getName());
                intent.putExtra(ClassViewerActivity.EXTRA_URI, adapterItem.getUri());
            } else {
                if (adapterItem.getCachedFile() == null) return;
                intent = new Intent(Intent.ACTION_VIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .setDataAndType(FmProvider.getContentUri(adapterItem.getCachedFile()), adapterItem.getType());
            }
            startActivity(intent);
        });
        model.observeUriChange().observe(this, newUri -> {
            if (newUri != null) loadNewFragment(AppExplorerFragment.getNewInstance(newUri));
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 1) {
            super.onBackPressed();
        } else if (model.isModified()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.exit_confirmation)
                    .setMessage(R.string.are_you_sure)
                    .setCancelable(false)
                    .setPositiveButton(R.string.no, null)
                    .setPositiveButton(R.string.yes, (dialog, which) -> finish())
//                    .setNeutralButton(R.string.save_project, (dialog, which) -> {
//                        // TODO: 10/10/21
//                    })
                    .show();
        } else finish();
    }

    public void loadNewFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.main_layout, fragment)
                .addToBackStack(null)
                .commit();
    }
}
