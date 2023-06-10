// SPDX-License-Identifier: Apache-2.0 OR GPL-3.0-or-later

package io.github.muntashirakon.AppManager.apk.signing;

import androidx.annotation.NonNull;

import com.reandroid.archive2.Archive;
import com.reandroid.archive2.ArchiveEntry;
import com.reandroid.archive2.writer.ApkWriter;
import com.reandroid.archive2.writer.ZipAligner;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.io.Paths;

public class ZipAlign {
    public static final String TAG = ZipAlign.class.getSimpleName();

    public static final int ALIGNMENT_4 = 4;

    private static final int ALIGNMENT_PAGE = 4096;

    public static void align(@NonNull File input, @NonNull File output, int alignment, boolean pageAlignSharedLibs)
            throws IOException {
        File dir = output.getParentFile();
        if (!Paths.exists(dir)) {
            dir.mkdirs();
        }
        Archive archive = new Archive(input);
        try (ApkWriter apkWriter = new ApkWriter(output, archive.mapEntrySource().values())) {
            apkWriter.setZipAligner(getZipAligner(alignment, pageAlignSharedLibs));
            apkWriter.write();
        }
        if (!verify(output, alignment, pageAlignSharedLibs)) {
            throw new IOException("Could not verify aligned APK file.");
        }
    }

    public static void align(@NonNull File inFile, int alignment, boolean pageAlignSharedLibs) throws IOException {
        File tmp = toTmpFile(inFile);
        tmp.delete();
        try {
            align(inFile, tmp, alignment, pageAlignSharedLibs);
            inFile.delete();
            tmp.renameTo(inFile);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
    }

    public static boolean verify(@NonNull File file, int alignment, boolean pageAlignSharedLibs) {
        Archive zipFile;
        boolean foundBad = false;
        Log.d(TAG, String.format(Locale.ROOT, "Verifying alignment of %s...\n", file));

        try {
            zipFile = new Archive(file);
        } catch (IOException e) {
            Log.e(TAG, String.format(Locale.ROOT, "Unable to open '%s' for verification\n", file), e);
            return false;
        }
        List<ArchiveEntry> entries = zipFile.getEntryList();
        for (ArchiveEntry pEntry : entries) {
            String name = pEntry.getName();
            long fileOffset = pEntry.getFileOffset();
            if (pEntry.getMethod() == ZipEntry.DEFLATED) {
                Log.d(TAG, String.format(Locale.ROOT, "%8d %s (OK - compressed)\n", fileOffset, name));
            } else if (pEntry.isDirectory()) {
                // Directory entries do not need to be aligned.
                Log.d(TAG, String.format(Locale.ROOT, "%8d %s (OK - directory)\n", fileOffset, name));
            } else {
                int alignTo = getAlignment(pEntry, alignment, pageAlignSharedLibs);
                if ((fileOffset % alignTo) != 0) {
                    Log.w(TAG, String.format(Locale.ROOT, "%8d %s (BAD - %d)\n", fileOffset, name, (fileOffset % alignTo)));
                    foundBad = true;
                    break;
                } else {
                    Log.d(TAG, String.format(Locale.ROOT, "%8d %s (OK)\n", fileOffset, name));
                }
            }
        }

        Log.d(TAG, String.format(Locale.ROOT, "Verification %s\n", foundBad ? "FAILED" : "successful"));

        return !foundBad;
    }

    private static int getAlignment(@NonNull ZipEntry entry, int defaultAlignment, boolean pageAlignSharedLibs) {
        if (!pageAlignSharedLibs) {
            return defaultAlignment;
        }
        String name = entry.getName();
        if (name.startsWith("lib/") && name.endsWith(".so")) {
            return ALIGNMENT_PAGE;
        } else {
            return defaultAlignment;
        }
    }

    @NonNull
    public static ZipAligner getZipAligner(int defaultAlignment, boolean pageAlignSharedLibs) {
        ZipAligner zipAligner = new ZipAligner();
        zipAligner.setDefaultAlignment(defaultAlignment);
        if (pageAlignSharedLibs) {
            Pattern patternNativeLib = Pattern.compile("^lib/.+\\.so$");
            zipAligner.setFileAlignment(patternNativeLib, ALIGNMENT_PAGE);
        }
        zipAligner.setEnableDataDescriptor(true);
        return zipAligner;
    }

    @NonNull
    private static File toTmpFile(@NonNull File file) {
        String name = file.getName() + ".align.tmp";
        File dir = file.getParentFile();
        if (dir == null) {
            return new File(name);
        }
        return new File(dir, name);
    }
}
