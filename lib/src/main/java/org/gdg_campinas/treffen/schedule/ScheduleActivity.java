/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gdg_campinas.treffen.schedule;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import org.gdg_campinas.treffen.archframework.PresenterImpl;
import org.gdg_campinas.treffen.injection.ModelProvider;
import org.gdg_campinas.treffen.model.TagMetadata;
import org.gdg_campinas.treffen.session.SessionDetailActivity;
import org.gdg_campinas.treffen.util.LogUtils;
import org.gdg_campinas.treffen.util.SessionsHelper;
import org.gdg_campinas.treffen.util.TimeUtils;
import org.gdg_campinas.treffen.model.ScheduleHelper;
import org.gdg_campinas.treffen.navigation.NavigationModel;
import org.gdg_campinas.treffen.schedule.ScheduleModel.MyScheduleQueryEnum;
import org.gdg_campinas.treffen.schedule.ScheduleModel.MyScheduleUserActionEnum;
import org.gdg_campinas.treffen.ui.BaseActivity;

import java.util.Date;

import static org.gdg_campinas.treffen.util.LogUtils.LOGD;
import static org.gdg_campinas.treffen.util.LogUtils.LOGE;
import static org.gdg_campinas.treffen.util.LogUtils.makeLogTag;

/**
 * This shows the schedule of the logged in user, organised per day.
 * <p/>
 * Depending on the device, this Activity uses a {@link ViewPager} with a {@link
 * ScheduleSingleDayFragment} for its page, which uses a {@link RecyclerView} for each day.
 * Each day data is backed by a {@link ScheduleDayAdapter}.
 * <p/>
 * If the user attends the conference, all time slots that have sessions are shown, with a button to
 * allow the user to see all sessions in that slot.
 */
public class ScheduleActivity extends BaseActivity implements ScheduleViewParent {
    /**
     * This is used in the narrow mode, to pass in the day index to the {@link
     * ScheduleSingleDayFragment}.
     */
    public static final String ARG_CONFERENCE_DAY_INDEX =
            "org.gdg_campinas.treffen.ARG_CONFERENCE_DAY_INDEX";

    /**
     * Int extra used to indicate a specific conference day should shown initially when the screen
     * is launched. Conference days are zero-indexed.
     */
    public static final String EXTRA_CONFERENCE_DAY =
            "org.gdg_campinas.treffen.EXTRA_CONFERENCE_DAY_INDEX";

    /**
     * String extra used to specify a tag to filter sessions on the schedule when the screen
     * launches.
     */
    public static final String EXTRA_FILTER_TAG = ScheduleFilterFragment.FILTER_TAG;

    // The saved instance state filters
    private static final String STATE_FILTER_TAGS =
            "org.gdg_campinas.treffen.myschedule.STATE_FILTER_TAGS";
    private static final String STATE_CURRENT_URI =
            "org.gdg_campinas.treffen.myschedule.STATE_CURRENT_URI";

    /**
     * Interval that a timer will redraw the UI during the conference, so that time sensitive
     * widgets, like the "Now" and "Ended" indicators can be properly updated.
     */
    private static final long INTERVAL_TO_REDRAW_UI = TimeUtils.MINUTE;

    private static final String SCREEN_LABEL = "Schedule";

    private static final String TAG = LogUtils.makeLogTag(ScheduleActivity.class);
    private final Handler mUpdateUiHandler = new Handler();
    private DrawerLayout mDrawerLayout;
    private ScheduleFilterFragment mScheduleFilterFragment;
    private SchedulePagerFragment mSchedulePagerFragment;
    private ScheduleModel mModel; // TODO decouple this
    private PresenterImpl<ScheduleModel, MyScheduleQueryEnum, MyScheduleUserActionEnum>
            mPresenter;
    private final Runnable mUpdateUIRunnable = new Runnable() {
        @Override
        public void run() {
            ScheduleActivity activity = ScheduleActivity.this;
            if (activity.isDestroyed()) {
                LogUtils.LOGD(TAG, "Activity is not valid anymore. Stopping UI Updater");
                return;
            }

            LogUtils.LOGD(TAG, "Running MySchedule UI updater (now=" +
                    new Date(TimeUtils.getCurrentTime(activity)) + ")");

            mPresenter.onUserAction(ScheduleModel.MyScheduleUserActionEnum.REDRAW_UI, null);

            if (TimeUtils.isConferenceInProgress(activity)) {
                scheduleNextUiUpdate();
            }
        }
    };

