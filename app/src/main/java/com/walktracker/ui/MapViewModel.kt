package com.spywalker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spywalker.data.City
import com.spywalker.data.RoadCoverageChunk
import com.spywalker.data.RoadSegment
import com.spywalker.data.WalkSessionSummary
import com.spywalker.repository.OsmRepository
import com.spywalker.repository.WalkRepository
import com.spywalker.service.CurrentLocationSnapshot
import com.spywalker.service.LocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val exploredRoads: List<RoadSegment> = emptyList(),
    val coveredRoadChunks: List<RoadCoverageChunk> = emptyList(),
    val sessionSummaries: List<WalkSessionSummary> = emptyList(),
    val isTracking: Boolean = false,
    val currentPointsCount: Int = 0,
    val currentLocation: CurrentLocationSnapshot? = null,
    val focusRequestId: Int = 0,
    val focusZoomLevel: Double = 18.0,
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
    
    // City selection
    val showCitySelection: Boolean = false,
    val showWalkSessions: Boolean = false
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val walkRepository: WalkRepository,
    private val osmRepository: OsmRepository,
    application: Application
) : AndroidViewModel(application) {
    companion object {
        private const val NORMAL_FOCUS_ZOOM = 18.0
        private const val CLOSE_FOCUS_ZOOM = 19.0
    }

    
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()
    private var cityStatsJob: Job? = null
    
    init {
        // ÐŸÐ¾Ð´Ð¿Ð¸ÑÑ‹Ð²Ð°ÐµÐ¼ÑÑ Ð½Ð° ÑÐ¾ÑÑ‚Ð¾ÑÐ½Ð¸Ðµ Ñ‚Ñ€ÐµÐºÐ¸Ð½Ð³Ð°
        viewModelScope.launch {
            LocationService.isTracking.collect { isTracking ->
                _uiState.update { it.copy(isTracking = isTracking) }
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
                _uiState.update { it.copy(currentLocation = location) }
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
                _uiState.update { it.copy(selectedCity = city) }
                
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

    fun requestFocusOnCurrentLocation() {
        _uiState.update {
            val nextZoom = if (it.focusZoomLevel >= CLOSE_FOCUS_ZOOM) {
                NORMAL_FOCUS_ZOOM
            } else {
                CLOSE_FOCUS_ZOOM
            }
            it.copy(
                focusRequestId = it.focusRequestId + 1,
                focusZoomLevel = nextZoom
            )
        }
    }

    fun showWalkSessions() {
        _uiState.update { it.copy(showWalkSessions = true) }
    }

    fun hideWalkSessions() {
        _uiState.update { it.copy(showWalkSessions = false) }
    }

    fun deleteWalkSession(sessionId: Long) {
        viewModelScope.launch {
            walkRepository.deleteSession(sessionId)
        }
    }
}

