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

package androidx.ui.layout

import androidx.annotation.FloatRange
import androidx.compose.Children
import androidx.compose.Composable
import androidx.compose.composer
import androidx.ui.core.Constraints
import androidx.ui.core.Dp
import androidx.ui.core.IntPx
import androidx.ui.core.IntPxSize
import androidx.ui.core.Layout
import androidx.ui.core.Measurable
import androidx.ui.core.ParentData
import androidx.ui.core.Placeable
import androidx.ui.core.coerceAtLeast
import androidx.ui.core.coerceIn
import androidx.ui.core.isFinite
import androidx.ui.core.max
import androidx.ui.core.min

/**
 * Collects information about the children of a [Table] when
 * its body is executed with a [TableChildren] as argument.
 */
class TableChildren internal constructor() {

    internal val tableChildren = mutableListOf<@Composable() () -> Unit>()
    private var rowGroup = 0

    fun tableRow(children: @Composable() () -> Unit) {
        tableChildren += {
            ParentData(data = TableChildData(rowGroup++), children = children)
        }
    }
}

/**
 * Parent data associated with children to assign a row group.
 */
private data class TableChildData(val rowGroup: Int)

private val Measurable.rowGroup get() = (parentData as TableChildData).rowGroup

/**
 * Used to specify the size of a [Table]'s column.
 */
sealed class TableColumnWidth {
    /**
     * Sizes the column by taking a part of the remaining space according
     * to [flex] once all the inflexible columns have been measured.
     */
    data class Flexible(internal val flex: Float) : TableColumnWidth()

    sealed class Inflexible : TableColumnWidth() {
        /**
         * Sizes the column to be the width of the widest child in that column.
         */
        object Wrap : Inflexible()

        /**
         * Sizes the column to a specific width.
         */
        data class Fixed(internal val width: Dp) : Inflexible()

        /**
         * Sizes the column to a fraction of the table’s maximum width constraint.
         */
        data class Fraction(
            @FloatRange(from = 0.0, to = 1.0) internal val fraction: Float
        ) : Inflexible()

        /**
         * Sizes the column such that it is the size that is the min of two width specifications.
         */
        data class Min(internal val a: Inflexible, internal val b: Inflexible) : Inflexible()

        /**
         * Sizes the column such that it is the size that is the max of two width specifications.
         */
        data class Max(internal val a: Inflexible, internal val b: Inflexible) : Inflexible()
    }
}

/**
 * Layout model that arranges its children into rows and columns.
 *
 * Example usage:
 *
 * @sample androidx.ui.layout.samples.SimpleTable
 *
 * @sample androidx.ui.layout.samples.TableWithDifferentColumnWidths
 */
@Composable
fun Table(
    childAlignment: Alignment = Alignment.TopLeft,
    columnWidth: (columnIndex: Int) -> TableColumnWidth = { TableColumnWidth.Flexible(1f) },
    block: TableChildren.() -> Unit
) {
    val children: @Composable() () -> Unit = with(TableChildren()) {
        apply(block)
        val composable = @Composable {
            tableChildren.forEach { it() }
        }
        composable
    }

    Layout(children) { m, constraints ->
        // Group the measurables into rows using rowGroup.
        val measurables = m.groupBy { it.rowGroup }.values.toTypedArray()

        val rowCount = measurables.size
        val columnCount = measurables.map { it.size }.max() ?: 0

        var totalFlex = 0f
        var availableSpace = if (constraints.maxWidth.isFinite()) {
            constraints.maxWidth
        } else {
            constraints.minWidth
        }

        val rowHeights = Array(rowCount) { IntPx.Zero }
        val columnWidths = Array(columnCount) { IntPx.Zero }

        val placeables = Array(rowCount) { arrayOfNulls<Placeable>(columnCount) }

        // Compute the actual width of a column for the given specification.
        fun TableColumnWidth.Inflexible.computeWidth(column: Int): IntPx {
            return when (this) {
                is TableColumnWidth.Inflexible.Wrap -> {
                    // Measure children in this column to get their preferred widths.
                    // TODO(calintat): Use minIntrinsicWidth and delay measuring until later.
                    var result = IntPx.Zero
                    for (row in 0 until rowCount) {
                        val p = placeables[row][column]
                        if (p != null) {
                            result = max(result, p.width)
                        } else {
                            val placeable = measurables[row][column].measure(Constraints())
                            placeables[row][column] = placeable
                            result = max(result, placeable.width)
                        }
                    }
                    result
                }
                is TableColumnWidth.Inflexible.Fixed -> {
                    this.width.toIntPx()
                }
                is TableColumnWidth.Inflexible.Fraction -> {
                    if (constraints.maxWidth.isFinite()) {
                        constraints.maxWidth * this.fraction
                    } else {
                        IntPx.Zero
                    }
                }
                is TableColumnWidth.Inflexible.Min -> {
                    min(this.a.computeWidth(column), this.b.computeWidth(column))
                }
                is TableColumnWidth.Inflexible.Max -> {
                    max(this.a.computeWidth(column), this.b.computeWidth(column))
                }
            }
        }

        // Compute widths of inflexible columns.
        for (column in 0 until columnCount) {
            when (val spec = columnWidth(column)) {
                is TableColumnWidth.Flexible -> {
                    totalFlex += spec.flex
                }
                is TableColumnWidth.Inflexible -> {
                    columnWidths[column] = spec.computeWidth(column)
                    availableSpace -= columnWidths[column]
                }
            }
        }

        availableSpace = availableSpace.coerceAtLeast(IntPx.Zero)

        // Compute widths of flex columns.
        for (column in 0 until columnCount) {
            val spec = columnWidth(column)
            if (spec is TableColumnWidth.Flexible) {
                columnWidths[column] = availableSpace * (spec.flex / totalFlex)
            }
        }

        // Measure the remaining children and calculate row heights.
        for (row in 0 until rowCount) {
            for (column in 0 until columnCount) {
                if (placeables[row][column] == null) {
                    placeables[row][column] = measurables[row][column].measure(
                        Constraints(minWidth = IntPx.Zero, maxWidth = columnWidths[column]))
                }
                rowHeights[row] = max(rowHeights[row], placeables[row][column]!!.height)
            }
        }

        // TODO(calintat): Figure out what to do when these exceed max constraints.
        val tableWidth = columnWidths.sum().coerceIn(constraints.minWidth, constraints.maxWidth)
        val tableHeight = rowHeights.sum().coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(tableWidth, tableHeight) {
            for (row in 0 until rowCount) {
                for (column in 0 until columnCount) {
                    val placeable = placeables[row][column]!!
                    val position = childAlignment.align(
                        IntPxSize(
                            width = columnWidths[column] - placeable.width,
                            height = rowHeights[row] - placeable.height
                        )
                    )
                    placeable.place(
                        x = columnWidths.take(column).sum() + position.x,
                        y = rowHeights.take(row).sum() + position.y
                    )
                }
            }
        }
    }
}

internal fun Array<IntPx>.sum() = this.fold(IntPx.Zero) { a, b -> a + b }
internal fun Collection<IntPx>.sum() = this.fold(IntPx.Zero) { a, b -> a + b }