    public static void launchScheduleWithFilterTag(Context context, TagMetadata.Tag tag) {
        launchScheduleWithFilterTag(context, tag.getId());
    }

    public static void launchScheduleWithFilterTag(Context context, String tag) {
        Intent intent = new Intent(context, ScheduleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (tag != null) {
            intent.putExtra(EXTRA_FILTER_TAG, tag);
        }
        context.startActivity(intent);
    }

    public static void launchScheduleForConferenceDay(Context context, int day) {
        Intent intent = new Intent(context, ScheduleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_CONFERENCE_DAY, day);
        context.startActivity(intent);
    }

    @Override
    protected NavigationModel.NavigationItemEnum getSelfNavDrawerItem() {
        return NavigationModel.NavigationItemEnum.MY_SCHEDULE;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        launchSessionDetailIfRequiredByIntent(getIntent()); // might call finish()
        if (isFinishing()) {
            return;
        }

        setContentView(org.gdg_campinas.treffen.lib.R.layout.schedule_act);
        setFullscreenLayout();

        mScheduleFilterFragment = (ScheduleFilterFragment) getSupportFragmentManager()
                .findFragmentById(org.gdg_campinas.treffen.lib.R.id.filter_drawer);
        mScheduleFilterFragment.setListener(new ScheduleFilterFragment.ScheduleFiltersFragmentListener() {
            @Override
            public void onFiltersChanged(TagFilterHolder filterHolder) {
                reloadSchedule(filterHolder);
            }
        });
        mSchedulePagerFragment = (SchedulePagerFragment) getSupportFragmentManager()
                .findFragmentById(org.gdg_campinas.treffen.lib.R.id.my_content);

        mDrawerLayout = (DrawerLayout) findViewById(org.gdg_campinas.treffen.lib.R.id.drawer_layout);

        initPresenter();
        if (savedInstanceState == null) {
            // first time through, check for extras
            processIntent(getIntent());
        }
        overridePendingTransition(org.gdg_campinas.treffen.lib.R.anim.fade_in, org.gdg_campinas.treffen.lib.R.anim.fade_out);
    }

    private void processIntent(Intent intent) {
        if (intent.hasExtra(EXTRA_CONFERENCE_DAY)) {
            int day = intent.getIntExtra(EXTRA_CONFERENCE_DAY, -1);
            // clear filters and show the selected day
            mScheduleFilterFragment.clearFilters();
            mSchedulePagerFragment.scrollToConferenceDay(day);
        }
        if (intent.hasExtra(EXTRA_FILTER_TAG)) {
            // apply the requested filter
            mScheduleFilterFragment.initWithArguments(intentToFragmentArguments(intent));
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (TimeUtils.isConferenceInProgress(this)) {
            scheduleNextUiUpdate();
        }
        mModel.addDataObservers();
    }

    @Override
    protected void onPause() {
        mModel.cleanUp();
        super.onPause();
    }

    /**
     * Pre-process the {@code intent} received to open this activity to determine if it was a deep
     * link to a SessionDetail. Typically you wouldn't use this type of logic, but we need to
     * because of the path of session details page on the website is only /schedule and session ids
     * are part of the query parameters ("sid").
     */
    private void launchSessionDetailIfRequiredByIntent(Intent intent) {
        if (intent != null && !TextUtils.isEmpty(intent.getDataString())) {
            String intentDataString = intent.getDataString();
            try {
                Uri dataUri = Uri.parse(intentDataString);

                // Website sends sessionId in query parameter "sid". If present, show
                // SessionDetailActivity
                String sessionId = dataUri.getQueryParameter("sid");
                if (!TextUtils.isEmpty(sessionId)) {
                    LogUtils.LOGD(TAG, "SessionId received from website: " + sessionId);
                    SessionDetailActivity.startSessionDetailActivity(ScheduleActivity.this,
                            sessionId);
                    finish();
                } else {
                    LogUtils.LOGD(TAG, "No SessionId received from website");
                }
            } catch (Exception exception) {
                LogUtils.LOGE(TAG, "Data uri existing but wasn't parsable for a session detail deep link");
            }
        }
    }

    private void initPresenter() {
        mModel = ModelProvider.provideMyScheduleModel(
                new ScheduleHelper(this),
                new SessionsHelper(this),
                this);
        TagFilterHolder filters = mScheduleFilterFragment.getFilters();
        mModel.setFilters(filters);

        final SchedulePagerFragment contentFragment =
                (SchedulePagerFragment) getSupportFragmentManager()
                        .findFragmentById(org.gdg_campinas.treffen.lib.R.id.my_content);
        contentFragment.onFiltersChanged(filters);

        // Each fragment in the pager adapter is an updatable view that the presenter must know
        mPresenter = new PresenterImpl<>(
                mModel,
                contentFragment.getDayFragments(),
                ScheduleModel.MyScheduleUserActionEnum.values(),
                ScheduleModel.MyScheduleQueryEnum.values());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        LogUtils.LOGD(TAG, "onNewIntent, extras " + intent.getExtras());

        launchSessionDetailIfRequiredByIntent(intent); // might call finish()
        if (isFinishing()) {
            return;
        }

        setIntent(intent);
        if (!isFinishing()) {
            processIntent(intent);
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawer(GravityCompat.END);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean canSwipeRefreshChildScrollUp() {
        final Fragment contentFragment = getSupportFragmentManager()
                .findFragmentById(org.gdg_campinas.treffen.lib.R.id.my_content);

        if (contentFragment instanceof ScheduleView) {
            return ((ScheduleView) contentFragment).canSwipeRefreshChildScrollUp();
        }

        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(org.gdg_campinas.treffen.lib.R.menu.my_schedule, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == org.gdg_campinas.treffen.lib.R.id.menu_filter) {
            mDrawerLayout.openDrawer(GravityCompat.END);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void scheduleNextUiUpdate() {
        // Remove existing UI update runnable, if any
        mUpdateUiHandler.removeCallbacks(mUpdateUIRunnable);
        // Post runnable with delay
        mUpdateUiHandler.postDelayed(mUpdateUIRunnable, INTERVAL_TO_REDRAW_UI);
    }

    private void reloadSchedule(TagFilterHolder filterHolder) {
        mModel.setFilters(filterHolder);
        mPresenter.onUserAction(ScheduleModel.MyScheduleUserActionEnum.RELOAD_DATA, null);
        final Fragment contentFragment = getSupportFragmentManager()
                .findFragmentById(org.gdg_campinas.treffen.lib.R.id.my_content);

        if (contentFragment instanceof ScheduleView) {
            ((ScheduleView) contentFragment).onFiltersChanged(filterHolder);
        }
    }

    @Override
    public void onRequestClearFilters() {
        mScheduleFilterFragment.clearFilters();
    }

    @Override
    public void onRequestFilterByTag(TagMetadata.Tag tag) {
        mScheduleFilterFragment.addFilter(tag);
    }

    @Override
    public void openFilterDrawer() {
        mDrawerLayout.openDrawer(GravityCompat.END);
    }

    @Override
    protected String getAnalyticsScreenLabel() {
        return SCREEN_LABEL;
    }

    @Override
    protected int getNavigationTitleId() {
        return org.gdg_campinas.treffen.lib.R.string.title_schedule;
    }
}
