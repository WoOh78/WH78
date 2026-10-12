/*
 * Copyright © 2013 The Android Open Source Project
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Parcelable
import android.util.AttributeSet
import androidx.core.graphics.ColorUtils
import com.google.android.material.materialswitch.MaterialSwitch

class ToggleSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : MaterialSwitch(context, attrs) {
    private var isRestoringState = false

    init {
        applyWh78Colors()
    }

    private fun applyWh78Colors() {
        val accent = resolveThemeColor(androidx.appcompat.R.attr.colorControlActivated, Color.rgb(77, 226, 160))
        val inactive = Color.rgb(140, 148, 153)
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, inactive)
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(accent, 135), ColorUtils.setAlphaComponent(inactive, 95))
        )
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) androidx.core.content.ContextCompat.getColor(context, value.resourceId) else value.data
        } else {
            fallback
        }
    }
    private var listener: OnBeforeCheckedChangeListener? = null
    override fun onRestoreInstanceState(state: Parcelable) {
        isRestoringState = true
        super.onRestoreInstanceState(state)
        isRestoringState = false
    }

    override fun setChecked(checked: Boolean) {
        if (checked == isChecked) return
        if (isRestoringState || listener == null) {
            super.setChecked(checked)
            return
        }
        isEnabled = false
        listener!!.onBeforeCheckedChanged(this, checked)
    }

    fun setCheckedInternal(checked: Boolean) {
        super.setChecked(checked)
        isEnabled = true
    }

    fun setOnBeforeCheckedChangeListener(listener: OnBeforeCheckedChangeListener?) {
        this.listener = listener
    }

    interface OnBeforeCheckedChangeListener {
        fun onBeforeCheckedChanged(toggleSwitch: ToggleSwitch?, checked: Boolean)
    }
}
