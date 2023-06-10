// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.io;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandleHidden;
import android.system.ErrnoException;
import android.system.OsConstants;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.muntashirakon.AppManager.utils.ContextUtils;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.io.fs.VirtualFileSystem;

public final class Paths {
    public static final String TAG = Paths.class.getSimpleName();

    @NonNull
    public static Path getPrimaryPath(@Nullable String path) {
        return get(new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority("com.android.externalstorage.documents")
                .path("/tree/primary:" + (path == null ? "" : path))
                .build());
    }

    @NonNull
    public static Path getUnprivileged(@NonNull File pathName) {
        Path path = null;
        try {
            path = new Path(ContextUtils.getContext(), pathName.getAbsolutePath(), false);
        } catch (RemoteException ignore) {
            // This exception is never called in unprivileged mode.
        }
        // Path is never null because RE is never called.
        return Objects.requireNonNull(path);
    }

    @NonNull
    public static Path getUnprivileged(@NonNull String pathName) {
        Path path = null;
        try {
            path = new Path(ContextUtils.getContext(), pathName, false);
        } catch (RemoteException ignore) {
            // This exception is never called in unprivileged mode.
        }
        // Path is never null because RE is never called.
        return Objects.requireNonNull(path);
    }

    @NonNull
    public static Path get(@NonNull String pathName) {
        return new Path(ContextUtils.getContext(), pathName);
    }

    @NonNull
    public static Path get(@NonNull File pathName) {
        return new Path(ContextUtils.getContext(), pathName.getAbsolutePath());
    }

    @NonNull
    public static Path get(@NonNull Uri pathUri) {
        return new Path(ContextUtils.getContext(), pathUri);
    }

    @NonNull
    public static Path get(@NonNull VirtualFileSystem fs) {
        return new Path(ContextUtils.getContext(), fs);
    }

    @NonNull
    public static Path[] build(@NonNull Path[] base, String... segments) {
        Path[] result = new Path[base.length];
        for (int i = 0; i < base.length; i++) {
            result[i] = build(base[i], segments);
        }
        return result;
    }

    @Nullable
    public static Path build(@NonNull File base, @NonNull String... segments) {
        return build(get(base), segments);
    }

