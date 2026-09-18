// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.utils.AppPref;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = SecurityAndOpsViewModelTest.ShadowOps.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SecurityAndOpsViewModelTest {
    private SecurityAndOpsViewModel mViewModel;

    @Before
    public void setUp() {
        ShadowOps.reset();
        AppPref.set(AppPref.PrefKey.PREF_LAST_VERSION_CODE_LONG, (long) BuildConfig.VERSION_CODE);
        mViewModel = new SecurityAndOpsViewModel(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        mViewModel.onCleared();
    }

    @Test
    public void duplicateModeInitialisationIsIgnored() throws InterruptedException {
        ShadowOps.blockInit = true;

        mViewModel.setModeOfOps();
        assertTrue(ShadowOps.operationStarted.await(5, TimeUnit.SECONDS));
        mViewModel.setModeOfOps();
        ShadowOps.continueOperation.countDown();
        assertTrue(ShadowOps.operationFinished.await(5, TimeUnit.SECONDS));

        assertEquals(1, ShadowOps.initCalls.get());
        assertEquals(Integer.valueOf(Ops.STATUS_SUCCESS), awaitStatus());
    }

    @Test
    public void unexpectedFailureFallsBackAndPublishesFailure() throws InterruptedException {
        ShadowOps.throwFromInit = true;

        mViewModel.setModeOfOps();
        assertTrue(ShadowOps.fallbackCalled.await(5, TimeUnit.SECONDS));

        assertEquals(1, ShadowOps.fallbackCalls.get());
        assertEquals(Integer.valueOf(Ops.STATUS_FAILURE), awaitStatus());
    }

    @Test
    public void clearingViewModelInterruptsPairingWait() throws InterruptedException {
        mViewModel.pairAdb();
        assertTrue(ShadowOps.operationStarted.await(5, TimeUnit.SECONDS));

        mViewModel.onCleared();

        assertTrue(ShadowOps.operationInterrupted.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void operationStatusesAreNotReplayedAfterActivityRecreation() {
        List<Integer> statuses = Arrays.asList(
                Ops.STATUS_SUCCESS,
                Ops.STATUS_FAILURE,
                Ops.STATUS_AUTO_CONNECT_WIRELESS_DEBUGGING,
                Ops.STATUS_WIRELESS_DEBUGGING_CHOOSER_REQUIRED,
                Ops.STATUS_ADB_PAIRING_REQUIRED,
                Ops.STATUS_ADB_CONNECT_REQUIRED,
                Ops.STATUS_FAILURE_ADB_NEED_MORE_PERMS
        );

        AtomicInteger firstObserverCalls = new AtomicInteger();
        AtomicInteger recreatedObserverCalls = new AtomicInteger();
        androidx.lifecycle.Observer<Integer> firstObserver = status -> firstObserverCalls.incrementAndGet();
        androidx.lifecycle.Observer<Integer> recreatedObserver = status -> recreatedObserverCalls.incrementAndGet();

        TestLifecycleOwner firstOwner = new TestLifecycleOwner();
        firstOwner.start();
        mViewModel.authenticationStatus().observe(firstOwner, firstObserver);
        for (Integer status : statuses) {
            mViewModel.onStatusReceived(status);
            ShadowLooper.idleMainLooper();
        }
        firstOwner.destroy();

        // A recreated Activity must wait for a new transition. Replaying the last command can
        // reopen a dialog, retry ADB, or repeat terminal navigation.
        TestLifecycleOwner recreatedOwner = new TestLifecycleOwner();
        recreatedOwner.start();
        mViewModel.authenticationStatus().observe(recreatedOwner, recreatedObserver);

        assertEquals(statuses.size(), firstObserverCalls.get());
        assertEquals(0, recreatedObserverCalls.get());
        recreatedOwner.destroy();
    }

    @Test
    public void statusEmittedWhileActivityIsStoppedIsDeliveredOnceAfterRecreation() {
        AtomicInteger recreatedObserverCalls = new AtomicInteger();
        androidx.lifecycle.Observer<Integer> recreatedObserver = status -> recreatedObserverCalls.incrementAndGet();

        // No active Activity observer exists when the backend finishes.
        mViewModel.onStatusReceived(Ops.STATUS_SUCCESS);
        ShadowLooper.idleMainLooper();

        TestLifecycleOwner recreatedOwner = new TestLifecycleOwner();
        recreatedOwner.start();
        mViewModel.authenticationStatus().observe(recreatedOwner, recreatedObserver);
        assertEquals(1, recreatedObserverCalls.get());
        recreatedOwner.destroy();

        // Recreating again must not process the same terminal transition twice.
        androidx.lifecycle.Observer<Integer> secondRecreatedObserver = status -> recreatedObserverCalls.incrementAndGet();
        TestLifecycleOwner secondRecreatedOwner = new TestLifecycleOwner();
        secondRecreatedOwner.start();
        mViewModel.authenticationStatus().observe(secondRecreatedOwner, secondRecreatedObserver);
        assertEquals(1, recreatedObserverCalls.get());
        secondRecreatedOwner.destroy();
    }

    private static class TestLifecycleOwner implements LifecycleOwner {
        private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return mLifecycle;
        }

        void start() {
            mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        }

        void destroy() {
            mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        }
    }

    @Nullable
    private Integer awaitStatus() throws InterruptedException {
        for (int i = 0; i < 100; ++i) {
            ShadowLooper.idleMainLooper();
            Integer status = mViewModel.authenticationStatus().getValue();
            if (status != null) {
                return status;
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Implements(Ops.class)
    public static class ShadowOps {
        static final AtomicInteger initCalls = new AtomicInteger();
        static final AtomicInteger fallbackCalls = new AtomicInteger();
        static CountDownLatch operationStarted;
        static CountDownLatch continueOperation;
        static CountDownLatch operationFinished;
        static CountDownLatch operationInterrupted;
        static CountDownLatch fallbackCalled;
        static boolean blockInit;
        static boolean throwFromInit;

        static void reset() {
            initCalls.set(0);
            fallbackCalls.set(0);
            operationStarted = new CountDownLatch(1);
            continueOperation = new CountDownLatch(1);
            operationFinished = new CountDownLatch(1);
            operationInterrupted = new CountDownLatch(1);
            fallbackCalled = new CountDownLatch(1);
            blockInit = false;
            throwFromInit = false;
        }

        @Implementation
        public static int init(Context context, boolean force) {
            initCalls.incrementAndGet();
            operationStarted.countDown();
            if (throwFromInit) {
                throw new IllegalStateException("Simulated initialisation failure");
            }
            if (blockInit) {
                try {
                    continueOperation.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    operationInterrupted.countDown();
                    return Ops.STATUS_FAILURE;
                }
            }
            operationFinished.countDown();
            return Ops.STATUS_SUCCESS;
        }

        @Implementation
        public static int pairAdb(Context context) {
            operationStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                operationInterrupted.countDown();
            }
            return Ops.STATUS_FAILURE;
        }

        @Implementation
        public static void fallbackToNoRoot(Context context) {
            fallbackCalls.incrementAndGet();
            fallbackCalled.countDown();
        }
    }
}
