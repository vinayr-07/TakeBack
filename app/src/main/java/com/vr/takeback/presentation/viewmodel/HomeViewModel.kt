package com.vr.takeback.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vr.takeback.TakeBackApplication
import com.vr.takeback.data.repository.*
import com.vr.takeback.domain.engine.CentralRestrictionEngine
import com.vr.takeback.domain.model.PermissionStatus
import com.vr.takeback.domain.model.Profile
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Immutable UI State representing the complete state of the Home Screen.
 */
data class HomeUiState(
    val profiles: List<Profile> = emptyList(),
    val quickBlockState: QuickBlockState = QuickBlockState(),
    val permissionStatus: PermissionStatus = PermissionStatus(),
    val strictState: StrictProtectionState = StrictProtectionState(),
    val usageSummary: DailyUsageSummary? = null,
    val isUsageLoading: Boolean = false,
    val dailyAllowanceMinutes: Int = 120,
    val pendingToggleProfileId: String? = null,
    val isPendingCancelQuickBlock: Boolean = false,
    val showAllowanceDialog: Boolean = false,
    val evaluationTime: LocalTime = LocalTime.now(),
    val evaluationDay: DayOfWeek = LocalDate.now().dayOfWeek
) {
    val activelyEnforcingProfiles: List<Profile>
        get() {
            val engine = CentralRestrictionEngine()
            return profiles.filter { profile ->
                profile.isEnabled && engine.isProfileCurrentlyEnforcing(profile, evaluationTime, evaluationDay)
            }
        }

    val isAccessibilityMissing: Boolean get() = !permissionStatus.hasAccessibility
    val isUsageStatsMissing: Boolean get() = !permissionStatus.hasUsageStats
    val isQuickBlockActive: Boolean get() = quickBlockState.isActive
    val hasEnabledProfile: Boolean get() = profiles.any { it.isEnabled }
    val isProtectionConfigured: Boolean get() = isQuickBlockActive || activelyEnforcingProfiles.isNotEmpty() || hasEnabledProfile
    val isProtectionActive: Boolean get() = isQuickBlockActive || activelyEnforcingProfiles.isNotEmpty()
}

/**
 * User actions and events originating from the Home Screen UI.
 */
sealed interface HomeUiEvent {
    object Refresh : HomeUiEvent
    data class StartQuickBlock(val minutes: Int) : HomeUiEvent
    object CancelQuickBlock : HomeUiEvent
    data class ConfirmCancelQuickBlockWithToken(val token: String) : HomeUiEvent
    object DismissCancelQuickBlockChallenge : HomeUiEvent
    data class SetDailyAllowance(val minutes: Int) : HomeUiEvent
    data class RequestToggleProfile(val profileId: String) : HomeUiEvent
    data class ConfirmToggleProfileWithToken(val profileId: String, val token: String) : HomeUiEvent
    object DismissToggleChallenge : HomeUiEvent
    data class SetAllowanceDialogVisible(val visible: Boolean) : HomeUiEvent
}

/**
 * Modern Android Architecture ViewModel for HomeScreen.
 *
 * Encapsulates all state coordination, asynchronous data queries,
 * and user interactions, exposing a single reactive StateFlow<HomeUiState>.
 */
