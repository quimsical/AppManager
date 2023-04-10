// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.crypto;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import org.openintents.openpgp.util.OpenPgpApi;

import java.util.Objects;

import io.github.muntashirakon.AppManager.BaseActivity;

public class OpenPGPCryptoActivity extends BaseActivity {
    private final ActivityResultLauncher<IntentSenderRequest> confirmationLauncher = registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                Intent broadcastIntent = new Intent(OpenPGPCrypto.ACTION_OPEN_PGP_INTERACTION_END);
                sendBroadcast(broadcastIntent);
                finish();
            });

    @Override
    public boolean getTransparentBackground() {
        return true;
    }

    @Override
    protected void onAuthenticated(@Nullable Bundle savedInstanceState) {
        if (getIntent() != null) onNewIntent(getIntent());
        else finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        PendingIntent pi = Objects.requireNonNull(intent.getParcelableExtra(OpenPgpApi.RESULT_INTENT));
        confirmationLauncher.launch(new IntentSenderRequest.Builder(pi).build());
    }
}
