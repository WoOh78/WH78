/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.fragment

import android.Manifest
import android.app.Dialog
import android.content.DialogInterface
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.databinding.Observable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import org.amnezia.awg.BR
import org.amnezia.awg.R
import org.amnezia.awg.dashboard.Wh78DialogTheme
import org.amnezia.awg.databinding.AppListDialogFragmentBinding
import org.amnezia.awg.databinding.ObservableKeyedArrayList
import org.amnezia.awg.model.ApplicationData
import org.amnezia.awg.util.ErrorMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListDialogFragment : DialogFragment() {
    private val appData = ObservableKeyedArrayList<String, ApplicationData>()
    private var currentlySelectedApps = emptyList<String>()
    private var initiallyExcluded = false
    private var tabs: TabLayout? = null
    private var dataLoaded = false
    private var resultSent = false

    private fun loadData() {
        val activity = activity ?: return
        val pm = activity.packageManager
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val applicationData: MutableList<ApplicationData> = ArrayList()
                withContext(Dispatchers.IO) {
                    val packageInfos = getPackagesHoldingPermissions(pm, arrayOf(Manifest.permission.INTERNET))
                    packageInfos.forEach {
                        val packageName = it.packageName
                        val appInfo = it.applicationInfo
                        val appData =
                            ApplicationData(appInfo?.loadIcon(pm) ?: pm.defaultActivityIcon, appInfo?.loadLabel(pm)?.toString() ?: packageName, packageName, currentlySelectedApps.contains(packageName))
                        applicationData.add(appData)
                    }
                }
                applicationData.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                withContext(Dispatchers.Main.immediate) {
                    appData.clear()
                    appData.addAll(applicationData)
                    dataLoaded = true
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    val error = ErrorMessages[e]
                    val message = activity.getString(R.string.error_fetching_apps, error)
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    dismissAllowingStateLoss()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentlySelectedApps = (arguments?.getStringArrayList(KEY_SELECTED_APPS) ?: emptyList())
        initiallyExcluded = arguments?.getBoolean(KEY_IS_EXCLUDED) ?: true
    }

    private fun getPackagesHoldingPermissions(pm: PackageManager, permissions: Array<String>): List<PackageInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackagesHoldingPermissions(permissions, PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackagesHoldingPermissions(permissions, 0)
        }
    }


    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val themedContext = Wh78DialogTheme.wrap(requireActivity())
        val alertDialogBuilder = MaterialAlertDialogBuilder(themedContext)
        val binding = AppListDialogFragmentBinding.inflate(LayoutInflater.from(themedContext), null, false)
        binding.executePendingBindings()
        alertDialogBuilder.setView(binding.root)

        tabs = binding.tabs
        tabs?.apply {
            selectTab(binding.tabs.getTabAt(if (initiallyExcluded) 0 else 1))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabReselected(tab: TabLayout.Tab?) = Unit
                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabSelected(tab: TabLayout.Tab?) = Unit
            })
        }

        binding.fragment = this
        binding.appData = appData
        binding.root.findViewById<View>(R.id.app_picker_back).setOnClickListener {
            // Keep the original dismiss semantics: onDismiss saves the current selection
            // only after the app list has finished loading.
            dismiss()
        }
        binding.root.findViewById<View>(R.id.app_picker_done).setOnClickListener {
            setSelectionAndDismiss()
        }
        loadData()

        val dialog = alertDialogBuilder.create()
        dialog.window?.setWindowAnimations(R.style.Wh78FullPageDialogAnimation)
        dialog.setOnShowListener {
            val window = dialog.window ?: return@setOnShowListener
            val surface = MaterialColors.getColor(
                themedContext,
                com.google.android.material.R.attr.colorSurface,
                Color.BLACK
            )
            window.setBackgroundDrawable(ColorDrawable(surface))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setGravity(Gravity.FILL)
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.statusBarColor = surface
            window.navigationBarColor = surface
            window.setWindowAnimations(R.style.Wh78FullPageDialogAnimation)
            window.decorView.systemUiVisibility = if (org.amnezia.awg.Application.get().currentAppThemeKey.lowercase() == "light") {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                0
            }
        }
        return dialog
    }

    private fun setSelectionAndDismiss() {
        if (resultSent) return
        resultSent = true
        val selectedApps: MutableList<String> = ArrayList()
        for (data in appData) {
            if (data.isSelected) {
                selectedApps.add(data.packageName)
            }
        }
        setFragmentResult(
            REQUEST_SELECTION, bundleOf(
                KEY_SELECTED_APPS to selectedApps.toTypedArray(),
                KEY_IS_EXCLUDED to (tabs?.selectedTabPosition == 0)
            )
        )
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (dataLoaded && !resultSent) {
            setSelectionAndDismiss()
            return
        }
        super.onDismiss(dialog)
    }

    companion object {
        const val KEY_SELECTED_APPS = "selected_apps"
        const val KEY_IS_EXCLUDED = "is_excluded"
        const val REQUEST_SELECTION = "request_selection"

        fun newInstance(selectedApps: ArrayList<String?>?, isExcluded: Boolean): AppListDialogFragment {
            val extras = Bundle()
            extras.putStringArrayList(KEY_SELECTED_APPS, selectedApps)
            extras.putBoolean(KEY_IS_EXCLUDED, isExcluded)
            val fragment = AppListDialogFragment()
            fragment.arguments = extras
            return fragment
        }
    }
}
