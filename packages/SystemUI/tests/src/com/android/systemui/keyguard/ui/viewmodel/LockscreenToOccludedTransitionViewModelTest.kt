/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor.Companion.TO_OCCLUDED_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AnimationParams
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel.Companion.LOCKSCREEN_ALPHA
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel.Companion.LOCKSCREEN_TRANSLATION_Y
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class LockscreenToOccludedTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: LockscreenToOccludedTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        val interactor = KeyguardTransitionInteractor(repository)
        underTest = LockscreenToOccludedTransitionViewModel(interactor)
    }

    @Test
    fun lockscreenFadeOut() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.lockscreenAlpha.onEach { values.add(it) }.launchIn(this)

            // Should start running here...
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.4f))
            // ...up to here
            repository.sendTransitionStep(step(0.7f))
            repository.sendTransitionStep(step(1f))

            // Only 3 values should be present, since the dream overlay runs for a small fraction
            // of the overall animation time
            assertThat(values.size).isEqualTo(3)
            assertThat(values[0]).isEqualTo(1f - animValue(0f, LOCKSCREEN_ALPHA))
            assertThat(values[1]).isEqualTo(1f - animValue(0.1f, LOCKSCREEN_ALPHA))
            assertThat(values[2]).isEqualTo(1f - animValue(0.4f, LOCKSCREEN_ALPHA))

            job.cancel()
        }

    @Test
    fun lockscreenTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.lockscreenTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            // Should start running here...
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(1f))
            // ...up to here

            assertThat(values.size).isEqualTo(4)
            assertThat(values[0])
                .isEqualTo(
                    EMPHASIZED_ACCELERATE.getInterpolation(
                        animValue(0f, LOCKSCREEN_TRANSLATION_Y)
                    ) * pixels
                )
            assertThat(values[1])
                .isEqualTo(
                    EMPHASIZED_ACCELERATE.getInterpolation(
                        animValue(0.3f, LOCKSCREEN_TRANSLATION_Y)
                    ) * pixels
                )
            assertThat(values[2])
                .isEqualTo(
                    EMPHASIZED_ACCELERATE.getInterpolation(
                        animValue(0.5f, LOCKSCREEN_TRANSLATION_Y)
                    ) * pixels
                )
            assertThat(values[3])
                .isEqualTo(
                    EMPHASIZED_ACCELERATE.getInterpolation(
                        animValue(1f, LOCKSCREEN_TRANSLATION_Y)
                    ) * pixels
                )
            job.cancel()
        }

    private fun animValue(stepValue: Float, params: AnimationParams): Float {
        val totalDuration = TO_OCCLUDED_DURATION
        val startValue = (params.startTime / totalDuration).toFloat()

        val multiplier = (totalDuration / params.duration).toFloat()
        return (stepValue - startValue) * multiplier
    }

    private fun step(value: Float): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.OCCLUDED,
            value = value,
            transitionState = TransitionState.RUNNING,
            ownerName = "LockscreenToOccludedTransitionViewModelTest"
        )
    }
}
