// SPDX-License-Identifier: MIT

package io.github.muntashirakon.AppManager.servermanager;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.SocketTimeoutException;

import io.github.muntashirakon.AppManager.server.common.DataTransmission;

/**
 * Describes why establishing the local server connection failed.
 */
public final class ServerConnectionFailure extends IOException {
    public enum Reason {
        TRANSPORT,
        SERVER_UNRESPONSIVE,
        AUTHENTICATION,
        PROTOCOL_MISMATCH,
        SERVER_START
    }

    @NonNull
    private final Reason mReason;

    ServerConnectionFailure(@NonNull String message, @NonNull Reason reason,
                            @NonNull Throwable cause) {
        super(message, cause);
        mReason = reason;
    }

    @NonNull
    public Reason getReason() {
        return mReason;
    }

    @NonNull
    static ServerConnectionFailure from(@NonNull Throwable failure, boolean startingServer) {
        Reason reason;
        if (failure instanceof DataTransmission.ProtocolVersionException) {
            reason = Reason.PROTOCOL_MISMATCH;
        } else if (failure instanceof SocketTimeoutException) {
            reason = Reason.SERVER_UNRESPONSIVE;
        } else if (isAuthenticationFailure(failure)) {
            reason = Reason.AUTHENTICATION;
        } else if (startingServer) {
            reason = Reason.SERVER_START;
        } else if (failure instanceof IOException) {
            reason = Reason.TRANSPORT;
        } else {
            reason = Reason.SERVER_START;
        }
        String message = failure.getMessage();
        if (message == null) message = reason.name();
        return new ServerConnectionFailure(message, reason, failure);
    }

    private static boolean isAuthenticationFailure(@NonNull Throwable failure) {
        String message = failure.getMessage();
        return message != null && (message.contains("Unauthorized server")
                || message.contains("Unauthorized client")
                || message.contains("HMAC mismatch"));
    }
}
