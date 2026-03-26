package com.spywalker.ui

import android.app.Application
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spywalker.data.City
import com.spywalker.data.RoadCoverageChunk
import com.spywalker.data.RoadSegment
import com.spywalker.data.WalkSessionSummary
import com.spywalker.repository.OsmRepository
import com.spywalker.repository.SuggestedCityDownload
import com.spywalker.repository.WalkRepository
import com.spywalker.service.CurrentLocationSnapshot
import com.spywalker.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

data class MapUiState(
    val exploredRoads: List<RoadSegment> = emptyList(),
    val coveredRoadChunks: List<RoadCoverageChunk> = emptyList(),
    val sessionSummaries: List<WalkSessionSummary> = emptyList(),
    val isTracking: Boolean = false,
    val currentPointsCount: Int = 0,
    val currentLocation: CurrentLocationSnapshot? = null,
    val isWeakGpsSignal: Boolean = false,
    val weakGpsAccuracyMeters: Float? = null,
    val focusRequestId: Int = 0,
    val focusZoomLevel: Double = 18.0,
    val mapZoomLevel: Double = 3.0,
    val totalSessions: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val totalWalkingTimeMs: Long = 0L,
    
    // City coverage (OSM-based)
    val selectedCity: City? = null,
    val totalRoadsCount: Int = 0,
    val exploredRoadsCount: Int = 0,
    val totalRoadLengthKm: Double = 0.0,
    val exploredRoadLengthKm: Double = 0.0,
    val coveragePercentage: Float = 0f,
    val suggestedCityDownload: SuggestedCityDownload? = null,
    val isCoverageStatsExpanded: Boolean = true,
    val isZoomControlVisible: Boolean = true,
    
    // City selection
    val showCitySelection: Boolean = false,
    val showWalkSessions: Boolean = false
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val walkRepository: WalkRepository,
    private val osmRepository: OsmRepository,
    private val fusedLocationClient: FusedLocationProviderClient,
    application: Application
) : AndroidViewModel(application) {
    companion object {
        private const val MIN_MAP_ZOOM = 3.0
        private const val DEFAULT_CITY_ZOOM = 13.0
        private const val MAX_MAP_ZOOM = 20.0
        private const val NORMAL_FOCUS_ZOOM = 17.0
        private const val PASSIVE_LOCATION_INTERVAL_MS = 10_000L
        private const val PASSIVE_LOCATION_MIN_DISTANCE_METERS = 15f
        private const val MAX_AUTO_CITY_ACCURACY_METERS = 150f
        private const val CITY_SUGGESTION_MIN_REFRESH_INTERVAL_MS = 90_000L
        private const val CITY_SUGGESTION_MIN_DISTANCE_METERS = 700f
        private const val WEAK_SIGNAL_ENTER_ACCURACY_METERS = 28f
        private const val WEAK_SIGNAL_EXIT_ACCURACY_METERS = 18f
        private const val WEAK_SIGNAL_ENTER_SAMPLE_COUNT = 2
        private const val WEAK_SIGNAL_EXIT_SAMPLE_COUNT = 3
    }

    
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()
    private var cityStatsJob: Job? = null
    private var passiveLocationUpdatesStarted = false
    private var passiveLocationSnapshot: CurrentLocationSnapshot? = null
    private var trackingLocationSnapshot: CurrentLocationSnapshot? = null
    private var autoCityJob: Job? = null
    private var lastCitySuggestionLookupAt: Long = 0L
    private var lastCitySuggestionLookupLocation: CurrentLocationSnapshot? = null
    private var weakSignalSampleCount = 0
    private var recoveredSignalSampleCount = 0

    private val passiveLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                handlePassiveLocation(
                    CurrentLocationSnapshot(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy
                    )
                )
            }
        }
    }
    
    init {
        // ÐŸÐ¾Ð´Ð¿Ð¸ÑÑ‹Ð²Ð°ÐµÐ¼ÑÑ Ð½Ð° ÑÐ¾ÑÑ‚Ð¾ÑÐ½Ð¸Ðµ Ñ‚Ñ€ÐµÐºÐ¸Ð½Ð³Ð°
        viewModelScope.launch {
            LocationService.isTracking.collect { isTracking ->
                _uiState.update { it.copy(isTracking = isTracking) }
                publishCurrentLocation()
            }
        }
        
        // ÐŸÐ¾Ð´Ð¿Ð¸ÑÑ‹Ð²Ð°ÐµÐ¼ÑÑ Ð½Ð° ÑÑ‡Ñ‘Ñ‚Ñ‡Ð¸Ðº Ñ‚Ð¾Ñ‡ÐµÐº Ñ‚ÐµÐºÑƒÑ‰ÐµÐ¹ ÑÐµÑÑÐ¸Ð¸
        viewModelScope.launch {
            LocationService.pointsCount.collect { count ->
                _uiState.update { it.copy(currentPointsCount = count) }
            }
        }

        viewModelScope.launch {
            LocationService.currentLocation.collect { location ->
                trackingLocationSnapshot = location
                publishCurrentLocation()
                location?.let(::processLocationForCitySelection)
            }
        }
        
        // ÐŸÐ¾Ð´Ð¿Ð¸ÑÑ‹Ð²Ð°ÐµÐ¼ÑÑ Ð½Ð° Ð¾Ð±Ñ‰ÐµÐµ ÐºÐ¾Ð»Ð¸Ñ‡ÐµÑÑ‚Ð²Ð¾ ÑÐµÑÑÐ¸Ð¹
        viewModelScope.launch {
            walkRepository.getTotalSessionsCount().collect { count ->
                _uiState.update { it.copy(totalSessions = count) }
            }
        }

        viewModelScope.launch {
            walkRepository.getAllSessionSummaries().collect { sessions ->
                _uiState.update { it.copy(sessionSummaries = sessions) }
            }
        }
        
        // ÐŸÐ¾Ð´Ð¿Ð¸ÑÑ‹Ð²Ð°ÐµÐ¼ÑÑ Ð½Ð° Ð¾Ð±Ñ‰ÑƒÑŽ Ð´Ð¸ÑÑ‚Ð°Ð½Ñ†Ð¸ÑŽ
        viewModelScope.launch {
            walkRepository.getTotalDistanceKm().collect { distance ->
                _uiState.update { it.copy(totalDistanceKm = distance) }
            }
        }
        
        // ÐŸÐ¾Ð´Ð¿Ð¸ÑÑ‹Ð²Ð°ÐµÐ¼ÑÑ Ð½Ð° Ð¾Ð±Ñ‰ÐµÐµ Ð²Ñ€ÐµÐ¼Ñ Ð¿Ñ€Ð¾Ð³ÑƒÐ»Ð¾Ðº
        viewModelScope.launch {
            walkRepository.getTotalWalkingTimeMs().collect { timeMs ->
                _uiState.update { it.copy(totalWalkingTimeMs = timeMs) }
            }
        }
        
        // === OSM Coverage ===
        
        // Подписываемся на выбранный город
        viewModelScope.launch {
            osmRepository.getSelectedCity().collect { city ->
                val previousSelectedCityId = _uiState.value.selectedCity?.osmId
                _uiState.update {
                    it.copy(
                        selectedCity = city,
                        mapZoomLevel = if (city != null && city.osmId != previousSelectedCityId) {
                            DEFAULT_CITY_ZOOM
                        } else {
                            it.mapZoomLevel
                        }
                    )
                }
                
                cityStatsJob?.cancel()
                if (city != null) {
                    observeCityStats(city.osmId)
                } else {
                    _uiState.update {
                        it.copy(
                            totalRoadsCount = 0,
                            exploredRoadsCount = 0,
                            totalRoadLengthKm = 0.0,
                            exploredRoadLengthKm = 0.0,
                            coveragePercentage = 0f,
                            exploredRoads = emptyList(),
                            coveredRoadChunks = emptyList()
                        )
                    }
                }
            }
        }
    }

    fun setForegroundLocationEnabled(enabled: Boolean) {
        if (enabled) {
            startPassiveLocationUpdates()
        } else {
            stopPassiveLocationUpdates(clearLocation = true)
        }
    }

    private fun startPassiveLocationUpdates() {
        if (passiveLocationUpdatesStarted) return

        passiveLocationUpdatesStarted = true

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    location?.let {
                        handlePassiveLocation(
                            CurrentLocationSnapshot(
                                latitude = it.latitude,
                                longitude = it.longitude,
                                accuracy = it.accuracy
                            )
                        )
                    }
                }

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                PASSIVE_LOCATION_INTERVAL_MS
            ).apply {
                setMinUpdateIntervalMillis(3_000L)
                setMinUpdateDistanceMeters(PASSIVE_LOCATION_MIN_DISTANCE_METERS)
                setWaitForAccurateLocation(false)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                passiveLocationCallback,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            passiveLocationUpdatesStarted = false
        }
    }

    private fun stopPassiveLocationUpdates(clearLocation: Boolean) {
        if (passiveLocationUpdatesStarted) {
            fusedLocationClient.removeLocationUpdates(passiveLocationCallback)
            passiveLocationUpdatesStarted = false
        }

        if (clearLocation) {
            passiveLocationSnapshot = null
            trackingLocationSnapshot = null
            _uiState.update {
                it.copy(
                    currentLocation = null,
                    selectedCity = null,
                    suggestedCityDownload = null,
                    isWeakGpsSignal = false,
                    weakGpsAccuracyMeters = null
                )
            }

            weakSignalSampleCount = 0
            recoveredSignalSampleCount = 0
        }
    }

    private fun handlePassiveLocation(location: CurrentLocationSnapshot) {
        passiveLocationSnapshot = location
        publishCurrentLocation()
        processLocationForCitySelection(location)
    }

    private fun publishCurrentLocation() {
        val displayedLocation = if (_uiState.value.isTracking) {
            trackingLocationSnapshot ?: passiveLocationSnapshot
        } else {
            passiveLocationSnapshot ?: trackingLocationSnapshot
        }

        _uiState.update { it.copy(currentLocation = displayedLocation) }
        updateWeakSignalState(displayedLocation)
    }

    private fun updateWeakSignalState(location: CurrentLocationSnapshot?) {
        if (location == null || location.accuracy <= 0f) {
            weakSignalSampleCount = 0
            recoveredSignalSampleCount = 0
            _uiState.update {
                it.copy(
                    isWeakGpsSignal = false,
                    weakGpsAccuracyMeters = null
                )
            }
            return
        }

        val accuracy = location.accuracy
        val isCurrentlyWeak = _uiState.value.isWeakGpsSignal

        when {
            accuracy >= WEAK_SIGNAL_ENTER_ACCURACY_METERS -> {
                weakSignalSampleCount += 1
                recoveredSignalSampleCount = 0

                if (isCurrentlyWeak || weakSignalSampleCount >= WEAK_SIGNAL_ENTER_SAMPLE_COUNT) {
                    _uiState.update {
                        it.copy(
                            isWeakGpsSignal = true,
                            weakGpsAccuracyMeters = accuracy
                        )
                    }
                }
            }

            accuracy <= WEAK_SIGNAL_EXIT_ACCURACY_METERS -> {
                weakSignalSampleCount = 0
                recoveredSignalSampleCount += 1

                if (isCurrentlyWeak && recoveredSignalSampleCount < WEAK_SIGNAL_EXIT_SAMPLE_COUNT) {
                    _uiState.update { it.copy(weakGpsAccuracyMeters = accuracy) }
                } else {
                    _uiState.update {
                        it.copy(
                            isWeakGpsSignal = false,
                            weakGpsAccuracyMeters = null
                        )
                    }
                }
            }

            else -> {
                weakSignalSampleCount = 0
                recoveredSignalSampleCount = 0

                if (isCurrentlyWeak) {
                    _uiState.update {
                        it.copy(
                            isWeakGpsSignal = true,
                            weakGpsAccuracyMeters = accuracy
                        )
                    }
                } else {
                    _uiState.update { it.copy(weakGpsAccuracyMeters = null) }
                }
            }
        }
    }

    private fun processLocationForCitySelection(location: CurrentLocationSnapshot) {
        if (location.accuracy <= 0f || location.accuracy > MAX_AUTO_CITY_ACCURACY_METERS) return

        autoCityJob?.cancel()
        autoCityJob = viewModelScope.launch {
            val matchedCity = osmRepository.findDownloadedCityForLocation(
                lat = location.latitude,
                lon = location.longitude
            )

            if (matchedCity != null) {
                val selectedCity = osmRepository.getSelectedCitySync()
                if (selectedCity?.osmId != matchedCity.osmId) {
                    osmRepository.selectCity(matchedCity.osmId)
                }

                _uiState.update { it.copy(suggestedCityDownload = null) }
                return@launch
            }

            if (osmRepository.getSelectedCitySync() != null) {
                osmRepository.clearSelectedCity()
            }

            if (!shouldRefreshSuggestedCity(location)) return@launch

            lastCitySuggestionLookupAt = System.currentTimeMillis()
            lastCitySuggestionLookupLocation = location

            osmRepository.suggestCityDownloadForLocation(
                lat = location.latitude,
                lon = location.longitude
            ).onSuccess { suggestion ->
                _uiState.update { it.copy(suggestedCityDownload = suggestion) }
            }
        }
    }

    private fun shouldRefreshSuggestedCity(location: CurrentLocationSnapshot): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCitySuggestionLookupAt >= CITY_SUGGESTION_MIN_REFRESH_INTERVAL_MS) {
            return true
        }

        val previousLookup = lastCitySuggestionLookupLocation ?: return true
        val distanceMeters = distanceBetweenMeters(previousLookup, location)
        return distanceMeters >= CITY_SUGGESTION_MIN_DISTANCE_METERS
    }

    private fun distanceBetweenMeters(
        first: CurrentLocationSnapshot,
        second: CurrentLocationSnapshot
    ): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(second.latitude - first.latitude)
        val dLon = Math.toRadians(second.longitude - first.longitude)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(first.latitude)) * kotlin.math.cos(Math.toRadians(second.latitude)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }
    
    private fun observeCityStats(cityOsmId: Long) {
        cityStatsJob?.cancel()
        cityStatsJob = viewModelScope.launch {
            launch {
                osmRepository.getTotalRoadsCount(cityOsmId).collect { count ->
                    _uiState.update { it.copy(totalRoadsCount = count) }
                }
            }

            launch {
                osmRepository.getExploredRoadsCount(cityOsmId).collect { count ->
                    _uiState.update { it.copy(exploredRoadsCount = count) }
                }
            }

            launch {
                osmRepository.getTotalRoadLength(cityOsmId).collect { length ->
                    val km = (length ?: 0.0) / 1000.0
                    _uiState.update { it.copy(totalRoadLengthKm = km) }
                }
            }

            launch {
                osmRepository.getExploredRoadLength(cityOsmId).collect { length ->
                    val km = (length ?: 0.0) / 1000.0
                    _uiState.update { state ->
                        val percentage = if (state.totalRoadLengthKm > 0) {
                            (km / state.totalRoadLengthKm * 100).toFloat()
                        } else 0f

                        state.copy(
                            exploredRoadLengthKm = km,
                            coveragePercentage = percentage
                        )
                    }
                }
            }

            launch {
                osmRepository.getExploredRoads(cityOsmId).collect { roads ->
                    _uiState.update { it.copy(exploredRoads = roads) }
                }
            }

            launch {
                osmRepository.getCoveredRoadChunks(cityOsmId).collect { chunks ->
                    _uiState.update { it.copy(coveredRoadChunks = chunks) }
                }
            }
        }
    }
    
    fun startTracking() {
        LocationService.start(getApplication())
    }
    
    fun stopTracking() {
        LocationService.stop(getApplication())
    }
    
    fun showCitySelection() {
        _uiState.update { it.copy(showCitySelection = true) }
    }
    
    fun hideCitySelection() {
        _uiState.update { it.copy(showCitySelection = false) }
    }

    fun dismissSuggestedCityDownload() {
        _uiState.update { it.copy(suggestedCityDownload = null) }
    }

    fun requestFocusOnCurrentLocation() {
        _uiState.update {
            val nextZoom = it.mapZoomLevel.coerceAtLeast(NORMAL_FOCUS_ZOOM)
            it.copy(
                focusRequestId = it.focusRequestId + 1,
                focusZoomLevel = nextZoom,
                mapZoomLevel = nextZoom
            )
        }
    }

    fun updateMapZoom(zoomLevel: Double) {
        val clampedZoom = zoomLevel.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
        _uiState.update {
            if (abs(it.mapZoomLevel - clampedZoom) < 0.05) {
                it
            } else {
                it.copy(mapZoomLevel = clampedZoom)
            }
        }
    }

    fun showWalkSessions() {
        _uiState.update { it.copy(showWalkSessions = true) }
    }

    fun toggleCoverageStats() {
        _uiState.update { it.copy(isCoverageStatsExpanded = !it.isCoverageStatsExpanded) }
    }

    fun toggleZoomControl() {
        _uiState.update { it.copy(isZoomControlVisible = !it.isZoomControlVisible) }
    }

    fun hideWalkSessions() {
        _uiState.update { it.copy(showWalkSessions = false) }
    }

    fun deleteWalkSession(sessionId: Long) {
        viewModelScope.launch {
            walkRepository.deleteSession(sessionId)
        }
    }

    fun handleSystemBack(): Boolean {
        return when {
            _uiState.value.showWalkSessions -> {
                hideWalkSessions()
                true
            }

            _uiState.value.showCitySelection -> {
                hideCitySelection()
                true
            }

            else -> false
        }
    }

    override fun onCleared() {
        stopPassiveLocationUpdates(clearLocation = false)
        super.onCleared()
    }
}