class HomeViewModel(
    private val profileRepository: ProfileRepository,
    private val quickBlockRepository: QuickBlockRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val permissionRepository: PermissionRepository,
    private val strictModeRepository: StrictModeRepository
) : ViewModel() {

    private val _usageSummary = MutableStateFlow<DailyUsageSummary?>(null)
    private val _isUsageLoading = MutableStateFlow(false)
    private val _dailyAllowanceMinutes = MutableStateFlow(usageStatsRepository.getDailyAllowanceMinutes())
    private val _pendingToggleProfileId = MutableStateFlow<String?>(null)
    private val _isPendingCancelQuickBlock = MutableStateFlow(false)
    private val _showAllowanceDialog = MutableStateFlow(false)
    private val _evaluationTime = MutableStateFlow(LocalTime.now())
    private val _evaluationDay = MutableStateFlow(LocalDate.now().dayOfWeek)

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            profileRepository.profiles,
            quickBlockRepository.state,
            permissionRepository.permissionStatus,
            strictModeRepository.protectionState
        ) { profiles, quickBlock, perms, strict ->
            Tuple4(profiles, quickBlock, perms, strict)
        },
        combine(
            _usageSummary,
            _isUsageLoading,
            _dailyAllowanceMinutes,
            _pendingToggleProfileId,
            _showAllowanceDialog
        ) { summary, loading, allowance, pendingToggle, showDialog ->
            Tuple5(summary, loading, allowance, pendingToggle, showDialog)
        },
        combine(
            _evaluationTime,
            _evaluationDay,
            _isPendingCancelQuickBlock
        ) { time, day, pendingCancel ->
            Triple(time, day, pendingCancel)
        }
    ) { (profiles, quickBlock, perms, strict), (summary, loading, allowance, pendingToggle, showDialog), (time, day, pendingCancel) ->
        HomeUiState(
            profiles = profiles,
            quickBlockState = quickBlock,
            permissionStatus = perms,
            strictState = strict,
            usageSummary = summary,
            isUsageLoading = loading,
            dailyAllowanceMinutes = allowance,
            pendingToggleProfileId = pendingToggle,
            isPendingCancelQuickBlock = pendingCancel,
            showAllowanceDialog = showDialog,
            evaluationTime = time,
            evaluationDay = day
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    init {
        refreshAll()
    }

    fun onEvent(event: HomeUiEvent) {
        when (event) {
            is HomeUiEvent.Refresh -> refreshAll()
            is HomeUiEvent.StartQuickBlock -> quickBlockRepository.startQuickBlock(event.minutes)
            is HomeUiEvent.CancelQuickBlock -> {
                val isStrictLocked = strictModeRepository.protectionState.value.isCurrentlyLocked
                if (isStrictLocked) {
                    _isPendingCancelQuickBlock.value = true
                } else {
                    quickBlockRepository.cancelQuickBlock()
                }
            }
            is HomeUiEvent.ConfirmCancelQuickBlockWithToken -> {
                quickBlockRepository.cancelQuickBlock(token = event.token)
                _isPendingCancelQuickBlock.value = false
            }
            is HomeUiEvent.DismissCancelQuickBlockChallenge -> {
                _isPendingCancelQuickBlock.value = false
            }
            is HomeUiEvent.SetDailyAllowance -> {
                usageStatsRepository.setDailyAllowanceMinutes(event.minutes)
                _dailyAllowanceMinutes.value = event.minutes
                _showAllowanceDialog.value = false
            }
            is HomeUiEvent.RequestToggleProfile -> {
                val isStrictLocked = strictModeRepository.protectionState.value.isCurrentlyLocked
                if (isStrictLocked) {
                    _pendingToggleProfileId.value = event.profileId
                } else {
                    profileRepository.toggleProfile(event.profileId)
                }
            }
            is HomeUiEvent.ConfirmToggleProfileWithToken -> {
                profileRepository.toggleProfile(event.profileId, token = event.token)
                _pendingToggleProfileId.value = null
            }
            is HomeUiEvent.DismissToggleChallenge -> {
                _pendingToggleProfileId.value = null
            }
            is HomeUiEvent.SetAllowanceDialogVisible -> {
                _showAllowanceDialog.value = event.visible
            }
        }
    }

    fun refreshAll() {
        permissionRepository.refreshPermissions()
        _dailyAllowanceMinutes.value = usageStatsRepository.getDailyAllowanceMinutes()
        _evaluationTime.value = LocalTime.now()
        _evaluationDay.value = LocalDate.now().dayOfWeek

        viewModelScope.launch {
            _isUsageLoading.value = true
            try {
                _usageSummary.value = usageStatsRepository.getTodayUsageSummary()
            } catch (e: Exception) {
                // Defensive fallback
            } finally {
                _isUsageLoading.value = false
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(
                    profileRepository = TakeBackApplication.profileRepository,
                    quickBlockRepository = TakeBackApplication.quickBlockRepository,
                    usageStatsRepository = TakeBackApplication.usageStatsRepository,
                    permissionRepository = TakeBackApplication.permissionRepository,
                    strictModeRepository = TakeBackApplication.strictModeRepository
                ) as T
            }
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
}
