/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.openscale.core.data.User
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.healthconnect.HealthConnectManager
import com.health.openscale.core.healthconnect.HealthConnectResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HealthConnectViewModel @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val settingsFacade: SettingsFacade,
    private val databaseRepository: DatabaseRepository
) : ViewModel() {

    val syncEnabled: StateFlow<Boolean> = settingsFacade.healthConnectSyncEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val syncDirection: StateFlow<String> = settingsFacade.healthConnectSyncDirection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "BOTH")

    val selectedUserId: StateFlow<Int> = settingsFacade.healthConnectUserId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1)

    val lastSyncTime: StateFlow<Long> = settingsFacade.healthConnectLastSyncTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val allUsers: StateFlow<List<User>> = databaseRepository.getAllUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isAvailable = MutableStateFlow(healthConnectManager.isAvailable())
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _allPermissionsGranted = MutableStateFlow(false)
    val allPermissionsGranted: StateFlow<Boolean> = _allPermissionsGranted.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    init {
        checkPermissions()
    }

    fun checkPermissions() {
        viewModelScope.launch {
            _isAvailable.value = healthConnectManager.isAvailable()
            if (_isAvailable.value) {
                _allPermissionsGranted.value = healthConnectManager.hasAllPermissions()
            } else {
                _allPermissionsGranted.value = false
            }
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsFacade.setHealthConnectSyncEnabled(enabled)
            checkPermissions()
        }
    }

    fun setSyncDirection(direction: String) {
        viewModelScope.launch {
            settingsFacade.setHealthConnectSyncDirection(direction)
            checkPermissions()
        }
    }

    fun setSelectedUserId(userId: Int) {
        viewModelScope.launch {
            settingsFacade.setHealthConnectUserId(userId)
        }
    }

    suspend fun getNeededPermissions(): Set<String> {
        return healthConnectManager.neededPermissions()
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = null
            val targetUserId = healthConnectManager.getEffectiveUserId()
            val result = healthConnectManager.syncNow(targetUserId)
            when (result) {
                is HealthConnectResult.Success -> {
                    _syncMessage.value = result.data
                }
                is HealthConnectResult.Failure -> {
                    _syncMessage.value = result.message
                }
            }
            _isSyncing.value = false
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }
}
