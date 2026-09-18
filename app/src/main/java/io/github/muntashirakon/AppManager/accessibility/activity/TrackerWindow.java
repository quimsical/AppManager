// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.accessibility.activity;

import android.annotation.SuppressLint;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.UserHandleHidden;
import android.text.Editable;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.accessibility.AccessibilityMultiplexer;
import io.github.muntashirakon.AppManager.compat.UsageStatsManagerCompat;
import io.github.muntashirakon.AppManager.details.AppDetailsActivity;
import io.github.muntashirakon.AppManager.utils.ThreadUtils;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.AppManager.utils.Utils;
import io.github.muntashirakon.AppManager.utils.appearance.AppearanceUtils;
import io.github.muntashirakon.widget.TextInputTextView;

public class TrackerWindow implements View.OnTouchListener {
    public final WindowManager mWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;
    private final View mView;
    private final ShapeableImageView mIconView;
    private final MaterialCardView mContentView;
    private final TextInputTextView mPackageNameView;
    private final TextInputTextView mActivityNameView;
    private final TextInputTextView mClassNameView;
    private final TextInputTextView mClassHierarchyView;
    private final MaterialButton mPlayPauseButton;
    private final ExecutorService mTrackerExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mActivityQueryExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong mUpdateGeneration = new AtomicLong();
    private final Map<String, String> mActivityNames = new HashMap<>();
    private final Point mWindowSize = new Point(0, 0);
    private final Point mWindowPosition = new Point(0, 0);
    private final Point mPressPosition = new Point(0, 0);
    private final int mMaxWidth;
    private boolean mPaused = false;
    private boolean mIconified = false;
    private boolean mViewAttached = false;
    @Nullable
    private Future<?> mClassHierarchyResult;
    @Nullable
    private Future<?> mActivityQueryResult;
    @Nullable
    private String mCurrentPackageName;
    private boolean mActivityQueryScheduled;
    private boolean mActivityQueryDirty;
    private boolean mActivityCacheInitialized;
    private long mLastActivityQueryEnd;

    private static final long ACTIVITY_QUERY_DEBOUNCE_MILLIS = 300;
    private static final long ACTIVITY_QUERY_INTERVAL_MILLIS = 1_000;
    private static final long ACTIVITY_INITIAL_LOOKBACK_MILLIS = 24 * 60 * 60 * 1_000L;
    private final Runnable mActivityQueryRunnable = this::runActivityQuery;

