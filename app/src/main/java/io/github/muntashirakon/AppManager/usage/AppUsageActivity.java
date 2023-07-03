// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.usage;

import static io.github.muntashirakon.AppManager.usage.UsageUtils.USAGE_LAST_BOOT;
import static io.github.muntashirakon.AppManager.usage.UsageUtils.USAGE_TODAY;
import static io.github.muntashirakon.AppManager.usage.UsageUtils.USAGE_WEEKLY;
import static io.github.muntashirakon.AppManager.usage.UsageUtils.USAGE_YESTERDAY;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textview.MaterialTextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import io.github.muntashirakon.AppManager.BaseActivity;
import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.logs.Log;
import io.github.muntashirakon.AppManager.self.SelfPermissions;
import io.github.muntashirakon.AppManager.self.imagecache.ImageLoader;
import io.github.muntashirakon.AppManager.settings.FeatureController;
import io.github.muntashirakon.AppManager.usage.UsageUtils.IntervalType;
import io.github.muntashirakon.AppManager.users.Users;
import io.github.muntashirakon.AppManager.utils.BetterActivityResult;
import io.github.muntashirakon.AppManager.utils.DateUtils;
import io.github.muntashirakon.AppManager.utils.MultithreadedExecutor;
import io.github.muntashirakon.AppManager.utils.UIUtils;
import io.github.muntashirakon.widget.MaterialSpinner;
import io.github.muntashirakon.widget.RecyclerView;
import io.github.muntashirakon.widget.SwipeRefreshLayout;

public class AppUsageActivity extends BaseActivity implements SwipeRefreshLayout.OnRefreshListener {
    @IntDef(value = {
            SORT_BY_APP_LABEL,
            SORT_BY_LAST_USED,
            SORT_BY_MOBILE_DATA,
            SORT_BY_PACKAGE_NAME,
            SORT_BY_SCREEN_TIME,
            SORT_BY_TIMES_OPENED,
            SORT_BY_WIFI_DATA
    })
    private @interface SortOrder {
    }

    private static final int SORT_BY_APP_LABEL = 0;
    private static final int SORT_BY_LAST_USED = 1;
    private static final int SORT_BY_MOBILE_DATA = 2;
    private static final int SORT_BY_PACKAGE_NAME = 3;
    private static final int SORT_BY_SCREEN_TIME = 4;
    private static final int SORT_BY_TIMES_OPENED = 5;
    private static final int SORT_BY_WIFI_DATA = 6;

    private static final int[] sSortMenuItemIdsMap = {
            R.id.action_sort_by_app_label, R.id.action_sort_by_last_used,
            R.id.action_sort_by_mobile_data, R.id.action_sort_by_package_name,
            R.id.action_sort_by_screen_time, R.id.action_sort_by_times_opened,
            R.id.action_sort_by_wifi_data};

    private AppUsageViewModel mViewModel;
    private LinearProgressIndicator mProgressIndicator;
    private SwipeRefreshLayout mSwipeRefresh;
    private AppUsageAdapter mAppUsageAdapter;
    private final BetterActivityResult<String, Boolean> mRequestPerm = BetterActivityResult
            .registerForActivityResult(this, new ActivityResultContracts.RequestPermission());

    @SuppressLint("WrongConstant")
    @Override
    protected void onAuthenticated(Bundle savedInstanceState) {
        if (!FeatureController.isUsageAccessEnabled()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_app_usage);
        setSupportActionBar(findViewById(R.id.toolbar));
        mViewModel = new ViewModelProvider(this).get(AppUsageViewModel.class);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(getString(R.string.app_usage));
        }

        mProgressIndicator = findViewById(R.id.progress_linear);
        mProgressIndicator.setVisibilityAfterHide(View.GONE);

        // Get usage stats
        mAppUsageAdapter = new AppUsageAdapter(this);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setEmptyView(findViewById(android.R.id.empty));
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(mAppUsageAdapter);

