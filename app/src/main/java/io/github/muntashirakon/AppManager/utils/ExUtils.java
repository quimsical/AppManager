// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

import io.github.muntashirakon.AppManager.backup.BackupException;
import io.github.muntashirakon.AppManager.logs.Log;

public class ExUtils {
    public interface ThrowingRunnable<T> {
        T run() throws Throwable;
    }

    public interface ThrowingRunnableNoReturn {
        void run() throws Throwable;
    }

    public static <T> T rethrowAsIOException(@NonNull Throwable e) throws IOException {
        IOException ioException = new IOException(e.getMessage());
        //noinspection UnnecessaryInitCause
        ioException.initCause(e);
        throw ioException;
    }

    public static <T> T rethrowAsBackupException(@NonNull String message, @NonNull Throwable e) throws BackupException {
        BackupException backupException = new BackupException(message);
        //noinspection UnnecessaryInitCause
        backupException.initCause(e);
        throw backupException;
    }

    @Nullable
    public static <T> T exceptionAsNull(ThrowingRunnable<T> r) {
        try {
            return r.run();
        } catch (Throwable th) {
            Log.e("ExUtils", "(Suppressed error)", th);
            return null;
        }
    }

    @Nullable
    public static <T> T asRuntimeException(ThrowingRunnable<T> r) {
        try {
            return r.run();
        } catch (Throwable th) {
            throw new RuntimeException(th);
        }
    }

    public static void exceptionAsIgnored(ThrowingRunnableNoReturn r) {
        try {
            r.run();
        } catch (Throwable th) {
            Log.e("ExUtils", "(Suppressed error)", th);
        }
    }
}
