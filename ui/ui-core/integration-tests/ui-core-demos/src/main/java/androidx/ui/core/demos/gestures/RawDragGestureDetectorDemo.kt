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

package androidx.ui.core.demos.gestures

import androidx.compose.runtime.Composable
import androidx.compose.runtime.state
import androidx.ui.core.Alignment
import androidx.ui.core.DensityAmbient
import androidx.ui.core.Modifier
import androidx.ui.core.gesture.DragObserver
import androidx.ui.core.gesture.rawDragGestureFilter
import androidx.compose.foundation.Box
import androidx.compose.foundation.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.preferredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * Simple [rawDragGestureFilter] demo.
 */
@Composable
fun RawDragGestureFilterDemo() {
    val offset = state { Offset.Zero }

    val dragObserver = object : DragObserver {
        override fun onDrag(dragDistance: Offset): Offset {
            offset.value += dragDistance
            return dragDistance
        }
    }

    val (offsetX, offsetY) =
        with(DensityAmbient.current) { offset.value.x.toDp() to offset.value.y.toDp() }

    Column {
        Text("Demonstrates dragging that starts immediately (no slop or anything else).")
        Box(
            Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
                .offset(offsetX, offsetY)
                .preferredSize(192.dp)
                .rawDragGestureFilter(dragObserver),
            backgroundColor = Grey
        )
    }
}
