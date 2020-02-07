/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.camera.camera2.internal;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertTrue;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.Manifest;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraDevice;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.camera2.internal.compat.CameraManagerCompat;
import androidx.camera.camera2.internal.util.SemaphoreReleasingCamera2Callbacks;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.UseCase;
import androidx.camera.core.impl.CameraCaptureCallback;
import androidx.camera.core.impl.CameraCaptureResult;
import androidx.camera.core.impl.CameraFactory;
import androidx.camera.core.impl.CameraInternal;
import androidx.camera.core.impl.CameraStateRegistry;
import androidx.camera.core.impl.CaptureConfig;
import androidx.camera.core.impl.DeferrableSurface;
import androidx.camera.core.impl.ImmediateSurface;
import androidx.camera.core.impl.Observable;
import androidx.camera.core.impl.SessionConfig;
import androidx.camera.core.impl.UseCaseConfig;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.testing.CameraUtil;
import androidx.camera.testing.HandlerUtil;
import androidx.camera.testing.fakes.FakeCamera;
import androidx.camera.testing.fakes.FakeCameraInfoInternal;
import androidx.camera.testing.fakes.FakeUseCase;
import androidx.camera.testing.fakes.FakeUseCaseConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.GrantPermissionRule;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contains tests for {@link androidx.camera.camera2.internal.Camera2CameraImpl} internal
 * implementation.
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public final class Camera2CameraImplTest {
    @CameraSelector.LensFacing
    private static final int DEFAULT_LENS_FACING = CameraSelector.LENS_FACING_BACK;
    // For the purpose of this test, always say we have 1 camera available.
    private static final int DEFAULT_AVAILABLE_CAMERA_COUNT = 1;
    private static final Set<CameraInternal.State> STABLE_STATES = new HashSet<>(Arrays.asList(
            CameraInternal.State.CLOSED,
            CameraInternal.State.OPEN,
            CameraInternal.State.RELEASED));

    private static CameraFactory sCameraFactory;

    @Rule
    public GrantPermissionRule mRuntimePermissionRule = GrantPermissionRule.grant(
            Manifest.permission.CAMERA);

    private ArrayList<FakeUseCase> mFakeUseCases = new ArrayList<>();
    private Camera2CameraImpl mCamera2CameraImpl;
    private HandlerThread mCameraHandlerThread;
    private Handler mCameraHandler;
    private CameraStateRegistry mCameraStateRegistry;
    Semaphore mSemaphore;
    OnImageAvailableListener mMockOnImageAvailableListener;
    String mCameraId;
    SemaphoreReleasingCamera2Callbacks.SessionStateCallback mSessionStateCallback;

    private static String getCameraIdForLensFacingUnchecked(
            @CameraSelector.LensFacing int lensFacing) {
        try {
            return sCameraFactory.cameraIdForLensFacing(lensFacing);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to attach to camera with LensFacing " + lensFacing, e);
        }
    }

    @BeforeClass
    public static void classSetup() {
        sCameraFactory = new Camera2CameraFactory(ApplicationProvider.getApplicationContext());
    }

    @Before
    public void setup() {
        assumeTrue(CameraUtil.deviceHasCamera());
        mMockOnImageAvailableListener = Mockito.mock(ImageReader.OnImageAvailableListener.class);
        mSessionStateCallback = new SemaphoreReleasingCamera2Callbacks.SessionStateCallback();

        mCameraId = getCameraIdForLensFacingUnchecked(DEFAULT_LENS_FACING);
        mCameraHandlerThread = new HandlerThread("cameraThread");
        mCameraHandlerThread.start();
        mCameraHandler = new Handler(mCameraHandlerThread.getLooper());
        mSemaphore = new Semaphore(0);
        mCameraStateRegistry = new CameraStateRegistry(DEFAULT_AVAILABLE_CAMERA_COUNT);
        mCamera2CameraImpl = new Camera2CameraImpl(
                CameraManagerCompat.from(ApplicationProvider.getApplicationContext()), mCameraId,
                mCameraStateRegistry, mCameraHandler, mCameraHandler);
    }

    @After
    public void teardown() throws InterruptedException, ExecutionException {
        // Need to release the camera no matter what is done, otherwise the CameraDevice is not
        // closed.
        // When the CameraDevice is not closed, then it can cause problems with interferes with
        // other test cases.
        if (mCamera2CameraImpl != null) {
            ListenableFuture<Void> cameraReleaseFuture = mCamera2CameraImpl.release();

            // Wait for camera to be fully closed
            cameraReleaseFuture.get();

            mCamera2CameraImpl = null;
        }

        for (FakeUseCase fakeUseCase : mFakeUseCases) {
            fakeUseCase.clear();
        }

        if (mCameraHandlerThread != null) {
            mCameraHandlerThread.quitSafely();
        }
    }

    @Test
    public void onlineUseCase() {
        mCamera2CameraImpl.open();

        UseCase useCase = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase));

        verify(mMockOnImageAvailableListener, never()).onImageAvailable(any(ImageReader.class));

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase));
        mCamera2CameraImpl.release();
    }

    @Test
    public void activeUseCase() {
        mCamera2CameraImpl.open();
        mCamera2CameraImpl.onUseCaseActive(createUseCase());

        verify(mMockOnImageAvailableListener, never()).onImageAvailable(any(ImageReader.class));

        mCamera2CameraImpl.release();
    }

    @Test
    public void onlineAndActiveUseCase() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.onUseCaseActive(useCase1);

        verify(mMockOnImageAvailableListener, timeout(4000).atLeastOnce())
                .onImageAvailable(any(ImageReader.class));

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
    }

    @Test
    public void removeOnlineUseCase() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.onUseCaseActive(useCase1);

        verify(mMockOnImageAvailableListener, never()).onImageAvailable(any(ImageReader.class));
    }

    @Test
    public void unopenedCamera() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));

        verify(mMockOnImageAvailableListener, never()).onImageAvailable(any(ImageReader.class));
    }

    @Test
    public void closedCamera() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));

        verify(mMockOnImageAvailableListener, never()).onImageAvailable(any(ImageReader.class));
    }

    @Test
    public void releaseUnopenedCamera() {
        UseCase useCase1 = createUseCase();
        // Checks that if a camera has been released then calling open() will no longer open it.
        mCamera2CameraImpl.release();
        mCamera2CameraImpl.open();

        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.onUseCaseActive(useCase1);

        verify(mMockOnImageAvailableListener, never()).onImageAvailable(any(ImageReader.class));

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
    }

    @Test
    public void releasedOpenedCamera() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.open();
        mCamera2CameraImpl.release();

        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.onUseCaseActive(useCase1);

        verify(mMockOnImageAvailableListener, never()).onImageAvailable(any(ImageReader.class));

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
    }

    @Test
    public void addOnline_oneUseCase_isOnline() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));

        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase1)).isTrue();

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
    }

    @Test
    public void addOnline_sameUseCases_staysOnline() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        boolean onlineAfterFirstAdd = mCamera2CameraImpl.isUseCaseOnline(useCase1);

        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));

        assertThat(onlineAfterFirstAdd).isTrue();
        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase1)).isTrue();

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
    }

    @Test
    public void addOnline_twoUseCases_bothComeOnline() {
        UseCase useCase1 = createUseCase();
        UseCase useCase2 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1, useCase2));

        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase1)).isTrue();
        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase2)).isTrue();

        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1, useCase2));
    }

    @Test
    public void removeOnline_offlineUseCase_staysOffline() {
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));

        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase1)).isFalse();
    }

    @Test
    public void removeOneOnlineUseCase_fromOnlineUseCases_onlyTakesSingleUseCaseOffline() {
        UseCase useCase1 = createUseCase();
        UseCase useCase2 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1, useCase2));

        boolean useCase1isOnlineAfterFirstAdd = mCamera2CameraImpl.isUseCaseOnline(useCase1);
        boolean useCase2isOnlineAfterFirstAdd = mCamera2CameraImpl.isUseCaseOnline(useCase2);

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));

        assertThat(useCase1isOnlineAfterFirstAdd).isTrue();
        assertThat(useCase2isOnlineAfterFirstAdd).isTrue();
        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase1)).isFalse();
        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase2)).isTrue();

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase2));
    }

    @Test
    public void removeSameOnlineUseCaseTwice_onlyTakesSameUseCaseOffline() {
        UseCase useCase1 = createUseCase();
        UseCase useCase2 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1, useCase2));

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));

        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase1)).isFalse();
        assertThat(mCamera2CameraImpl.isUseCaseOnline(useCase2)).isTrue();

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase2));
    }

    @Test
    public void onlineUseCase_changeSurface_onUseCaseReset_correctAttachCount()
            throws ExecutionException, InterruptedException {
        blockHandler();

        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1));
        DeferrableSurface surface1 = useCase1.getSessionConfig(mCameraId).getSurfaces().get(0);

        unblockHandler();
        HandlerUtil.waitForLooperToIdle(mCameraHandler);

        changeUseCaseSurface(useCase1);
        mCamera2CameraImpl.onUseCaseReset(useCase1);
        DeferrableSurface surface2 = useCase1.getSessionConfig(mCameraId).getSurfaces().get(0);

        // Wait for camera to be released to ensure it has finished closing
        ListenableFuture<Void> releaseFuture = mCamera2CameraImpl.release();
        releaseFuture.get();

        assertThat(surface1).isNotEqualTo(surface2);

        // Old surface is decremented when CameraCaptueSession is closed by new
        // CameraCaptureSession.
        assertThat(surface1.getUseCount()).isEqualTo(0);
        // New surface is decremented when CameraCaptueSession is closed by
        // mCamera2CameraImpl.release()
        assertThat(surface2.getUseCount()).isEqualTo(0);
        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1));
    }

    @Test
    public void pendingSingleRequestRunSuccessfully_whenAnotherUseCaseOnline()
            throws InterruptedException {

        // Block camera thread to queue all the camera operations.
        blockHandler();

        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1));

        CameraCaptureCallback captureCallback = mock(CameraCaptureCallback.class);
        CaptureConfig.Builder captureConfigBuilder = new CaptureConfig.Builder();
        captureConfigBuilder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);
        captureConfigBuilder.addSurface(useCase1.getSessionConfig(mCameraId).getSurfaces().get(0));
        captureConfigBuilder.addCameraCaptureCallback(captureCallback);

        mCamera2CameraImpl.getCameraControlInternal().submitCaptureRequests(
                Arrays.asList(captureConfigBuilder.build()));

        UseCase useCase2 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase2));

        // Unblock camera handler to make camera operation run quickly .
        // To make the single request not able to run in 1st capture session.  and verify if it can
        // be carried over to the new capture session and run successfully.
        unblockHandler();
        HandlerUtil.waitForLooperToIdle(mCameraHandler);

        // CameraCaptureCallback.onCaptureCompleted() should be called to signal a capture attempt.
        verify(captureCallback, timeout(3000).times(1))
                .onCaptureCompleted(any(CameraCaptureResult.class));

        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1, useCase2));
    }

    @Test
    public void pendingSingleRequestSkipped_whenTheUseCaseIsRemoved()
            throws InterruptedException {

        // Block camera thread to queue all the camera operations.
        blockHandler();

        UseCase useCase1 = createUseCase();
        UseCase useCase2 = createUseCase();

        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1, useCase2));

        CameraCaptureCallback captureCallback = mock(CameraCaptureCallback.class);
        CaptureConfig.Builder captureConfigBuilder = new CaptureConfig.Builder();
        captureConfigBuilder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);
        captureConfigBuilder.addSurface(useCase1.getSessionConfig(mCameraId).getSurfaces().get(0));
        captureConfigBuilder.addCameraCaptureCallback(captureCallback);

        mCamera2CameraImpl.getCameraControlInternal().submitCaptureRequests(
                Arrays.asList(captureConfigBuilder.build()));
        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1));

        // Unblock camera handle to make camera operation run quickly .
        // To make the single request not able to run in 1st capture session.  and verify if it can
        // be carried to the new capture session and run successfully.
        unblockHandler();
        HandlerUtil.waitForLooperToIdle(mCameraHandler);

        // TODO: b/133710422 should provide a way to detect if request is cancelled.
        Thread.sleep(1000);

        // CameraCaptureCallback.onCaptureCompleted() is not called and there is no crash.
        verify(captureCallback, times(0))
                .onCaptureCompleted(any(CameraCaptureResult.class));

        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase2));
    }

    @Test
    public void cameraStateIsClosed_afterInitialization()
            throws ExecutionException, InterruptedException {
        Observable<CameraInternal.State> state = mCamera2CameraImpl.getCameraState();
        CameraInternal.State currentState = state.fetchData().get();
        assertThat(currentState).isEqualTo(CameraInternal.State.CLOSED);
    }

    @Test
    public void cameraStateTransitionTest() throws InterruptedException {

        final AtomicReference<CameraInternal.State> lastStableState = new AtomicReference<>(null);
        Observable.Observer<CameraInternal.State> observer =
                new Observable.Observer<CameraInternal.State>() {
                    @Override
                    public void onNewData(@Nullable CameraInternal.State value) {
                        // Ignore any transient states.
                        if (STABLE_STATES.contains(value)) {
                            lastStableState.set(value);
                            mSemaphore.release();
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable t) { /* Ignore any transient errors. */ }
                };

        List<CameraInternal.State> observedStates = new ArrayList<>();
        mCamera2CameraImpl.getCameraState().addObserver(CameraXExecutors.directExecutor(),
                observer);

        // Wait for initial CLOSED state
        mSemaphore.acquire();
        observedStates.add(lastStableState.get());

        // Wait for OPEN state
        mCamera2CameraImpl.open();
        mSemaphore.acquire();
        observedStates.add(lastStableState.get());

        // Wait for CLOSED state again
        mCamera2CameraImpl.close();
        mSemaphore.acquire();
        observedStates.add(lastStableState.get());

        // Wait for RELEASED state
        mCamera2CameraImpl.release();
        mSemaphore.acquire();
        observedStates.add(lastStableState.get());

        mCamera2CameraImpl.getCameraState().removeObserver(observer);

        assertThat(observedStates).containsExactly(
                CameraInternal.State.CLOSED,
                CameraInternal.State.OPEN,
                CameraInternal.State.CLOSED,
                CameraInternal.State.RELEASED);
    }

    @Test
    public void cameraTransitionsThroughPendingState_whenNoCamerasAvailable() {
        @SuppressWarnings("unchecked") // Cannot mock generic type inline
                Observable.Observer<CameraInternal.State> mockObserver =
                mock(Observable.Observer.class);

        // Ensure real camera can't open due to max cameras being open
        Camera mockCamera = mock(Camera.class);
        mCameraStateRegistry.registerCamera(mockCamera, CameraXExecutors.directExecutor(),
                () -> {
                });
        mCameraStateRegistry.tryOpenCamera(mockCamera);


        mCamera2CameraImpl.getCameraState().addObserver(CameraXExecutors.directExecutor(),
                mockObserver);

        mCamera2CameraImpl.open();

        // Ensure that the camera gets to a PENDING_OPEN state
        verify(mockObserver, timeout(3000).atLeastOnce()).onNewData(
                CameraInternal.State.PENDING_OPEN);

        // Allow camera to be opened
        mCameraStateRegistry.markCameraState(mockCamera, CameraInternal.State.CLOSED);

        verify(mockObserver, timeout(3000)).onNewData(CameraInternal.State.OPEN);

        mCamera2CameraImpl.getCameraState().removeObserver(mockObserver);
    }

    @Test
    public void cameraStateIsReleased_afterRelease()
            throws ExecutionException, InterruptedException {
        Observable<CameraInternal.State> state = mCamera2CameraImpl.getCameraState();

        // Wait for camera to release
        mCamera2CameraImpl.release().get();
        CameraInternal.State currentState = state.fetchData().get();

        assertThat(currentState).isEqualTo(CameraInternal.State.RELEASED);
    }

    @Test
    public void openNewCaptureSessionImmediateBeforePreviousCaptureSessionClosed()
            throws InterruptedException {
        mCamera2CameraImpl.open();
        UseCase useCase1 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1));
        mCamera2CameraImpl.onUseCaseActive(useCase1);

        // Wait a little bit for the camera to open.
        assertTrue(mSessionStateCallback.waitForOnConfigured(1));

        // Remove the useCase1 and trigger the CaptureSession#close().
        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1));

        // Create the secondary use case immediately and open it before the first use case closed.
        UseCase useCase2 = createUseCase();
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase2));
        mCamera2CameraImpl.onUseCaseActive(useCase2);
        // Wait for the secondary capture session is configured.
        assertTrue(mSessionStateCallback.waitForOnConfigured(1));

        Observable.Observer<CameraInternal.State> mockObserver = mock(Observable.Observer.class);
        mCamera2CameraImpl.getCameraState().addObserver(CameraXExecutors.directExecutor(),
                mockObserver);
        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase2));
        mCamera2CameraImpl.close();

        // Wait for the CLOSED state. If the test fail, the CameraX might in wrong internal state,
        // and the Camera2CameraImpl#release() might stuck.
        verify(mockObserver, timeout(4000).times(1)).onNewData(CameraInternal.State.CLOSED);
    }

    // Blocks the camera thread handler.
    private void blockHandler() {
        mCameraHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mSemaphore.acquire();
                } catch (InterruptedException e) {

                }
            }
        });
    }

    // unblock camera thread handler
    private void unblockHandler() {
        mSemaphore.release();
    }

    private UseCase createUseCase() {
        FakeUseCaseConfig.Builder configBuilder =
                new FakeUseCaseConfig.Builder().setTargetName("UseCase");
        new Camera2Interop.Extender<>(configBuilder).setSessionStateCallback(mSessionStateCallback);
        CameraSelector selector =
                new CameraSelector.Builder().requireLensFacing(
                        CameraSelector.LENS_FACING_BACK).build();
        TestUseCase testUseCase = new TestUseCase(configBuilder.getUseCaseConfig(), selector,
                mMockOnImageAvailableListener);
        Map<String, Size> suggestedResolutionMap = new HashMap<>();
        suggestedResolutionMap.put(mCameraId, new Size(640, 480));
        testUseCase.updateSuggestedResolution(suggestedResolutionMap);
        mFakeUseCases.add(testUseCase);
        return testUseCase;
    }

    @Test
    public void useCaseOnStateOnline_isCalled() throws InterruptedException {
        TestUseCase useCase1 = spy((TestUseCase) createUseCase());
        TestUseCase useCase2 = spy((TestUseCase) createUseCase());

        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1, useCase2));

        HandlerUtil.waitForLooperToIdle(mCameraHandler);

        Handler uiThreadHandler = new Handler(Looper.getMainLooper());
        HandlerUtil.waitForLooperToIdle(uiThreadHandler);

        verify(useCase1, times(1)).onStateOnline(eq(mCameraId));
        verify(useCase2, times(1)).onStateOnline(eq(mCameraId));

        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1, useCase2));
    }

    @Test
    public void useCaseOnStateOffline_isCalled() throws InterruptedException {
        TestUseCase useCase1 = spy((TestUseCase) createUseCase());
        TestUseCase useCase2 = spy((TestUseCase) createUseCase());
        TestUseCase useCase3 = spy((TestUseCase) createUseCase());

        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1, useCase2));

        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1, useCase2, useCase3));

        HandlerUtil.waitForLooperToIdle(mCameraHandler);

        Handler uiThreadHandler = new Handler(Looper.getMainLooper());
        HandlerUtil.waitForLooperToIdle(uiThreadHandler);

        verify(useCase1, times(1)).onStateOffline(eq(mCameraId));
        verify(useCase2, times(1)).onStateOffline(eq(mCameraId));
        verify(useCase3, times(0)).onStateOffline(eq(mCameraId));
    }

    private boolean isCameraControlActive(Camera2CameraControl camera2CameraControl) {
        ListenableFuture<Void> listenableFuture = camera2CameraControl.setZoomRatio(2.0f);
        try {
            // setZoom() will fail immediately when Cameracontrol is not active.
            listenableFuture.get(50, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CameraControl.OperationCanceledException) {
                return false;
            }
        } catch (InterruptedException | TimeoutException e) {
        }
        return true;
    }

    @Test
    public void activateCameraControl_whenExsitsOnlineUseCases() throws InterruptedException {
        Camera2CameraControl camera2CameraControl =
                (Camera2CameraControl) mCamera2CameraImpl.getCameraControlInternal();

        assertThat(isCameraControlActive(camera2CameraControl)).isFalse();

        UseCase useCase1 = createUseCase();

        mCamera2CameraImpl.addOnlineUseCase(Collections.singletonList(useCase1));
        HandlerUtil.waitForLooperToIdle(mCameraHandler);

        assertThat(isCameraControlActive(camera2CameraControl)).isTrue();

        mCamera2CameraImpl.removeOnlineUseCase(Collections.singletonList(useCase1));
    }

    @Test
    public void deactivateCameraControl_whenNoOnlineUseCases() throws InterruptedException {
        Camera2CameraControl camera2CameraControl =
                (Camera2CameraControl) mCamera2CameraImpl.getCameraControlInternal();
        UseCase useCase1 = createUseCase();

        mCamera2CameraImpl.addOnlineUseCase(Arrays.asList(useCase1));
        HandlerUtil.waitForLooperToIdle(mCameraHandler);
        assertThat(isCameraControlActive(camera2CameraControl)).isTrue();

        mCamera2CameraImpl.removeOnlineUseCase(Arrays.asList(useCase1));
        HandlerUtil.waitForLooperToIdle(mCameraHandler);

        assertThat(isCameraControlActive(camera2CameraControl)).isFalse();
    }

    private DeferrableSurface getUseCaseSurface(UseCase useCase) {
        return useCase.getSessionConfig(mCameraId).getSurfaces().get(0);
    }

    private void changeUseCaseSurface(UseCase useCase) {
        Map<String, Size> suggestedResolutionMap = new HashMap<>();
        suggestedResolutionMap.put(mCameraId, new Size(640, 480));
        useCase.updateSuggestedResolution(suggestedResolutionMap);
    }

    private void waitForCameraClose(Camera2CameraImpl camera2CameraImpl)
            throws InterruptedException {
        Semaphore semaphore = new Semaphore(0);

        Observable.Observer<CameraInternal.State> observer =
                new Observable.Observer<CameraInternal.State>() {
                    @Override
                    public void onNewData(@Nullable CameraInternal.State value) {
                        // Ignore any transient states.
                        if (value == CameraInternal.State.CLOSED) {
                            semaphore.release();
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable t) { /* Ignore any transient errors. */ }
                };

        camera2CameraImpl.getCameraState().addObserver(CameraXExecutors.directExecutor(), observer);

        // Wait until camera reaches closed state
        semaphore.acquire();
    }

    public static class TestUseCase extends FakeUseCase {
        private final ImageReader.OnImageAvailableListener mImageAvailableListener;
        HandlerThread mHandlerThread = new HandlerThread("HandlerThread");
        Handler mHandler;
        FakeUseCaseConfig mConfig;
        private String mCameraId;
        private DeferrableSurface mDeferrableSurface;

        TestUseCase(
                @NonNull FakeUseCaseConfig config,
                @NonNull CameraSelector cameraSelector,
                @NonNull ImageReader.OnImageAvailableListener listener) {
            super(config);
            // Ensure we're using the combined configuration (user config + defaults)
            mConfig = (FakeUseCaseConfig) getUseCaseConfig();

            mImageAvailableListener = listener;
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
            Map<String, Size> suggestedResolutionMap = new HashMap<>();
            Integer lensFacing =
                    cameraSelector.getLensFacing() == null ? CameraSelector.LENS_FACING_BACK :
                            cameraSelector.getLensFacing();
            mCameraId = getCameraIdForLensFacingUnchecked(lensFacing);
            onBind(new FakeCamera(mCameraId, null,
                    new FakeCameraInfoInternal(mCameraId, 0, lensFacing)));
            suggestedResolutionMap.put(mCameraId, new Size(640, 480));
            updateSuggestedResolution(suggestedResolutionMap);
        }

        public void close() {
            mHandler.removeCallbacksAndMessages(null);
            mHandlerThread.quitSafely();
            if (mDeferrableSurface != null) {
                mDeferrableSurface.close();
            }
        }

        @Override
        public void clear() {
            super.clear();
            close();
        }

        // we need to set Camera2OptionUnpacker to the Config to enable the camera2 callback hookup.
        @Override
        protected UseCaseConfig.Builder<?, ?, ?> getDefaultBuilder(
                @Nullable CameraInfo cameraInfo) {
            return new FakeUseCaseConfig.Builder()
                    .setSessionOptionUnpacker(new Camera2SessionOptionUnpacker());
        }

        @Override
        @NonNull
        protected Map<String, Size> onSuggestedResolutionUpdated(
                @NonNull Map<String, Size> suggestedResolutionMap) {
            Size resolution = suggestedResolutionMap.get(mCameraId);
            SessionConfig.Builder builder = SessionConfig.Builder.createFrom(mConfig);

            builder.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);
            if (mDeferrableSurface != null) {
                mDeferrableSurface.close();
            }
            ImageReader imageReader =
                    ImageReader.newInstance(
                            resolution.getWidth(),
                            resolution.getHeight(),
                            ImageFormat.YUV_420_888, /*maxImages*/
                            2);
            imageReader.setOnImageAvailableListener(mImageAvailableListener, mHandler);
            Surface surface = imageReader.getSurface();
            mDeferrableSurface = new ImmediateSurface(surface);
            mDeferrableSurface.getTerminationFuture().addListener(() -> {
                surface.release();
                imageReader.close();
            }, CameraXExecutors.directExecutor());
            builder.addSurface(mDeferrableSurface);

            attachToCamera(mCameraId, builder.build());
            return suggestedResolutionMap;
        }
    }
}
