package com.gpm.virtual.viewmodel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gpm.virtual.data.KnownPackages
import com.gpm.virtual.data.db.AppDatabase
import com.gpm.virtual.data.model.FavoriteLocation
import com.gpm.virtual.data.model.NearbyPlace
import com.gpm.virtual.data.preferences.GpsConfig
import com.gpm.virtual.data.preferences.GpsPreferences
import com.gpm.virtual.data.repository.LocationRepository
import com.gpm.virtual.data.repository.NearbyRepository
import com.gpm.virtual.manager.GpsSimulationManager
import com.gpm.virtual.manager.NetworkManager
import com.gpm.virtual.manager.SpoofManager
import com.gpm.virtual.service.MockLocationService
import com.gpm.virtual.util.XposedStatus
import com.gpm.virtual.xposed.LitmusObserver
import com.gpm.virtual.xposed.RealisticGpsEngine
import com.gpm.virtual.xposed.SpoofConsistencyGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class SearchResultItem(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ==================================================================
    // DEPENDENCIES
    // ==================================================================
    private val repository: LocationRepository
    private val nearbyRepository: NearbyRepository
    private val spoofManager: SpoofManager
    private val gpsSimulationManager: GpsSimulationManager
    private val networkManager: NetworkManager

    // ==================================================================
    // STATE FLOWS
    // ==================================================================
    val config: StateFlow<GpsConfig>
    val favorites: StateFlow<List<FavoriteLocation>>

    private val _activeCoordinates = MutableStateFlow(Pair(-6.1683295, 106.7588494))
    val activeCoordinates: StateFlow<Pair<Double, Double>> = _activeCoordinates.asStateFlow()

    private val _isLsposedActive = MutableStateFlow(false)
    val isLsposedActive: StateFlow<Boolean> = _isLsposedActive.asStateFlow()

    private val _lsposedStatusDetail = MutableStateFlow("")
    val lsposedStatusDetail: StateFlow<String> = _lsposedStatusDetail.asStateFlow()

    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()

    private val _hasBackgroundPermission = MutableStateFlow(false)
    val hasBackgroundPermission: StateFlow<Boolean> = _hasBackgroundPermission.asStateFlow()

    private val _isSyncingGps = MutableStateFlow(false)
    val isSyncingGps: StateFlow<Boolean> = _isSyncingGps.asStateFlow()

    private val _realDeviceLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val realDeviceLocation: StateFlow<Pair<Double, Double>?> = _realDeviceLocation.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _nearbyPlaces = MutableStateFlow<List<NearbyPlace>>(emptyList())
    val nearbyPlaces: StateFlow<List<NearbyPlace>> = _nearbyPlaces.asStateFlow()

    private val _isNearbyLoading = MutableStateFlow(false)
    val isNearbyLoading: StateFlow<Boolean> = _isNearbyLoading.asStateFlow()

    private val _selectedNearbyCategory = MutableStateFlow("Pickup Point")
    val selectedNearbyCategory: StateFlow<String> = _selectedNearbyCategory.asStateFlow()

    private val _statusSnackbar = MutableStateFlow<String?>(null)
    val statusSnackbar: StateFlow<String?> = _statusSnackbar.asStateFlow()

    private val geocoder: Geocoder = Geocoder(application, Locale.getDefault())
    private var nearbyJob: Job? = null

    private val injectionPrefs: SharedPreferences =
        application.getSharedPreferences("gpm_injection_prefs", Context.MODE_PRIVATE)

    private val _injectionScope = MutableStateFlow("ALL")
    val injectionScope: StateFlow<String> = _injectionScope.asStateFlow()

    private val _gojekTarget = MutableStateFlow<Pair<Double, Double>?>(null)
    val gojekTarget: StateFlow<Pair<Double, Double>?> = _gojekTarget.asStateFlow()

    private val _grabTarget = MutableStateFlow<Pair<Double, Double>?>(null)
    val grabTarget: StateFlow<Pair<Double, Double>?> = _grabTarget.asStateFlow()

    private val _gojekDraftTarget = MutableStateFlow<Pair<Double, Double>?>(null)
    val gojekDraftTarget: StateFlow<Pair<Double, Double>?> = _gojekDraftTarget.asStateFlow()

    private val _grabDraftTarget = MutableStateFlow<Pair<Double, Double>?>(null)
    val grabDraftTarget: StateFlow<Pair<Double, Double>?> = _grabDraftTarget.asStateFlow()

    val hasPendingDraft: StateFlow<Boolean> = combine(
        _injectionScope, _gojekDraftTarget, _grabDraftTarget
    ) { scope, gDraft, rDraft ->
        when (scope) {
            "GOJEK" -> gDraft != null
            "GRAB"  -> rDraft != null
            else    -> gDraft != null || rDraft != null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _activeEditTarget = MutableStateFlow("gojek")
    val activeEditTarget: StateFlow<String> = _activeEditTarget.asStateFlow()

    private val _gojekSpoofActive = MutableStateFlow(false)
    val gojekSpoofActive: StateFlow<Boolean> = _gojekSpoofActive.asStateFlow()

    private val _grabSpoofActive = MutableStateFlow(false)
    val grabSpoofActive: StateFlow<Boolean> = _grabSpoofActive.asStateFlow()

    private var injectionPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var lastSpoofToggleMs: Long = 0L
    private val spoofToggleCooldownMs = 1_000L

    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MockLocationService.ACTION_LOCATION_UPDATED) {
                val lat = intent.getDoubleExtra("latitude", 0.0)
                val lng = intent.getDoubleExtra("longitude", 0.0)
                if (lat != 0.0 && lng != 0.0) {
                    _activeCoordinates.value = Pair(lat, lng)
                }
            }
        }
    }

    init {
        val db = AppDatabase.getDatabase(application)
        val prefs = (application as? com.gpm.virtual.GpmApplication)?.preferences
            ?: GpsPreferences.getInstance(application)
        repository = LocationRepository(db.favoriteDao(), prefs)
        nearbyRepository = NearbyRepository(application)

        spoofManager = SpoofManager(
            context = application,
            injectionPrefs = injectionPrefs,
            repository = repository,
            onStatusMessage = { _statusSnackbar.value = it }
        )
        gpsSimulationManager = GpsSimulationManager(
            context = application,
            injectionPrefs = injectionPrefs,
            repository = repository,
            onActiveCoordinatesChanged = { _activeCoordinates.value = it },
            onStatusMessage = { _statusSnackbar.value = it }
        )
        networkManager = NetworkManager(
            context = application,
            repository = repository,
            onStatusMessage = { _statusSnackbar.value = it }
        )

        config = repository.configFlow
        favorites = repository.allFavorites.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        _injectionScope.value = spoofManager.getInjectionScope()
        _gojekTarget.value = spoofManager.getGojekTarget()
        _grabTarget.value = spoofManager.getGrabTarget()
        _gojekSpoofActive.value = spoofManager.isGojekSpoofActive()
        _grabSpoofActive.value = spoofManager.isGrabSpoofActive()

        registerInjectionPrefsListener()
        syncActiveEditTargetWithScope()

        checkPermissions()
        checkLsposedStatus()
        fetchNearbyPlaces()

        _activeCoordinates.value = Pair(
            prefs.getConfig().latitude,
            prefs.getConfig().longitude
        )

        // ⭐ [FIX-KOMBINASI] Loop warm-up VT engine.
        // Jalan terus selama SALAH SATU toggle ON (Play / VT / Random).
        // - Play ON  → output di-update ke _activeCoordinates (UI marker)
        // - Play OFF → engine tetap warm (baseline real GPS), output tidak dipakai
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    val cfg = config.value
                    val anyToggleOn = cfg.useVirtual || cfg.useVirtualRacing ||
                            cfg.useRandom || cfg.isSpoofActive

                    if (anyToggleOn) {
                        // Warm up engine — center = real GPS saat Play OFF
                        val realGps = _realDeviceLocation.value
                        val vtCoords = gpsSimulationManager.updateVtRacing(cfg, realGps)

                        // HANYA update UI marker saat Play ON
                        if (cfg.isSpoofActive) {
                            if (vtCoords != null) {
                                _activeCoordinates.value = vtCoords
                            } else {
                                val randomCoords = gpsSimulationManager.updateRandomMovement(cfg)
                                if (randomCoords != null) {
                                    _activeCoordinates.value = randomCoords
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                delay(30L)
            }
        }

        try {
            val filter = IntentFilter(MockLocationService.ACTION_LOCATION_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(
                    locationUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                application.registerReceiver(locationUpdateReceiver, filter)
            }
        } catch (_: Throwable) {}

        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            while (isActive) {
                _isLsposedActive.value = XposedStatus.isModuleActive(app)
                _lsposedStatusDetail.value = XposedStatus.getStatusDetail(app)
                delay(8000)
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            try { LitmusObserver.invalidate() } catch (_: Throwable) {}
        }

        viewModelScope.launch {
            delay(1500)
            if (_gojekSpoofActive.value || _grabSpoofActive.value) {
                spoofManager.restoreSpoofStateToTargets(config.value)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  UI SETTERS
    // ══════════════════════════════════════════════════════════════════

    fun setThemeMode(themeMode: String) {
        repository.updateConfig { it.copy(themeMode = themeMode) }
    }

    fun setMapType(mapType: String) {
        repository.updateConfig { it.copy(mapType = mapType) }
    }

    fun setJoystick(enabled: Boolean) {
        repository.updateConfig { it.copy(isJoystickEnabled = enabled) }
    }

    fun setFloatingOverlay(enabled: Boolean) {
        repository.updateConfig { it.copy(isFloatingOverlayEnabled = enabled) }
    }

    fun setWalkSpeed(speed: Float) {
        repository.updateConfig { it.copy(walkSpeedMps = speed) }
    }

    fun setCustomBearingEnabled(enabled: Boolean) {
        repository.updateConfig { it.copy(isCustomBearingEnabled = enabled) }
        injectionPrefs.edit().putBoolean("is_custom_bearing_enabled", enabled).apply()
        if (config.value.isSpoofActive) {
            MockLocationService.pushImmediate(getApplication())
        }
    }

    fun setBearing(bearing: Float) {
        val normalized = ((bearing % 360f) + 360f) % 360f
        repository.updateConfig { it.copy(bearing = normalized) }
        injectionPrefs.edit().putFloat("bearing", normalized).apply()
        if (config.value.isSpoofActive) {
            MockLocationService.pushImmediate(getApplication())
        }
    }

    fun setLocationSharing(enabled: Boolean) {
        repository.updateConfig { it.copy(isLocationSharingEnabled = enabled) }
        injectionPrefs.edit().putBoolean("is_location_sharing_enabled", enabled).apply()
        if (config.value.isSpoofActive) {
            MockLocationService.pushImmediate(getApplication())
        }
        showMessage(
            if (enabled) "Sinkronisasi Berbagi Lokasi Aktif (Firebase & Target)"
            else "Berbagi Lokasi Dinonaktifkan"
        )
    }

    fun setAccuracy(accuracy: Float) {
        repository.updateConfig { it.copy(accuracy = accuracy) }
        spoofManager.notifyTargetsConfigChanged()
    }

    fun setRandomLocation(enabled: Boolean) {
        repository.updateConfig { it.copy(isRandomLocationEnabled = enabled) }
        spoofManager.notifyTargetsConfigChanged()
    }

    fun setRandomRadius(radius: Float) {
        repository.updateConfig { it.copy(randomRadiusMeters = radius) }
    }

    fun setRandomAccuracy(enabled: Boolean) {
        repository.updateConfig { it.copy(isRandomAccuracyEnabled = enabled) }
        spoofManager.notifyTargetsConfigChanged()
    }

    fun setAntiMock(enabled: Boolean) {
        repository.updateConfig { it.copy(isAntiMockEnabled = enabled) }
        spoofManager.notifyTargetsConfigChanged()
    }

    // ══════════════════════════════════════════════════════════════════
    //  NETWORK
    // ══════════════════════════════════════════════════════════════════

    fun setConnectionStability(enabled: Boolean) =
        networkManager.setConnectionStability(enabled)

    fun setGcmSupport(enabled: Boolean) =
        networkManager.setGcmSupport(enabled)

    fun setOkHttpStability(enabled: Boolean) =
        networkManager.setOkHttpStability(enabled)

    fun setFastTcp(enabled: Boolean) =
        networkManager.setFastTcp(enabled)

    fun setFastestCaptureMode(enabled: Boolean) =
        networkManager.setFastestCaptureMode(enabled)

    // ══════════════════════════════════════════════════════════════════
    //  SPOOF
    // ══════════════════════════════════════════════════════════════════

    fun setInjectionScope(scope: String) {
        spoofManager.setInjectionScope(scope)
        _injectionScope.value = spoofManager.getInjectionScope()
        syncActiveEditTargetWithScope()
    }

    fun setGojekTarget(lat: Double?, lng: Double?) {
        spoofManager.setGojekTarget(lat, lng)
        _gojekTarget.value = spoofManager.getGojekTarget()
    }

    fun setGrabTarget(lat: Double?, lng: Double?) {
        spoofManager.setGrabTarget(lat, lng)
        _grabTarget.value = spoofManager.getGrabTarget()
    }

    fun toggleGojekSpoof() {
        val now = System.currentTimeMillis()
        if (now - lastSpoofToggleMs < spoofToggleCooldownMs) {
            val remain = (spoofToggleCooldownMs - (now - lastSpoofToggleMs)) / 1000.0
            showMessage("Tunggu %.1fs lagi...".format(remain))
            return
        }
        lastSpoofToggleMs = now

        val realCoord = _realDeviceLocation.value
        val wasActive = _gojekSpoofActive.value

        spoofManager.toggleGojekSpoof(config.value)
        _gojekSpoofActive.value = spoofManager.isGojekSpoofActive()
        _grabSpoofActive.value = spoofManager.isGrabSpoofActive()
        refreshPerAppTargetsFromPrefs()

        if (!wasActive && _gojekSpoofActive.value && realCoord != null) {
            val targetCoord = _gojekTarget.value
                ?: (config.value.latitude to config.value.longitude)
            showTransitionFeedback("Gojek", realCoord, targetCoord)
        } else if (wasActive && !_gojekSpoofActive.value) {
            showMessage("Spoof Gojek dimatikan")
        }
    }

    fun toggleGrabSpoof() {
        val now = System.currentTimeMillis()
        if (now - lastSpoofToggleMs < spoofToggleCooldownMs) {
            val remain = (spoofToggleCooldownMs - (now - lastSpoofToggleMs)) / 1000.0
            showMessage("Tunggu %.1fs lagi...".format(remain))
            return
        }
        lastSpoofToggleMs = now

        val realCoord = _realDeviceLocation.value
        val wasActive = _grabSpoofActive.value

        spoofManager.toggleGrabSpoof(config.value)
        _gojekSpoofActive.value = spoofManager.isGojekSpoofActive()
        _grabSpoofActive.value = spoofManager.isGrabSpoofActive()
        refreshPerAppTargetsFromPrefs()

        if (!wasActive && _grabSpoofActive.value && realCoord != null) {
            val targetCoord = _grabTarget.value
                ?: (config.value.latitude to config.value.longitude)
            showTransitionFeedback("Grab", realCoord, targetCoord)
        } else if (wasActive && !_grabSpoofActive.value) {
            showMessage("Spoof Grab dimatikan")
        }
    }

    fun toggleAllSpoof() {
        val now = System.currentTimeMillis()
        if (now - lastSpoofToggleMs < spoofToggleCooldownMs) {
            val remain = (spoofToggleCooldownMs - (now - lastSpoofToggleMs)) / 1000.0
            showMessage("Tunggu %.1fs lagi...".format(remain))
            return
        }
        lastSpoofToggleMs = now

        val realCoord = _realDeviceLocation.value
        val wasAnyActive = _gojekSpoofActive.value || _grabSpoofActive.value

        spoofManager.toggleAllSpoof(config.value)
        _gojekSpoofActive.value = spoofManager.isGojekSpoofActive()
        _grabSpoofActive.value = spoofManager.isGrabSpoofActive()
        refreshPerAppTargetsFromPrefs()

        val nowActive = _gojekSpoofActive.value || _grabSpoofActive.value

        if (!wasAnyActive && nowActive && realCoord != null) {
            val targets = mutableListOf<Pair<Double, Double>>()
            if (_gojekSpoofActive.value) {
                targets.add(_gojekTarget.value ?: (config.value.latitude to config.value.longitude))
            }
            if (_grabSpoofActive.value) {
                targets.add(_grabTarget.value ?: (config.value.latitude to config.value.longitude))
            }
            val farthest = targets.maxByOrNull { t ->
                SpoofConsistencyGuard.haversineMeters(
                    realCoord.first, realCoord.second, t.first, t.second
                )
            }
            if (farthest != null) {
                showTransitionFeedback("Spoof", realCoord, farthest)
            }
        } else if (wasAnyActive && !nowActive) {
            showMessage("Spoof dimatikan")
        }
    }

    private fun refreshPerAppTargetsFromPrefs() {
        _gojekTarget.value = spoofManager.getGojekTarget()
        _grabTarget.value = spoofManager.getGrabTarget()
    }

    private fun showTransitionFeedback(
        label: String,
        fromCoord: Pair<Double, Double>,
        toCoord: Pair<Double, Double>
    ) {
        val distM = SpoofConsistencyGuard.haversineMeters(
            fromCoord.first, fromCoord.second,
            toCoord.first, toCoord.second
        )
        val tier = SpoofConsistencyGuard.classifyDistance(distM)
        val durationMs = SpoofConsistencyGuard.estimateDurationMs(distM)
        val warning = SpoofConsistencyGuard.getTierWarning(tier)

        val msg = when (tier) {
            SpoofConsistencyGuard.DistanceTier.INSTANT ->
                "$label aktif"
            SpoofConsistencyGuard.DistanceTier.SHORT ->
                "Transisi $label: %.0fm dalam %.1fs".format(distM, durationMs / 1000.0)
            SpoofConsistencyGuard.DistanceTier.MEDIUM ->
                "Transisi cepat $label: %.1fkm dalam %.1fs".format(
                    distM / 1000.0, durationMs / 1000.0
                )
            SpoofConsistencyGuard.DistanceTier.LONG ->
                "⚠️ $label jarak jauh: %.1fkm, transisi %.1fs".format(
                    distM / 1000.0, durationMs / 1000.0
                )
            SpoofConsistencyGuard.DistanceTier.EXTREME ->
                "⚠️⚠️ $label jarak sangat jauh: %.1fkm".format(distM / 1000.0)
        }

        _statusSnackbar.value = if (warning != null) "$msg\n$warning" else msg
    }

    fun injectGojekHooks() {
        val coord = _gojekTarget.value ?: (config.value.latitude to config.value.longitude)
        spoofManager.injectGojekHooks(coord)
    }

    fun injectGrabHooks() {
        val coord = _grabTarget.value ?: (config.value.latitude to config.value.longitude)
        spoofManager.injectGrabHooks(coord)
    }

    fun injectDualTargets() {
        val globalLat = config.value.latitude
        val globalLng = config.value.longitude
        val gojekCoord = _gojekTarget.value ?: (globalLat to globalLng)
        val grabCoord = _grabTarget.value ?: (globalLat to globalLng)
        spoofManager.injectGojekHooks(gojekCoord)
        spoofManager.injectGrabHooks(grabCoord)
    }

    fun restoreSpoofStateToTargets() {
        spoofManager.restoreSpoofStateToTargets(config.value)
    }

    fun clearPerAppOverrides() {
        spoofManager.clearPerAppOverrides()
        _gojekTarget.value = null
        _grabTarget.value = null
    }

    fun setDraftTarget(lat: Double, lng: Double, name: String? = null) {
        val safeLat = RealisticGpsEngine.normalizeLat(lat)
        val safeLng = RealisticGpsEngine.normalizeLng(lng)

        when (_injectionScope.value) {
            "GOJEK" -> _gojekDraftTarget.value = safeLat to safeLng
            "GRAB"  -> _grabDraftTarget.value = safeLat to safeLng
            else -> when (_activeEditTarget.value) {
                "gojek" -> _gojekDraftTarget.value = safeLat to safeLng
                else    -> _grabDraftTarget.value = safeLat to safeLng
            }
        }

        _activeCoordinates.value = safeLat to safeLng
        if (name != null) {
            repository.updateConfig { it.copy(locationName = name) }
        }
        _statusSnackbar.value = "Draft: %.5f, %.5f — tekan Terapkan".format(safeLat, safeLng)
    }

    fun applyDraftForCurrentScope() {
        when (_injectionScope.value) {
            "GOJEK" -> {
                val draft = _gojekDraftTarget.value
                if (draft == null) {
                    _statusSnackbar.value = "Tidak ada draft Gojek"
                    return
                }
                setGojekTarget(draft.first, draft.second)
                _gojekDraftTarget.value = null
                repository.updateConfig { it.copy(latitude = draft.first, longitude = draft.second) }
                _activeCoordinates.value = draft
                gpsSimulationManager.setCenterCoordinates(draft.first, draft.second)

                if (_gojekSpoofActive.value) {
                    spoofManager.sendSpoofBroadcast(
                        pkg = KnownPackages.GOJEK_PARTNER,
                        active = true,
                        action = "com.gpm.virtual.ACTION_SPOOF_GOJEK",
                        lat = draft.first, lng = draft.second
                    )
                    MockLocationService.pushImmediate(getApplication())
                    MockLocationService.pingLocationToScope(getApplication())
                }
                _statusSnackbar.value = "Lokasi Gojek diterapkan: %.5f, %.5f".format(draft.first, draft.second)
            }
            "GRAB" -> {
                val draft = _grabDraftTarget.value
                if (draft == null) {
                    _statusSnackbar.value = "Tidak ada draft Grab"
                    return
                }
                setGrabTarget(draft.first, draft.second)
                _grabDraftTarget.value = null
                repository.updateConfig { it.copy(latitude = draft.first, longitude = draft.second) }
                _activeCoordinates.value = draft
                gpsSimulationManager.setCenterCoordinates(draft.first, draft.second)

                if (_grabSpoofActive.value) {
                    for (pkg in listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)) {
                        spoofManager.sendSpoofBroadcast(
                            pkg = pkg, active = true,
                            action = "com.gpm.virtual.ACTION_SPOOF_GRAB",
                            lat = draft.first, lng = draft.second
                        )
                    }
                    MockLocationService.pushImmediate(getApplication())
                    MockLocationService.pingLocationToScope(getApplication())
                }
                _statusSnackbar.value = "Lokasi Grab diterapkan: %.5f, %.5f".format(draft.first, draft.second)
            }
            else -> {
                val gDraft = _gojekDraftTarget.value
                val rDraft = _grabDraftTarget.value
                if (gDraft == null && rDraft == null) {
                    _statusSnackbar.value = "Tidak ada draft"
                    return
                }
                val applied = mutableListOf<String>()

                if (gDraft != null) {
                    setGojekTarget(gDraft.first, gDraft.second)
                    _gojekDraftTarget.value = null
                    applied.add("Gojek %.5f,%.5f".format(gDraft.first, gDraft.second))
                    if (_gojekSpoofActive.value) {
                        spoofManager.sendSpoofBroadcast(
                            pkg = KnownPackages.GOJEK_PARTNER, active = true,
                            action = "com.gpm.virtual.ACTION_SPOOF_GOJEK",
                            lat = gDraft.first, lng = gDraft.second
                        )
                    }
                }
                if (rDraft != null) {
                    setGrabTarget(rDraft.first, rDraft.second)
                    _grabDraftTarget.value = null
                    applied.add("Grab %.5f,%.5f".format(rDraft.first, rDraft.second))
                    if (_grabSpoofActive.value) {
                        for (pkg in listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)) {
                            spoofManager.sendSpoofBroadcast(
                                pkg = pkg, active = true,
                                action = "com.gpm.virtual.ACTION_SPOOF_GRAB",
                                lat = rDraft.first, lng = rDraft.second
                            )
                        }
                    }
                }
                val finalCoord = gDraft ?: rDraft
                if (finalCoord != null) {
                    repository.updateConfig { it.copy(latitude = finalCoord.first, longitude = finalCoord.second) }
                    _activeCoordinates.value = finalCoord
                    gpsSimulationManager.setCenterCoordinates(finalCoord.first, finalCoord.second)
                    MockLocationService.pushImmediate(getApplication())
                    MockLocationService.pingLocationToScope(getApplication())
                }
                _statusSnackbar.value = "Diterapkan: ${applied.joinToString(" | ")}"
            }
        }
    }

    fun discardDraftForCurrentScope() {
        when (_injectionScope.value) {
            "GOJEK" -> {
                _gojekDraftTarget.value = null
                _gojekTarget.value?.let { _activeCoordinates.value = it }
                _statusSnackbar.value = "Draft Gojek dibatalkan"
            }
            "GRAB" -> {
                _grabDraftTarget.value = null
                _grabTarget.value?.let { _activeCoordinates.value = it }
                _statusSnackbar.value = "Draft Grab dibatalkan"
            }
            else -> {
                when (_activeEditTarget.value) {
                    "gojek" -> {
                        _gojekDraftTarget.value = null
                        _gojekTarget.value?.let { _activeCoordinates.value = it }
                        _statusSnackbar.value = "Draft Gojek dibatalkan"
                    }
                    else -> {
                        _grabDraftTarget.value = null
                        _grabTarget.value?.let { _activeCoordinates.value = it }
                        _statusSnackbar.value = "Draft Grab dibatalkan"
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  GPS SIMULATION
    // ══════════════════════════════════════════════════════════════════

    fun moveLocation(deltaLatMeters: Double, deltaLngMeters: Double) {
        val newCoords = gpsSimulationManager.moveLocation(
            deltaLatMeters, deltaLngMeters, config.value
        )
        _activeCoordinates.value = newCoords
        refreshPerAppTargetsFromPrefs()

        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            delay(400)
            loadNearbyPlacesForCoords(newCoords.first, newCoords.second, _selectedNearbyCategory.value)
        }
    }

    fun updateTargetLocation(latitude: Double, longitude: Double, name: String? = null) {
        val coords = gpsSimulationManager.updateTargetLocation(
            latitude, longitude, name, config.value
        )
        _activeCoordinates.value = coords
        _gojekDraftTarget.value = null
        _grabDraftTarget.value = null
        refreshPerAppTargetsFromPrefs()

        val coordDisplay = "${RealisticGpsEngine.formatGoogleMapsCoord(coords.first)}, " +
                "${RealisticGpsEngine.formatGoogleMapsCoord(coords.second)}"

        val anySpoof = _gojekSpoofActive.value || _grabSpoofActive.value
        _statusSnackbar.value = if (anySpoof) {
            "Lokasi spoof diperbarui: $coordDisplay"
        } else {
            "Marker menandai lokasi: $coordDisplay"
        }

        viewModelScope.launch {
            if (name == null && _injectionScope.value == "ALL") {
                val resolvedName = resolveAddressName(coords.first, coords.second)
                repository.updateConfig {
                    if (it.latitude == coords.first && it.longitude == coords.second) {
                        it.copy(locationName = resolvedName)
                    } else it
                }
            }
            loadNearbyPlacesForCoords(coords.first, coords.second, _selectedNearbyCategory.value)
        }
    }

    fun setCenterCoordinates(latStr: String, lngStr: String) {
        val lat = latStr.toDoubleOrNull()
        val lng = lngStr.toDoubleOrNull()
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            gpsSimulationManager.setCenterCoordinates(lat, lng)
            updateTargetLocation(lat, lng)
        }
    }

    /**
     * ⭐ [FIX-KOMBINASI] VT toggle → auto-start service via GpsPreferences.
     * Service warm-up tidak butuh notif (Play only).
     */
    fun setUseVirtual(enabled: Boolean) {
        repository.updateConfig { it.copy(useVirtual = enabled) }
    }

    fun setUseVirtualRacing(enabled: Boolean) {
        repository.updateConfig { it.copy(useVirtualRacing = enabled) }
    }

    fun setUseRandom(enabled: Boolean) {
        repository.updateConfig {
            it.copy(useRandom = enabled, isRandomLocationEnabled = enabled)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  HOOK & SPOOF MASTER
    // ══════════════════════════════════════════════════════════════════

    fun toggleHook(enabled: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            repository.updateConfig {
                it.copy(
                    isHookEnabled = enabled,
                    isSpoofActive = if (!enabled) false else it.isSpoofActive
                )
            }
            if (enabled) {
                MockLocationService.pingLocationToScope(app)
                showMessage("Hook sistem diaktifkan")
            } else {
                MockLocationService.stop(app)
                MockLocationService.pingLocationToScope(app)
                showMessage("Hook sistem dinonaktifkan")
            }
        }
    }

    fun toggleSpoof(active: Boolean) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            val current = repository.getConfig()
            gpsSimulationManager.setCenterCoordinates(current.latitude, current.longitude)
            repository.updateConfig { it.copy(isSpoofActive = active) }
            if (active) {
                MockLocationService.start(app)
                MockLocationService.pingLocationToScope(app)
                MockLocationService.pushImmediate(app)
                showMessage("Spoofing diaktifkan")
            } else {
                MockLocationService.stop(app)
                MockLocationService.pingLocationToScope(app)
                showMessage("Spoofing dinonaktifkan")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  PERMISSION & GPS
    // ══════════════════════════════════════════════════════════════════

    fun checkPermissions() {
        val app = getApplication<Application>()
        val fineGranted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        _hasLocationPermission.value = fineGranted || coarseGranted
        if ((fineGranted || coarseGranted) && _realDeviceLocation.value == null) {
            syncWithDeviceGps()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            _hasBackgroundPermission.value = ContextCompat.checkSelfPermission(
                app, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            _hasBackgroundPermission.value = true
        }
    }

    fun onPermissionsResult(fineGranted: Boolean, coarseGranted: Boolean) {
        _hasLocationPermission.value = fineGranted || coarseGranted
        checkPermissions()
    }

    @SuppressLint("MissingPermission")
    fun syncWithDeviceGps(onLocationFound: ((Double, Double) -> Unit)? = null) {
        val app = getApplication<Application>()
        val fineGranted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            showMessage("Izin lokasi diperlukan")
            return
        }

        viewModelScope.launch {
            _isSyncingGps.value = true
            try {
                val fusedClient = com.google.android.gms.location.LocationServices
                    .getFusedLocationProviderClient(app)
                val cts = com.google.android.gms.tasks.CancellationTokenSource()
                fusedClient.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).addOnSuccessListener { loc ->
                    if (loc != null) {
                        val lat = loc.latitude
                        val lng = loc.longitude
                        _realDeviceLocation.value = Pair(lat, lng)
                        onLocationFound?.invoke(lat, lng)
                        showMessage("Titik biru lokasi asli terhubung (${String.format("%.4f, %.4f", lat, lng)})")
                        _isSyncingGps.value = false
                    } else {
                        performFallbackGpsSync(app, onLocationFound)
                    }
                }.addOnFailureListener {
                    performFallbackGpsSync(app, onLocationFound)
                }
            } catch (_: Throwable) {
                performFallbackGpsSync(app, onLocationFound)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun performFallbackGpsSync(
        app: Application,
        onLocationFound: ((Double, Double) -> Unit)?
    ) {
        try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER
                )
                var bestLoc: Location? = null
                for (p in providers) {
                    try {
                        val loc = lm.getLastKnownLocation(p)
                        if (loc != null) {
                            if (bestLoc == null || loc.time > bestLoc.time) bestLoc = loc
                        }
                    } catch (_: Throwable) {}
                }

                if (bestLoc != null) {
                    val lat = bestLoc.latitude
                    val lng = bestLoc.longitude
                    _realDeviceLocation.value = Pair(lat, lng)
                    onLocationFound?.invoke(lat, lng)
                    showMessage("Titik biru GPS perangkat (${String.format("%.4f, %.4f", lat, lng)})")
                } else {
                    lm.requestSingleUpdate(
                        LocationManager.GPS_PROVIDER,
                        object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                val lat = location.latitude
                                val lng = location.longitude
                                _realDeviceLocation.value = Pair(lat, lng)
                                onLocationFound?.invoke(lat, lng)
                                showMessage("Titik biru lokasi asli terkoneksi!")
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        },
                        null
                    )
                }
            }
        } catch (t: Throwable) {
            showMessage("Gagal baca GPS: ${t.localizedMessage}")
        } finally {
            _isSyncingGps.value = false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  STATUS
    // ══════════════════════════════════════════════════════════════════

    fun checkLsposedStatus() {
        val app = getApplication<Application>()
        _isLsposedActive.value = XposedStatus.isModuleActive(app)
        _lsposedStatusDetail.value = XposedStatus.getStatusDetail(app)
    }

    fun showMessage(msg: String) {
        _statusSnackbar.value = msg
    }

    fun clearSnackbar() {
        _statusSnackbar.value = null
    }

    fun pingLocationToScope() {
        val app = getApplication<Application>()
        MockLocationService.pingLocationToScope(app)
        val targetInfo =
            if (!com.gpm.virtual.data.provider.LocationConfigProvider.lastPingedPackage.isNullOrEmpty()) {
                " • Scope: ${com.gpm.virtual.data.provider.LocationConfigProvider.lastPingedPackage}"
            } else ""
        showMessage("Ping lokasi terkirim!$targetInfo")
    }

    // ══════════════════════════════════════════════════════════════════
    //  LITMUS
    // ══════════════════════════════════════════════════════════════════

    fun isKalmanActive(): Boolean = try {
        LitmusObserver.isKalmanActive()
    } catch (_: Throwable) { false }

    fun getLitmusSnapshot(): LitmusObserver.Snapshot? = try {
        LitmusObserver.get()
    } catch (_: Throwable) { null }

    fun refreshLitmusContext() {
        try { LitmusObserver.invalidate() } catch (_: Throwable) {}
    }

    // ══════════════════════════════════════════════════════════════════
    //  OVERLAY
    // ══════════════════════════════════════════════════════════════════

    fun showOverlayOnTarget(pkg: String, text: String) {
        try {
            getApplication<Application>().sendBroadcast(
                Intent("com.gpm.virtual.ACTION_SHOW_OVERLAY").apply {
                    setPackage(pkg)
                    putExtra("text", text)
                }
            )
        } catch (t: Throwable) {
            _statusSnackbar.value = "Gagal show overlay: ${t.message}"
        }
    }

    fun updateOverlayOnTarget(pkg: String, text: String) {
        try {
            getApplication<Application>().sendBroadcast(
                Intent("com.gpm.virtual.ACTION_UPDATE_OVERLAY").apply {
                    setPackage(pkg)
                    putExtra("text", text)
                }
            )
        } catch (_: Throwable) {}
    }

    fun hideOverlayOnTarget(pkg: String) {
        try {
            getApplication<Application>().sendBroadcast(
                Intent("com.gpm.virtual.ACTION_HIDE_OVERLAY").apply {
                    setPackage(pkg)
                }
            )
        } catch (_: Throwable) {}
    }

    fun refreshOverlayForTarget(target: String) {
        when (target) {
            "gojek" -> {
                val coord = _gojekTarget.value ?: (config.value.latitude to config.value.longitude)
                if (_gojekSpoofActive.value) {
                    showOverlayOnTarget(
                        KnownPackages.GOJEK_PARTNER,
                        "Spoof Gojek: %.5f, %.5f".format(coord.first, coord.second)
                    )
                } else {
                    hideOverlayOnTarget(KnownPackages.GOJEK_PARTNER)
                }
            }
            "grab" -> {
                val coord = _grabTarget.value ?: (config.value.latitude to config.value.longitude)
                if (_grabSpoofActive.value) {
                    for (pkg in listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)) {
                        showOverlayOnTarget(
                            pkg, "Spoof Grab: %.5f, %.5f".format(coord.first, coord.second)
                        )
                    }
                } else {
                    for (pkg in listOf(KnownPackages.GRAB_TAXI_DRIVER, KnownPackages.GRAB_DRIVER)) {
                        hideOverlayOnTarget(pkg)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  FAVORITES
    // ══════════════════════════════════════════════════════════════════

    fun saveCurrentFavorite(name: String) {
        viewModelScope.launch {
            val current = config.value
            val id = repository.saveFavorite(
                name = name.ifBlank { current.locationName },
                latitude = current.latitude,
                longitude = current.longitude,
                address = current.locationName
            )
            if (id > 0) showMessage("Lokasi disimpan ke Favorit")
        }
    }

    fun saveFavoriteLocation(name: String, latitude: Double, longitude: Double, address: String = "") {
        viewModelScope.launch {
            val roundedLat = RealisticGpsEngine.roundTo6Decimals(latitude)
            val roundedLng = RealisticGpsEngine.roundTo6Decimals(longitude)
            val finalName = if (name.isNotBlank()) name
            else "Koordinat (${String.format(Locale.US, "%.5f, %.5f", roundedLat, roundedLng)})"
            val id = repository.saveFavorite(
                name = finalName,
                latitude = roundedLat,
                longitude = roundedLng,
                address = address.ifBlank { "Lat: $roundedLat, Lng: $roundedLng" }
            )
            if (id > 0) showMessage("Koordinat disimpan: $finalName")
        }
    }

    fun deleteFavorite(favorite: FavoriteLocation) {
        viewModelScope.launch {
            repository.deleteFavorite(favorite)
            showMessage("Favorit dihapus: ${favorite.name}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  NEARBY / SEARCH
    // ══════════════════════════════════════════════════════════════════

    fun fetchNearbyPlaces(category: String = _selectedNearbyCategory.value) {
        _selectedNearbyCategory.value = category
        val current = config.value
        loadNearbyPlacesForCoords(current.latitude, current.longitude, category)
    }

    private fun loadNearbyPlacesForCoords(lat: Double, lng: Double, category: String) {
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            _isNearbyLoading.value = true
            try {
                val places = nearbyRepository.getNearbyPlaces(lat, lng, category)
                _nearbyPlaces.value = places
            } catch (_: Throwable) {
                _nearbyPlaces.value = emptyList()
            } finally {
                _isNearbyLoading.value = false
            }
        }
    }

    fun searchLocations(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isSearching.value = true
            val results = mutableListOf<SearchResultItem>()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocationName(query, 5) { addresses ->
                        val mapped = addresses.map { addr ->
                            SearchResultItem(
                                title = addr.featureName ?: addr.locality ?: query,
                                subtitle = addr.getAddressLine(0) ?: "${addr.latitude}, ${addr.longitude}",
                                latitude = addr.latitude,
                                longitude = addr.longitude
                            )
                        }
                        _searchResults.value = mapped
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 5)
                    addresses?.forEach { addr ->
                        results.add(
                            SearchResultItem(
                                title = addr.featureName ?: addr.locality ?: query,
                                subtitle = addr.getAddressLine(0) ?: "${addr.latitude}, ${addr.longitude}",
                                latitude = addr.latitude,
                                longitude = addr.longitude
                            )
                        )
                    }
                }
            } catch (_: Throwable) {}

            val curated = getCuratedPlaces()
            val filtered = curated.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.subtitle.contains(query, ignoreCase = true)
            }
            for (item in filtered) {
                if (results.none { it.title == item.title }) results.add(item)
            }

            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
    }

    private suspend fun resolveAddressName(lat: Double, lng: Double): String =
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses: List<Address>? = geocoder.getFromLocation(lat, lng, 1)
                val addr = addresses?.firstOrNull()
                if (addr != null) {
                    return@withContext addr.subLocality ?: addr.locality
                    ?: addr.subAdminArea ?: addr.adminArea ?: "Titik Terpilih"
                }
            } catch (_: Throwable) {}
            return@withContext String.format(Locale.US, "Lat: %.4f, Lng: %.4f", lat, lng)
        }

    private fun getCuratedPlaces(): List<SearchResultItem> = listOf(
        SearchResultItem("Jakarta Barat", "DKI Jakarta, Indonesia", -6.1683295, 106.7588494),
        SearchResultItem("Monas (Monumen Nasional)", "Gambir, Jakarta Pusat, DKI Jakarta", -6.1753924, 106.8271528),
        SearchResultItem("Gelora Bung Karno (GBK)", "Senayan, Jakarta Selatan", -6.218335, 106.802216),
        SearchResultItem("Bandara Soekarno-Hatta (CGK)", "Tangerang, Banten", -6.125556, 106.655833),
        SearchResultItem("Gedung Sate", "Bandung, Jawa Barat", -6.902484, 107.61881),
        SearchResultItem("Tugu Pahlawan", "Surabaya, Jawa Timur", -7.245842, 112.737827),
        SearchResultItem("Malioboro", "Yogyakarta, DI Yogyakarta", -7.792612, 110.365842),
        SearchResultItem("Kuta Beach", "Badung, Bali, Indonesia", -8.718493, 115.168631),
        SearchResultItem("Shibuya Crossing", "Tokyo, Japan", 35.659494, 139.700562),
        SearchResultItem("Times Square", "New York, USA", 40.758896, -73.985130),
        SearchResultItem("Eiffel Tower", "Paris, France", 48.858370, 2.294481),
        SearchResultItem("Big Ben", "London, UK", 51.500729, -0.124625)
    )

    // ══════════════════════════════════════════════════════════════════
    //  INTERNAL
    // ══════════════════════════════════════════════════════════════════

    fun setActiveEditTarget(target: String) {
        val normalized = if (target.lowercase() == "grab") "grab" else "gojek"
        if (_activeEditTarget.value == normalized) return
        _activeEditTarget.value = normalized
        val pos = when (normalized) {
            "gojek" -> _gojekDraftTarget.value ?: _gojekTarget.value
            else    -> _grabDraftTarget.value ?: _grabTarget.value
        }
        pos?.let { _activeCoordinates.value = it }
    }

    private fun syncActiveEditTargetWithScope() {
        val newTarget = when (_injectionScope.value) {
            "GRAB" -> "grab"
            "GOJEK" -> "gojek"
            else -> _activeEditTarget.value
        }
        if (_activeEditTarget.value != newTarget) {
            _activeEditTarget.value = newTarget
        }
    }

    private fun registerInjectionPrefsListener() {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "gojek_spoof_active" -> {
                    val v = injectionPrefs.getBoolean("gojek_spoof_active", false)
                    if (_gojekSpoofActive.value != v) _gojekSpoofActive.value = v
                }
                "grab_spoof_active" -> {
                    val v = injectionPrefs.getBoolean("grab_spoof_active", false)
                    if (_grabSpoofActive.value != v) _grabSpoofActive.value = v
                }
                "gojek_has_target", "gojek_lat", "gojek_lng" ->
                    _gojekTarget.value = spoofManager.getGojekTarget()
                "grab_has_target", "grab_lat", "grab_lng" ->
                    _grabTarget.value = spoofManager.getGrabTarget()
                "target_scope" -> {
                    val v = injectionPrefs.getString("target_scope", "ALL") ?: "ALL"
                    if (_injectionScope.value != v) _injectionScope.value = v
                }
            }
        }
        injectionPrefs.registerOnSharedPreferenceChangeListener(listener)
        injectionPrefsListener = listener
    }

    private fun unregisterInjectionPrefsListener() {
        try {
            injectionPrefsListener?.let {
                injectionPrefs.unregisterOnSharedPreferenceChangeListener(it)
            }
        } catch (_: Throwable) {}
        injectionPrefsListener = null
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(locationUpdateReceiver)
        } catch (_: Throwable) {}
        unregisterInjectionPrefsListener()
    }
}