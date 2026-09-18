// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = MainPreferencesViewModelTest.ShadowOps.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class MainPreferencesViewModelTest {
    private MainPreferencesViewModel mViewModel;

    @Before
    public void setUp() {
        ShadowOps.reset();
        mViewModel = new MainPreferencesViewModel(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        mViewModel.onCleared();
    }

    @Test
    public void duplicateModeInitialisationIsIgnoredWhilePreviousOperationIsPending()
            throws InterruptedException {
        ShadowOps.blockOperation = true;
        mViewModel.setModeOfOps();
        assertTrue(ShadowOps.operationStarted.await(5, TimeUnit.SECONDS));

        mViewModel.setModeOfOps();
        ShadowOps.continueOperation.countDown();

        assertTrue(ShadowOps.operationFinished.await(5, TimeUnit.SECONDS));
        awaitStatus(Ops.STATUS_SUCCESS);
        assertEquals(1, ShadowOps.initCalls.get());
    }

    @Test
    public void duplicateAdbConnectionIsIgnoredWhilePreviousOperationIsPending()
            throws InterruptedException {
        ShadowOps.blockOperation = true;
        mViewModel.connectAdb(5555);
        assertTrue(ShadowOps.operationStarted.await(5, TimeUnit.SECONDS));

        mViewModel.connectAdb(5556);
        ShadowOps.continueOperation.countDown();

        assertTrue(ShadowOps.operationFinished.await(5, TimeUnit.SECONDS));
        awaitStatus(Ops.STATUS_SUCCESS);
        assertEquals(1, ShadowOps.connectCalls.get());
    }

    private void awaitStatus(int expected) throws InterruptedException {
        for (int i = 0; i < 100; ++i) {
            ShadowLooper.idleMainLooper();
            Integer status = mViewModel.getModeOfOpsStatus().getValue();
            if (Integer.valueOf(expected).equals(status)) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(Integer.valueOf(expected), mViewModel.getModeOfOpsStatus().getValue());
    }

    @Implements(Ops.class)
    public static class ShadowOps {
        static final AtomicInteger initCalls = new AtomicInteger();
        static final AtomicInteger connectCalls = new AtomicInteger();
        static CountDownLatch operationStarted;
        static CountDownLatch continueOperation;
        static CountDownLatch operationFinished;
        static boolean blockOperation;

        static void reset() {
            initCalls.set(0);
            connectCalls.set(0);
            operationStarted = new CountDownLatch(1);
            continueOperation = new CountDownLatch(1);
            operationFinished = new CountDownLatch(1);
            blockOperation = false;
        }

        @Implementation
        public static int init(Context context, boolean force) {
            initCalls.incrementAndGet();
            return runOperation();
        }

        @Implementation
        public static int connectAdb(Context context, int port, int returnCodeOnFailure) {
            connectCalls.incrementAndGet();
            return runOperation();
        }

        @Implementation
        public static void fallbackToNoRoot(Context context) {
        }

        private static int runOperation() {
            operationStarted.countDown();
            if (blockOperation) {
                try {
                    continueOperation.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Ops.STATUS_FAILURE;
                }
            }
            operationFinished.countDown();
            return Ops.STATUS_SUCCESS;
        }
    }
}
