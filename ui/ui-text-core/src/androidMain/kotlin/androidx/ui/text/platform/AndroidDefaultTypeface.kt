/*
 * Copyright 2020 The Android Open Source Project
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

package androidx.ui.text.platform

import android.graphics.Typeface
import android.os.Build
import androidx.ui.text.font.DefaultFontFamily
import androidx.ui.text.font.FontFamily
import androidx.ui.text.font.FontStyle
import androidx.ui.text.font.FontSynthesis
import androidx.ui.text.font.FontWeight

/**
 * An implementation of [AndroidTypeface] for [DefaultFontFamily]
 */
internal class AndroidDefaultTypeface : AndroidTypeface {

    override val fontFamily: FontFamily = FontFamily.Default

    override fun getNativeTypeface(
        fontWeight: FontWeight,
        fontStyle: FontStyle,
        synthesis: FontSynthesis
    ): Typeface {
        return if (Build.VERSION.SDK_INT < 28) {
            Typeface.defaultFromStyle(
                TypefaceAdapter.getTypefaceStyle(fontWeight, fontStyle))
        } else {
            Typeface.create(Typeface.DEFAULT, fontWeight.weight, fontStyle == FontStyle.Italic)
        }
    }
}