        mSwipeRefresh = findViewById(R.id.swipe_refresh);
        mSwipeRefresh.setOnRefreshListener(this);
        mSwipeRefresh.setOnChildScrollUpCallback((parent, child) -> recyclerView.canScrollVertically(-1));

        MaterialSpinner intervalSpinner = findViewById(R.id.spinner_interval);
        // Make spinner the first item to focus on
        intervalSpinner.requestFocus();
        ArrayAdapter<CharSequence> intervalSpinnerAdapter = ArrayAdapter.createFromResource(this,
                R.array.usage_interval_dropdown_list, io.github.muntashirakon.ui.R.layout.auto_complete_dropdown_item_small);
        intervalSpinner.setAdapter(intervalSpinnerAdapter);
        intervalSpinner.setOnItemClickListener((parent, view, position, id) -> {
            mViewModel.setCurrentInterval(position);
            getAppUsage();
        });
        mViewModel.getPackageUsageInfoList().observe(this, packageUsageInfoList -> {
            mAppUsageAdapter.setDefaultList(packageUsageInfoList);
            setUsageSummary();
            mProgressIndicator.hide();
        });
        mViewModel.getPackageUsageInfo().observe(this, packageUsageInfo -> {
            AppUsageDetailsDialog fragment = AppUsageDetailsDialog.getInstance(packageUsageInfo);
            fragment.show(getSupportFragmentManager(), AppUsageDetailsDialog.TAG);
        });
        checkPermissions();
    }

    @Override
    public void onRefresh() {
        mSwipeRefresh.setRefreshing(false);
        checkPermissions();
    }

    private void checkPermissions() {
        // Check permission
        if (!SelfPermissions.checkUsageStatsPermission()) {
            promptForUsageStatsPermission();
        } else getAppUsage();
        if (AppUsageStatsManager.requireReadPhoneStatePermission()) {
            // Grant READ_PHONE_STATE permission
            mRequestPerm.launch(Manifest.permission.READ_PHONE_STATE, granted -> {
                if (granted) recreate();
            });
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_app_usage_actions, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mAppUsageAdapter != null) {
            menu.findItem(sSortMenuItemIdsMap[mAppUsageAdapter.mSortBy]).setChecked(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
        } else if (id == R.id.action_sort_by_app_label) {
            setSortBy(SORT_BY_APP_LABEL);
            item.setChecked(true);
        } else if (id == R.id.action_sort_by_last_used) {
            setSortBy(SORT_BY_LAST_USED);
            item.setChecked(true);
        } else if (id == R.id.action_sort_by_mobile_data) {
            setSortBy(SORT_BY_MOBILE_DATA);
            item.setChecked(true);
        } else if (id == R.id.action_sort_by_package_name) {
            setSortBy(SORT_BY_PACKAGE_NAME);
            item.setChecked(true);
        } else if (id == R.id.action_sort_by_screen_time) {
            setSortBy(SORT_BY_SCREEN_TIME);
            item.setChecked(true);
        } else if (id == R.id.action_sort_by_times_opened) {
            setSortBy(SORT_BY_TIMES_OPENED);
            item.setChecked(true);
        } else if (id == R.id.action_sort_by_wifi_data) {
            setSortBy(SORT_BY_WIFI_DATA);
            item.setChecked(true);
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void setSortBy(@SortOrder int sort) {
        if (mAppUsageAdapter != null) {
            mAppUsageAdapter.setSortBy(sort);
            mAppUsageAdapter.sortPackageUsageInfoList();
            mAppUsageAdapter.notifyDataSetChanged();
        }
    }

    private void getAppUsage() {
        mProgressIndicator.show();
        mViewModel.loadPackageUsageInfoList();
    }

    private void promptForUsageStatsPermission() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.grant_usage_access)
                .setMessage(R.string.grant_usage_acess_message)
                .setPositiveButton(R.string.go, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        // Usage access isn't available
                        new MaterialAlertDialogBuilder(this)
                                .setCancelable(false)
                                .setTitle(R.string.grant_usage_access)
                                .setMessage(R.string.usage_access_not_supported)
                                .setPositiveButton(R.string.go_back, (dialog1, which1) -> {
                                    FeatureController.getInstance().modifyState(FeatureController
                                            .FEAT_USAGE_ACCESS, false);
                                    finish();
                                })
                                .show();
                    }
                })
                .setNegativeButton(getString(R.string.go_back), (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void setUsageSummary() {
        TextView timeUsed = findViewById(R.id.time_used);
        TextView timeRange = findViewById(R.id.time_range);
        timeUsed.setText(DateUtils.getFormattedDuration(this, mViewModel.getTotalScreenTime()));
        switch (mViewModel.getCurrentInterval()) {
            case USAGE_TODAY:
                timeRange.setText(R.string.usage_today);
                break;
            case USAGE_YESTERDAY:
                timeRange.setText(R.string.usage_yesterday);
                break;
            case USAGE_WEEKLY:
                timeRange.setText(R.string.usage_7_days);
                break;
            case USAGE_LAST_BOOT:
                break;
        }
    }

    private int getUsagePercent(long screenTime) {
        return (int) (screenTime * 100. / mViewModel.getTotalScreenTime());
    }

    public static class AppUsageViewModel extends AndroidViewModel {
        private final MutableLiveData<List<PackageUsageInfo>> mPackageUsageInfoListLiveData = new MutableLiveData<>();
        private final MutableLiveData<PackageUsageInfo> mPackageUsageInfoLiveData = new MutableLiveData<>();
        private final MultithreadedExecutor mExecutor = MultithreadedExecutor.getNewInstance();

        private long mTotalScreenTime;
        private boolean mHasMultipleUsers;
        @IntervalType
        private int mCurrentInterval = USAGE_TODAY;

        public AppUsageViewModel(@NonNull Application application) {
            super(application);
        }

        @Override
        protected void onCleared() {
            mExecutor.shutdownNow();
            super.onCleared();
        }

        public LiveData<List<PackageUsageInfo>> getPackageUsageInfoList() {
            return mPackageUsageInfoListLiveData;
        }

        public LiveData<PackageUsageInfo> getPackageUsageInfo() {
            return mPackageUsageInfoLiveData;
        }

        public void setCurrentInterval(@IntervalType int currentInterval) {
            mCurrentInterval = currentInterval;
        }

        @IntervalType
        public int getCurrentInterval() {
            return mCurrentInterval;
        }

        public long getTotalScreenTime() {
            return mTotalScreenTime;
        }

        public boolean hasMultipleUsers() {
            return mHasMultipleUsers;
        }

        public void loadPackageUsageInfo(PackageUsageInfo usageInfo) {
            mExecutor.submit(() -> {
                try {
                    PackageUsageInfo packageUsageInfo = AppUsageStatsManager.getInstance().getUsageStatsForPackage(
                            usageInfo.packageName, mCurrentInterval, usageInfo.userId);
                    packageUsageInfo.copyOthers(usageInfo);
                    mPackageUsageInfoLiveData.postValue(packageUsageInfo);
                } catch (Exception e) {
                    Log.e("AppUsage", e);
                }
            });
        }

        @AnyThread
        public void loadPackageUsageInfoList() {
            mExecutor.submit(() -> {
                int[] userIds = Users.getUsersIds();
                List<PackageUsageInfo> packageUsageInfoList = new ArrayList<>();
                for (int userId : userIds) {
                    try {
                        packageUsageInfoList.addAll(AppUsageStatsManager.getInstance()
                                .getUsageStats(mCurrentInterval, userId));
                    } catch (Exception e) {
                        Log.e("AppUsage", e);
                    }
                }
                mTotalScreenTime = 0;
                Set<Integer> users = new HashSet<>(3);
                for (PackageUsageInfo appItem : packageUsageInfoList) {
                    mTotalScreenTime += appItem.screenTime;
                    users.add(appItem.userId);
                }
                mHasMultipleUsers = users.size() > 1;
                mPackageUsageInfoListLiveData.postValue(packageUsageInfoList);
            });
        }
    }

    static class AppUsageAdapter extends RecyclerView.Adapter<AppUsageAdapter.ViewHolder> {
        @GuardedBy("mAdapterList")
        private final List<PackageUsageInfo> mAdapterList = new ArrayList<>();
        private final AppUsageActivity mActivity;
        @SortOrder
        private int mSortBy;

        private static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView appIcon;
            View iconFrame;
            MaterialTextView appLabel;
            MaterialTextView badge;
            MaterialTextView packageName;
            MaterialTextView lastUsageDate;
            MaterialTextView mobileDataUsage;
            MaterialTextView wifiDataUsage;
            MaterialTextView screenTime;
            MaterialTextView percentUsage;
            LinearProgressIndicator usageIndicator;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                appIcon = itemView.findViewById(R.id.icon);
                iconFrame = itemView.findViewById(R.id.icon_frame);
                appIcon.setClipToOutline(true);
                badge = itemView.findViewById(R.id.badge);
                appLabel = itemView.findViewById(R.id.label);
                packageName = itemView.findViewById(R.id.package_name);
                lastUsageDate = itemView.findViewById(R.id.date);
                mobileDataUsage = itemView.findViewById(R.id.data_usage);
                wifiDataUsage = itemView.findViewById(R.id.wifi_usage);
                screenTime = itemView.findViewById(R.id.screen_time);
                percentUsage = itemView.findViewById(R.id.percent_usage);
                usageIndicator = itemView.findViewById(R.id.progress_linear);
            }
        }

        AppUsageAdapter(@NonNull AppUsageActivity activity) {
            mActivity = activity;
            mSortBy = SORT_BY_SCREEN_TIME;
        }

        void setDefaultList(List<PackageUsageInfo> list) {
            synchronized (mAdapterList) {
                mAdapterList.clear();
                mAdapterList.addAll(list);
            }
            sortPackageUsageInfoList();
            notifyDataSetChanged();
        }

        public void setSortBy(int sortBy) {
            mSortBy = sortBy;
        }

        private void sortPackageUsageInfoList() {
            synchronized (mAdapterList) {
                Collections.sort(mAdapterList, ((o1, o2) -> {
                    switch (mSortBy) {
                        case SORT_BY_APP_LABEL:
                            return Collator.getInstance().compare(o1.appLabel, o2.appLabel);
                        case SORT_BY_LAST_USED:
                            return -Long.compare(o1.lastUsageTime, o2.lastUsageTime);
                        case SORT_BY_MOBILE_DATA:
                            if (o1.mobileData == null) return o2.mobileData == null ? 0 : -1;
                            return -o1.mobileData.compareTo(o2.mobileData);
                        case SORT_BY_PACKAGE_NAME:
                            return o1.packageName.compareToIgnoreCase(o2.packageName);
                        case SORT_BY_SCREEN_TIME:
                            return -Long.compare(o1.screenTime, o2.screenTime);
                        case SORT_BY_TIMES_OPENED:
                            return -Integer.compare(o1.timesOpened, o2.timesOpened);
                        case SORT_BY_WIFI_DATA:
                            if (o1.wifiData == null) return o2.wifiData == null ? 0 : -1;
                            return -o1.wifiData.compareTo(o2.wifiData);
                    }
                    return 0;
                }));
            }
        }

        @Override
        public int getItemCount() {
            synchronized (mAdapterList) {
                return mAdapterList.size();
            }
        }

        @Override
        public long getItemId(int position) {
            synchronized (mAdapterList) {
                return Objects.hashCode(mAdapterList.get(position));
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_usage, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final PackageUsageInfo usageInfo;
            synchronized (mAdapterList) {
                usageInfo = mAdapterList.get(position);
            }
            final int percentUsage = mActivity.getUsagePercent(usageInfo.screenTime);
            // Set label (or package name on failure)
            holder.appLabel.setText(usageInfo.appLabel);
            // Set icon
            ImageLoader.getInstance().displayImage(usageInfo.packageName, usageInfo.applicationInfo, holder.appIcon);
            // Set user ID
            if (mActivity.mViewModel.hasMultipleUsers()) {
                holder.iconFrame.setBackgroundResource(R.drawable.circle_with_padding);
                holder.badge.setVisibility(View.VISIBLE);
                holder.badge.setText(String.format(Locale.getDefault(), "%d", usageInfo.userId));
            } else {
                holder.iconFrame.setBackgroundResource(0);
                holder.badge.setVisibility(View.GONE);
            }
            // Set package name
            holder.packageName.setText(usageInfo.packageName);
            // Set usage
            long lastTimeUsed = usageInfo.lastUsageTime > 1 ? (System.currentTimeMillis() - usageInfo.lastUsageTime) : 0;
            if (mActivity.mViewModel.getCurrentInterval() != USAGE_YESTERDAY
                    && usageInfo.packageName.equals(BuildConfig.APPLICATION_ID)) {
                // Special case for App Manager since the user is using the app right now
                holder.lastUsageDate.setText(R.string.running);
            } else if (lastTimeUsed > 1) {
                holder.lastUsageDate.setText(String.format(Locale.getDefault(), "%s %s",
                        DateUtils.getFormattedDuration(mActivity, lastTimeUsed), mActivity.getString(R.string.ago)));
            } else {
                holder.lastUsageDate.setText(R.string._undefined);
            }
            String screenTimesWithTimesOpened;
            // Set times opened
            screenTimesWithTimesOpened = mActivity.getResources().getQuantityString(R.plurals.no_of_times_opened, usageInfo.timesOpened, usageInfo.timesOpened);
            // Set screen time
            screenTimesWithTimesOpened += ", " + DateUtils.getFormattedDuration(mActivity, usageInfo.screenTime);
            holder.screenTime.setText(screenTimesWithTimesOpened);
            // Set data usage
            AppUsageStatsManager.DataUsage mobileData = usageInfo.mobileData;
            if (mobileData != null && (mobileData.first != 0 || mobileData.second != 0)) {
                Drawable phoneIcon = ContextCompat.getDrawable(mActivity, R.drawable.ic_phone_android);
                String dataUsage = String.format("  ↑ %1$s ↓ %2$s",
                        Formatter.formatFileSize(mActivity, mobileData.first),
                        Formatter.formatFileSize(mActivity, mobileData.second));
                holder.mobileDataUsage.setText(UIUtils.setImageSpan(dataUsage, phoneIcon, holder.mobileDataUsage));
            } else holder.mobileDataUsage.setText("");
            AppUsageStatsManager.DataUsage wifiData = usageInfo.wifiData;
            if (wifiData != null && (wifiData.first != 0 || wifiData.second != 0)) {
                Drawable wifiIcon = ContextCompat.getDrawable(mActivity, R.drawable.ic_wifi);
                String dataUsage = String.format("  ↑ %1$s ↓ %2$s",
                        Formatter.formatFileSize(mActivity, wifiData.first),
                        Formatter.formatFileSize(mActivity, wifiData.second));
                holder.wifiDataUsage.setText(UIUtils.setImageSpan(dataUsage, wifiIcon, holder.wifiDataUsage));
            } else holder.wifiDataUsage.setText("");
            // Set usage percentage
            holder.percentUsage.setText(String.format(Locale.getDefault(), "%d%%", percentUsage));
            holder.usageIndicator.show();
            holder.usageIndicator.setProgress(percentUsage);
            // On Click Listener
            holder.itemView.setOnClickListener(v -> mActivity.mViewModel.loadPackageUsageInfo(usageInfo));
        }
    }
}
