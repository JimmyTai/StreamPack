/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.thibaultbee.streampack.app.ui.settings

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import androidx.preference.*
import com.github.thibaultbee.streampack.app.R
import com.github.thibaultbee.streampack.app.configuration.ConfigurationHelper

class SettingsFragment : PreferenceFragmentCompat() {
    private val configHelper: ConfigurationHelper by lazy {
        ConfigurationHelper(requireContext())
    }

    private val resolutionListPreference: ListPreference by lazy {
        this.findPreference(getString(R.string.video_resolution_key))!!
    }

    private val endpointTypePreference: SwitchPreference by lazy {
        this.findPreference(getString(R.string.endpoint_type_key))!!
    }

    private val serverEndpointPreference: PreferenceCategory by lazy {
        this.findPreference(getString(R.string.endpoint_server_key))!!
    }

    private val fileEndpointPreference: PreferenceCategory by lazy {
        this.findPreference(getString(R.string.endpoint_file_key))!!
    }

    private val serverIpPreference: EditTextPreference by lazy {
        this.findPreference(getString(R.string.server_ip_key))!!
    }

    private val serverPortPreference: EditTextPreference by lazy {
        this.findPreference(getString(R.string.server_port_key))!!
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
    }

    private fun loadPreferences() {
        configHelper.resolutionEntries.map { it.toString() }.toTypedArray().run {
            resolutionListPreference.entries = this
            resolutionListPreference.entryValues = this
        }

        serverEndpointPreference.isVisible = endpointTypePreference.isChecked
        fileEndpointPreference.isVisible = !endpointTypePreference.isChecked
        endpointTypePreference.setOnPreferenceChangeListener { _, newValue ->
            serverEndpointPreference.isVisible = newValue as Boolean
            fileEndpointPreference.isVisible = !newValue
            true
        }

        serverIpPreference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        serverPortPreference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.filters = arrayOf(InputFilter.LengthFilter(5))
        }
    }
}