    @SuppressLint("ClickableViewAccessibility")
    public TrackerWindow(@NonNull Context context) {
        Context themedContext = AppearanceUtils.getThemedContext(context, true);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        Display display = mWindowManager.getDefaultDisplay();
        int displayWidth = display.getWidth();
        display.getRealSize(mWindowSize);
        mMaxWidth = (displayWidth / 2) + 300; // FIXME: 5/2/23 Find a better way to represent a display
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mWindowLayoutParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type, flags, PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.gravity = Gravity.CENTER;
        mWindowLayoutParams.width = mMaxWidth;
        mWindowLayoutParams.windowAnimations = android.R.style.Animation_Toast;

        mView = View.inflate(themedContext, R.layout.window_activity_tracker, null);
        mIconView = mView.findViewById(R.id.icon);
        mContentView = mView.findViewById(R.id.content);
        mPackageNameView = mView.findViewById(R.id.package_name);
        mActivityNameView = mView.findViewById(R.id.activity_name);
        mClassNameView = mView.findViewById(R.id.class_name);
        mClassHierarchyView = mView.findViewById(R.id.class_hierarchy);
        mPlayPauseButton = mView.findViewById(R.id.action_play_pause);
        mPackageNameView.setOnLongClickListener(v -> {
            Editable packageName = mPackageNameView.getText();
            if (TextUtils.isEmpty(packageName)) {
                return false;
            }
            copyText("Package name", packageName);
            return true;
        });
        mActivityNameView.setOnLongClickListener(v -> {
            Editable activityName = mActivityNameView.getText();
            if (TextUtils.isEmpty(activityName)) {
                return false;
            }
            copyText("Activity name", activityName);
            return true;
        });
        mClassNameView.setOnLongClickListener(v -> {
            Editable className = mClassNameView.getText();
            if (TextUtils.isEmpty(className)) {
                return false;
            }
            copyText("Class name", className);
            return true;
        });
        mClassHierarchyView.setOnLongClickListener(v -> {
            Editable hierarchy = mClassHierarchyView.getText();
            if (TextUtils.isEmpty(hierarchy)) {
                return false;
            }
            copyText("Class hierarchy", hierarchy);
            return true;
        });
        mView.findViewById(R.id.info).setOnClickListener(v -> {
            Editable packageName = mPackageNameView.getText();
            if (TextUtils.isEmpty(packageName)) {
                return;
            }
            Intent appInfoIntent = AppDetailsActivity.getIntent(context, packageName.toString(), UserHandleHidden.myUserId(), true);
            appInfoIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(appInfoIntent);
            } catch (Throwable th) {
                UIUtils.displayLongToast("Error: " + th.getMessage());
            }
        });
        mView.findViewById(R.id.mini).setOnClickListener(v -> iconify());
        mPlayPauseButton.setOnClickListener(v -> {
            mPaused = !mPaused;
            mPlayPauseButton.setIconResource(mPaused ? R.drawable.ic_play_arrow : R.drawable.ic_pause);
        });
        mView.findViewById(android.R.id.closeButton).setOnClickListener(v -> dismiss());
        mIconView.setVisibility(View.GONE);
        mIconView.setOnClickListener(v -> expand());
        mView.findViewById(R.id.drag).setOnTouchListener(this);
        mIconView.setOnTouchListener(this);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        Point point;
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            point = new Point((int) event.getRawX(), (int) event.getRawY());
            mPressPosition.set(point.x, point.y);
            mWindowPosition.set(mWindowLayoutParams.x, mWindowLayoutParams.y);
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            point = new Point((int) event.getRawX(), (int) event.getRawY());
            int delX = point.x - mPressPosition.x;
            int delY = point.y - mPressPosition.y;
            mWindowLayoutParams.x = mWindowPosition.x + delX;
            mWindowLayoutParams.y = mWindowPosition.y + delY;
            updateLayout();
            return true;
        }
        if (v == mIconView && action == MotionEvent.ACTION_UP) {
            point = new Point((int) event.getRawX(), (int) event.getRawY());
            int delX = Math.abs(point.x - mPressPosition.x);
            int delY = Math.abs(point.y - mPressPosition.y);
            if (delX < 1 && delY < 1) {
                v.performClick();
                return true;
            }
        }
        return false;
    }

    public void showOrUpdate(AccessibilityEvent event) {
        if (!mViewAttached) {
            mViewAttached = true;
            mWindowManager.addView(mView, mWindowLayoutParams);
        }
        if (mPaused) {
            return;
        }
        @Nullable
        CharSequence packageName = event.getPackageName();
        @Nullable
        CharSequence className = event.getClassName();
        if (packageName != null && BuildConfig.APPLICATION_ID.contentEquals(packageName)) {
            // On some devices, this window always gets the focus
            if (className != null && "android.widget.EditText".contentEquals(className)) {
                // For some reason, only this class is focused
                AccessibilityNodeInfo source = event.getSource();
                if (source == null) {
                    // No class hierarchy. This is the intended event
                    return;
                }
                source.recycle();
            }
        }
        if (mClassHierarchyResult != null) {
            mClassHierarchyResult.cancel(true);
            if (mTrackerExecutor instanceof ThreadPoolExecutor) {
                ((ThreadPoolExecutor) mTrackerExecutor).remove((Runnable) mClassHierarchyResult);
            }
        }
        long updateGeneration = mUpdateGeneration.incrementAndGet();
        mCurrentPackageName = packageName != null ? packageName.toString() : null;
        mPackageNameView.setText(packageName);
        updateActivityNameView();
        mClassNameView.setText(className);
        mClassHierarchyResult = mTrackerExecutor.submit(() -> {
            CharSequence classHierarchy = TextUtils.join("\n", getClassHierarchy(event));
            if (ThreadUtils.isInterrupted()) {
                return;
            }
            ThreadUtils.postOnMainThread(() -> {
                if (mUpdateGeneration.get() != updateGeneration) {
                    return;
                }
                mClassHierarchyView.setText(classHierarchy);
            });
        });
        requestActivityQuery();
    }

    public void dismiss() {
        AccessibilityMultiplexer.getInstance().enableLeadingActivityTracker(false);
        mViewAttached = false;
        if (mClassHierarchyResult != null) {
            mClassHierarchyResult.cancel(true);
            if (mTrackerExecutor instanceof ThreadPoolExecutor) {
                ((ThreadPoolExecutor) mTrackerExecutor).remove((Runnable) mClassHierarchyResult);
            }
        }
        mUpdateGeneration.incrementAndGet();
        mTrackerExecutor.shutdownNow();
        ThreadUtils.getUiThreadHandler().removeCallbacks(mActivityQueryRunnable);
        mActivityQueryExecutor.shutdownNow();
        try {
            mWindowManager.removeView(mView);
        } catch (Exception ignore) {
        }
    }

    private void iconify() {
        mPaused = true;
        mIconified = true;
        // Window position may need to be adjusted to display the icon
        // (0,0) is middle
        int height = -mWindowSize.y / 2;
        if (mWindowLayoutParams.y < height) {
            mWindowPosition.y = height;
            mWindowLayoutParams.y = height;
        }
        mIconView.setVisibility(View.VISIBLE);
        mContentView.setVisibility(View.GONE);
        updateLayout();
    }

    private void expand() {
        mContentView.setVisibility(View.VISIBLE);
        mIconView.setVisibility(View.GONE);
        mPaused = false;
        mIconified = false;
        // Window position may need to be adjusted to display the drag handle
        // (0,0) is middle
        int width = (-mWindowSize.x + mMaxWidth) / 2;
        if (mWindowLayoutParams.x < width) {
            mWindowPosition.x = width;
            mWindowLayoutParams.x = width;
        }
        updateLayout();
    }

    private void updateLayout() {
        mWindowLayoutParams.width = mIconified ? WindowManager.LayoutParams.WRAP_CONTENT : mMaxWidth;
        mWindowManager.updateViewLayout(mView, mWindowLayoutParams);
    }

    private void copyText(CharSequence label, CharSequence content) {
        Utils.copyToClipboard(mView.getContext(), label, content);
    }

    private void requestActivityQuery() {
        mActivityQueryDirty = true;
        if (mActivityQueryScheduled || (mActivityQueryResult != null && !mActivityQueryResult.isDone())) {
            return;
        }
        long delay = Math.max(0, mLastActivityQueryEnd + ACTIVITY_QUERY_INTERVAL_MILLIS
                - System.currentTimeMillis());
        mActivityQueryScheduled = true;
        ThreadUtils.getUiThreadHandler().postDelayed(mActivityQueryRunnable,
                Math.max(delay, ACTIVITY_QUERY_DEBOUNCE_MILLIS));
    }

    private void runActivityQuery() {
        mActivityQueryScheduled = false;
        if (!mActivityQueryDirty || !mViewAttached) {
            return;
        }
        mActivityQueryDirty = false;
        long endTime = System.currentTimeMillis();
        long beginTime = mActivityCacheInitialized ? mLastActivityQueryEnd
                : endTime - ACTIVITY_INITIAL_LOOKBACK_MILLIS;
        mActivityQueryResult = mActivityQueryExecutor.submit(() -> {
            Map<String, ActivityEvent> latestActivities = getLatestActivities(beginTime, endTime);
            ThreadUtils.postOnMainThread(() -> {
                mActivityQueryResult = null;
                if (!mViewAttached) {
                    return;
                }
                if (latestActivities != null) {
                    for (Map.Entry<String, ActivityEvent> entry : latestActivities.entrySet()) {
                        mActivityNames.put(entry.getKey(), entry.getValue().activityName);
                    }
                    mActivityCacheInitialized = true;
                    mLastActivityQueryEnd = endTime;
                } else {
                    // Preserve the cache and retry after a later event.
                    mActivityQueryDirty = true;
                }
                updateActivityNameView();
                if (mActivityQueryDirty) {
                    requestActivityQuery();
                }
            });
        });
    }

    @Nullable
    private static Map<String, ActivityEvent> getLatestActivities(long beginTime, long endTime) {
        UsageEvents queryEvents = UsageStatsManagerCompat.queryEvents(beginTime, endTime,
                UserHandleHidden.myUserId());
        if (queryEvents == null) {
            return null;
        }
        Map<String, ActivityEvent> latestActivities = new HashMap<>();
        UsageEvents.Event usageEvent = new UsageEvents.Event();
        while (queryEvents.hasNextEvent()) {
            queryEvents.getNextEvent(usageEvent);
            if (usageEvent.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED
                    || usageEvent.getPackageName() == null || usageEvent.getClassName() == null) {
                continue;
            }
            String packageName = usageEvent.getPackageName();
            ActivityEvent previous = latestActivities.get(packageName);
            if (previous == null || previous.timestamp < usageEvent.getTimeStamp()) {
                latestActivities.put(packageName,
                        new ActivityEvent(usageEvent.getClassName(), usageEvent.getTimeStamp()));
            }
            if (ThreadUtils.isInterrupted()) {
                return null;
            }
        }
        return latestActivities;
    }

    private void updateActivityNameView() {
        mActivityNameView.setText(mCurrentPackageName == null ? null : mActivityNames.get(mCurrentPackageName));
    }

    private static final class ActivityEvent {
        private final String activityName;
        private final long timestamp;

        private ActivityEvent(@NonNull String activityName, long timestamp) {
            this.activityName = activityName;
            this.timestamp = timestamp;
        }
    }

    @NonNull
    private static List<CharSequence> getClassHierarchy(@NonNull AccessibilityEvent event) {
        List<CharSequence> classHierarchies = new ArrayList<>();
        AccessibilityNodeInfo nodeInfo = event.getSource();
        if (nodeInfo != null) {
            classHierarchies.add(nodeInfo.getClassName());
            int depth = 0;
            while (depth < 20) { // Limit depth to avoid running forever
                AccessibilityNodeInfo tmpNodeInfo = nodeInfo.getParent();
                if (tmpNodeInfo != null) {
                    nodeInfo.recycle();
                    nodeInfo = tmpNodeInfo;
                    classHierarchies.add(nodeInfo.getClassName());
                } else {
                    // Max depth reached
                    break;
                }
                ++depth;
                if (ThreadUtils.isInterrupted()) {
                    return Collections.emptyList();
                }
            }
            try {
                if (depth == 20) {
                    classHierarchies.add("...");
                }
            } finally {
                nodeInfo.recycle();
            }
        }
        Collections.reverse(classHierarchies);
        if (ThreadUtils.isInterrupted()) {
            return Collections.emptyList();
        }
        int size = classHierarchies.size();
        if (size <= 1) {
            return classHierarchies;
        }
        classHierarchies.set(0, "┬ " + classHierarchies.get(0));
        for (int i = 1; i < size; ++i) {
            StringBuilder sb = new StringBuilder();
            for (int j = 1; j < i; ++j) {
                sb.append(' ');
            }
            if (i != (size - 1)) {
                sb.append("└┬ ");
            } else sb.append("└─ ");
            sb.append(classHierarchies.get(i));
            classHierarchies.set(i, sb.toString());
            if (ThreadUtils.isInterrupted()) {
                return Collections.emptyList();
            }
        }
        return classHierarchies;
    }
}