    @Nullable
    public static Path build(@NonNull Path base, @NonNull String... segments) {
        Path cur = base;
        boolean isLfs = cur.getFile() != null;
        try {
            for (String segment : segments) {
                if (isLfs) {
                    cur = get(new File(cur.getFilePath(), segment));
                } else {
                    cur = cur.findFile(segment);
                }
            }
            return cur;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    public static boolean exists(@Nullable String path) {
        return path != null && get(path).exists();
    }

    public static boolean exists(@Nullable File path) {
        return path != null && path.exists();
    }

    /**
     * Replace /storage/emulated with /data/media if the directory is inaccessible
     */
    @NonNull
    public static Path getAccessiblePath(@NonNull Path path) {
        if (!path.getUri().getScheme().equals(ContentResolver.SCHEME_FILE)) {
            // Scheme other than file are already readable at their best notion
            return path;
        }
        if (path.canRead()) {
            return path;
        }
        String pathString = Objects.requireNonNull(path.getFilePath());
        if (pathString.startsWith("/storage/emulated/")) {
            // The only inaccessible path is /storage/emulated/{!myUserId} and it has to be replaced with /data/media/{!myUserId}
            if (!String.format(Locale.ROOT, "/storage/emulated/%d", UserHandleHidden.myUserId()).equals(pathString)) {
                return get(pathString.replaceFirst("/storage/emulated/", "/data/media/"));
            }
        }
        return path;
    }

    @Nullable
    public static String getSanitizedPath(@NonNull String name, boolean omitRoot) {
        // Replace multiple separators with a single separator
        //noinspection RegExpRedundantEscape,RegExpSimplifiable
        name = name.replaceAll("[\\/\\\\]+", File.separator);
        if (name.equals(File.separator)) {
            // Name is a separator AKA root
            return File.separator;
        }
        // Name isn't a root but could still be ../ or ./, we only consider ./ because we cannot allow it
        if (name.startsWith("./")) {
            // Omit ./
            name = name.substring(2);
        }
        // Omit last separator if present, this also means ../ will become ..
        if (name.endsWith(File.separator)) {
            name = name.substring(0, name.length() - 1);
        }
        // Omit root if requested
        if (omitRoot && name.startsWith(File.separator)) {
            name = name.substring(1);
        }
        // At this point, name could contain nothing at all
        return name.isEmpty() ? null : name;
    }

    /**
     * Return the last segment from the given path. If the path has a trailing `/`, it removes it and attempt to find
     * the last segment again. If it contains only `/` or no `/` at all, it returns empty string.
     * <p>
     * TODO: It should return null when no last path segment is found
     *
     * @param path An abstract path, may or may not start and/or end with `/`.
     */
    @AnyThread
    @NonNull
    public static String getLastPathSegment(@NonNull String path) {
        if (path.isEmpty()) return "";
        int lastIndexOfSeparator;
        int lastIndexOfPath;
        for (; ; ) {
            lastIndexOfSeparator = path.lastIndexOf(File.separator);
            if (lastIndexOfSeparator == -1) {
                // There are no `/` in the string, so return as is.
                return path;
            }
            lastIndexOfPath = path.length() - 1;
            if (lastIndexOfSeparator == lastIndexOfPath) {
                // `/` is the last character.
                // Therefore, trim it and find the last path again.
                path = path.substring(0, lastIndexOfPath);
                continue;
            }
            // No more `/` at the end
            break;
        }
        // There are path components, so return the last one.
        return path.substring(lastIndexOfSeparator + 1);
    }

    @NonNull
    public static String removeLastPathSegment(@NonNull String path) {
        if (path.isEmpty()) return "";
        int lastIndexOfSeparator;
        int lastIndexOfPath;
        for (; ; ) {
            lastIndexOfSeparator = path.lastIndexOf(File.separator);
            if (lastIndexOfSeparator == -1) {
                // There are no `/` in the string, so return as is
                return "";
            }
            if (lastIndexOfSeparator == 0 && path.length() == 1) {
                // Only `/` exists
                return File.separator;
            }
            lastIndexOfPath = path.length() - 1;
            if (lastIndexOfSeparator == lastIndexOfPath) {
                // `/` is the last character.
                // Therefore, trim it and find the last path again.
                path = path.substring(0, lastIndexOfPath);
                continue;
            }
            // No more `/` at the end
            break;
        }
        // Remove the last segment
        path = path.substring(0, lastIndexOfSeparator);
        if (path.isEmpty()) {
            return File.separator;
        }
        // Remove `/` from the end
        for (; ; ) {
            lastIndexOfSeparator = path.lastIndexOf(File.separator);
            if (lastIndexOfSeparator == -1) {
                // There are no `/` in the string, so return as is
                return path;
            }
            if (lastIndexOfSeparator == 0 && path.length() == 1) {
                // Only `/` exists
                return File.separator;
            }
            lastIndexOfPath = path.length() - 1;
            if (lastIndexOfSeparator == lastIndexOfPath) {
                // `/` is the last character.
                // Therefore, trim it and find the last path again.
                path = path.substring(0, lastIndexOfPath);
                continue;
            }
            // No more `/` at the end
            break;
        }
        return path;
    }

    @NonNull
    public static String appendPathSegment(@NonNull String path, @NonNull String lastPathSegment) {
        if (lastPathSegment.isEmpty()) {
            return path;
        }
        boolean pathEndsWithSeparator = path.endsWith(File.separator);
        if (lastPathSegment.startsWith(File.separator)) {
            if (lastPathSegment.length() == 1) {
                // There's only a path separator, return path as is
                return path;
            }
            if (!pathEndsWithSeparator) {
                // Path didn't end with a separator but lastPathSegment did
                return path + lastPathSegment;
            } else {
                // Need to remove separator from at least one of the arguments
                lastPathSegment = lastPathSegment.substring(1);
            }
        }
        // lastPathSegment does not have a separator
        if (pathEndsWithSeparator) {
            return path + lastPathSegment;
        } else return path + File.separator + lastPathSegment;
    }

    @AnyThread
    @NonNull
    public static String trimPathExtension(@NonNull String path) {
        String filename = getLastPathSegment(path);
        int lastIndexOfDot = filename.lastIndexOf('.');
        int lastIndexOfPath = filename.length() - 1;
        if (lastIndexOfDot == 0 || lastIndexOfDot == -1 || lastIndexOfDot == lastIndexOfPath) {
            return path;
        }
        return path.substring(0, path.lastIndexOf('.'));
    }

    @AnyThread
    @Nullable
    public static String getPathExtension(@NonNull String path) {
        return getPathExtension(path, true);
    }

    @AnyThread
    @Nullable
    public static String getPathExtension(@NonNull String path, boolean forceLowercase) {
        String str = Paths.getLastPathSegment(path);
        int lastIndexOfDot = str.lastIndexOf('.');
        if (lastIndexOfDot == -1 || lastIndexOfDot == str.length() - 1) return null;
        String extension = str.substring(lastIndexOfDot + 1);
        return forceLowercase ? extension.toLowerCase(Locale.ROOT) : extension;
    }

    public static Uri appendPathSegment(@NonNull Uri uri, @NonNull String lastPathSegment) {
        return new Uri.Builder()
                .scheme(uri.getScheme())
                .authority(uri.getAuthority())
                .path(getSanitizedPath(uri.getPath() + File.separator + lastPathSegment, false))
                .build();
    }

    public static Uri removeLastPathSegment(@NonNull Uri uri) {
        String path = uri.getPath();
        if (path.equals(File.separator)) return uri;
        return new Uri.Builder()
                .scheme(uri.getScheme())
                .authority(uri.getAuthority())
                .path(Paths.removeLastPathSegment(path))
                .build();
    }

    public static long size(@Nullable Path root) {
        if (root == null) {
            return 0;
        }
        if (root.isFile()) {
            return root.length();
        }
        if (root.isSymbolicLink()) {
            return 0;
        }
        if (!root.isDirectory()) {
            // Other types of files aren't supported
            return 0;
        }
        long length = 0;
        Path[] files = root.listFiles();
        for (Path file : files) {
            if (ThreadUtils.isInterrupted()) {
                // Size could be too long
                return length;
            }
            length += size(file);
        }
        return length;
    }

    public static void chmod(@NonNull Path path, int mode) throws ErrnoException {
        ExtendedFile file = path.getFile();
        if (file == null) {
            throw new ErrnoException("Supplied path is not a Linux path.", OsConstants.EBADF);
        }
        file.setMode(mode);
    }

    public static void chown(@NonNull Path path, int uid, int gid) throws ErrnoException {
        ExtendedFile file = path.getFile();
        if (file == null) {
            throw new ErrnoException("Supplied path is not a Linux path.", OsConstants.EBADF);
        }
        file.setUidGid(uid, gid);
    }

    /**
     * Set owner and mode of given path.
     *
     * @param mode to apply through {@code chmod}
     * @param uid  to apply through {@code chown}, or -1 to leave unchanged
     * @param gid  to apply through {@code chown}, or -1 to leave unchanged
     */
    public static void setPermissions(@NonNull Path path, int mode, int uid, int gid) throws ErrnoException {
        chmod(path, mode);
        if (uid >= 0 || gid >= 0) {
            chown(path, uid, gid);
        }
    }

    /**
     * Same as {@link #getAll(Path, Path, String[], String[], boolean)} except that all nullable fields are set to
     * {@code null} and following symbolic link is disabled.
     *
     * @param source All files and directories inside the path is listed, including the source file itself.
     * @return List of all files and directories inside {@code source} (inclusive).
     */
    @NonNull
    public static List<Path> getAll(@NonNull Path source) {
        return getAll(null, source, null, null, false);
    }

    /**
     * Get a list of files and directories inside {@code source} including the source file itself. This method is fully
     * capable of handling path filters as regular expressions and can follow symbolic links. It uses a non-recursive
     * algorithm and should be much faster than a recursive implementation.
     * <p>
     * <b>Note:</b> Currently, it can only retrieve regular files as well as directories. Any other file formats (e.g.
     * FIFO) are not currently supported.
     *
     * @param base        Base path is the path in respect to which {@code filters} and {@code exclusions} are applied.
     *                    If it is {@code null}, no base path is considered.
     * @param source      All files and directories inside the path is listed, including the source file itself.
     * @param filters     Filters to be applied. No filters are applied if it's set to {@code null}. The filters are
     *                    expected to be regular expressions and are mutually exclusive.
     * @param exclusions  Same as {@code filters}, except that it ignores the given patterns.
     * @param followLinks Whether to follow symbolic links. If disabled, a linked directory will be added as a regular
     *                    file.
     * @return List of files and directories inside {@code source} (inclusive).
     */
    @NonNull
    public static List<Path> getAll(@Nullable Path base, @NonNull Path source, @Nullable String[] filters,
                                    @Nullable String[] exclusions, boolean followLinks) {
        Objects.requireNonNull(source);
        // Convert filters into patterns to reduce overheads
        Pattern[] filterPatterns;
        if (filters != null) {
            filterPatterns = new Pattern[filters.length];
            for (int i = 0; i < filters.length; ++i) {
                filterPatterns[i] = Pattern.compile(filters[i]);
            }
        } else filterPatterns = null;
        Pattern[] exclusionPatterns;
        if (exclusions != null) {
            exclusionPatterns = new Pattern[exclusions.length];
            for (int i = 0; i < exclusions.length; ++i) {
                exclusionPatterns[i] = Pattern.compile(exclusions[i]);
            }
        } else exclusionPatterns = null;
        // Start collecting files
        LinkedList<Path> allFiles = new LinkedList<>();
        if (source.isFile()) { // OsConstants#S_ISREG
            // Add it and return
            allFiles.add(source);
            return allFiles;
        } else if (source.isDirectory()) { // OsConstants#S_ISDIR
            if (!followLinks && source.isSymbolicLink()) {
                // Add the directory only if it's a symbolic link and followLinks is disabled
                allFiles.add(source);
                return allFiles;
            }
        } else {
            // No support for any other files
            return allFiles;
        }
        // Top-level directory
        Path[] fileList = source.listFiles(pathname -> pathname.isDirectory()
                || (isUnderFilter(pathname, base, filterPatterns) && !willExclude(pathname, base, exclusionPatterns)));
        if (fileList.length == 0) {
            // Add this directory nonetheless if it matches one of the filters, no symlink checks needed
            if (isUnderFilter(source, base, filterPatterns) && !willExclude(source, base, exclusionPatterns)) {
                allFiles.add(source);
            }
            return allFiles;
        } else {
            // Has children, don't check for filters, just add the directory
            allFiles.add(source);
        }
        // Declare a collection of stored directories
        LinkedList<Path> dirCheckList = new LinkedList<>();
        for (Path curFile : fileList) {
            if (curFile.isFile()) { // OsConstants#S_ISREG
                allFiles.add(curFile);
            } else if (curFile.isDirectory()) { // OsConstants#S_ISDIR
                if (!followLinks && curFile.isSymbolicLink()) {
                    // Add the directory only if it's a symbolic link and followLinks is disabled
                    allFiles.add(curFile);
                } else {
                    // Not a symlink
                    dirCheckList.add(curFile);
                }
            } // else No support for any other files
        }
        while (!dirCheckList.isEmpty()) {
            Path removedDir = dirCheckList.removeFirst();
            // Remove the first catalog
            Path[] removedDirFileList = removedDir.listFiles(pathname -> pathname.isDirectory()
                    || (isUnderFilter(pathname, base, filterPatterns) && !willExclude(pathname, base, exclusionPatterns)));
            if (removedDirFileList.length == 0) {
                // Add this directory nonetheless if it matches one of the filters, no symlink checks needed
                if (isUnderFilter(removedDir, base, filterPatterns) && !willExclude(removedDir, base, exclusionPatterns)) {
                    allFiles.add(removedDir);
                }
                continue;
            } else {
                // Has children
                allFiles.add(removedDir);
            }
            for (Path curFile : removedDirFileList) {
                if (curFile.isFile()) { // OsConstants#S_ISREG
                    allFiles.add(curFile);
                } else if (curFile.isDirectory()) { // OsConstants#S_ISDIR
                    if (!followLinks && curFile.isSymbolicLink()) {
                        // Add the directory only if it's a symbolic link and followLinks is disabled
                        allFiles.add(curFile);
                    } else {
                        // Not a symlink
                        dirCheckList.add(curFile);
                    }
                } // else No support for any other files
            }
        }
        return allFiles;
    }

    public static boolean isUnderFilter(@NonNull Path file, @Nullable Path basePath, @Nullable Pattern[] filters) {
        if (filters == null) return true;
        String fileStr = basePath == null ? file.getUri().getPath() : getRelativePath(file, basePath);
        for (Pattern filter : filters) {
            if (filter.matcher(fileStr).matches()) return true;
        }
        return false;
    }

    public static boolean willExclude(@NonNull Path file, @Nullable Path basePath, @Nullable Pattern[] exclude) {
        if (exclude == null) return false;
        String fileStr = basePath == null ? file.getUri().getPath() : getRelativePath(file, basePath);
        for (Pattern excludeRegex : exclude) {
            if (excludeRegex.matcher(fileStr).matches()) return true;
        }
        return false;
    }

    @NonNull
    public static String getRelativePath(@NonNull Path file, @NonNull Path basePath) {
        return getRelativePath(file, basePath, File.separator);
    }

    @NonNull
    public static String getRelativePath(@NonNull Path file, @NonNull Path basePath, @NonNull String separator) {
        String baseDir = basePath.getUri().getPath() + (basePath.isDirectory() ? separator : "");
        String targetPath = file.getUri().getPath() + (file.isDirectory() ? separator : "");
        return getRelativePath(targetPath, baseDir, separator);
    }

    @NonNull
    public static String getRelativePath(@NonNull String targetPath, @NonNull String baseDir, @NonNull String separator) {
        String[] base = baseDir.split(Pattern.quote(separator));
        String[] target = targetPath.split(Pattern.quote(separator));

        // Count common elements and their length
        int commonCount = 0, commonLength = 0, maxCount = Math.min(target.length, base.length);
        while (commonCount < maxCount) {
            String targetElement = target[commonCount];
            if (!targetElement.equals(base[commonCount])) break;
            commonCount++;
            commonLength += targetElement.length() + 1; // Directory name length plus slash
        }
        if (commonCount == 0) return targetPath; // No common path element

        int targetLength = targetPath.length();
        int dirsUp = base.length - commonCount;
        StringBuilder relative = new StringBuilder(dirsUp * 3 + targetLength - commonLength + 1);
        for (int i = 0; i < dirsUp; i++) {
            relative.append("..").append(separator);
        }
        if (commonLength < targetLength) relative.append(targetPath.substring(commonLength));
        return relative.toString();
    }
}
