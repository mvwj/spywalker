package com.spywalker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spywalker.data.City
import com.spywalker.data.osm.NominatimSearchResult
import com.spywalker.repository.OsmRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CitySelectionUiState(
    val searchQuery: String = "",
    val searchResults: List<NominatimSearchResult> = emptyList(),
    val downloadedCities: List<City> = emptyList(),
    val selectedCity: City? = null,
    val isSearching: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadStatus: String = "",
    val error: String? = null
)

@HiltViewModel
class CitySelectionViewModel @Inject constructor(
    private val osmRepository: OsmRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CitySelectionUiState())
    val uiState: StateFlow<CitySelectionUiState> = _uiState.asStateFlow()
    
    init {
        // Load downloaded cities
        viewModelScope.launch {
            osmRepository.getAllCities().collect { cities ->
                _uiState.update { it.copy(downloadedCities = cities) }
            }
        }
        
        // Load selected city
        viewModelScope.launch {
            osmRepository.getSelectedCity().collect { city ->
                _uiState.update { it.copy(selectedCity = city) }
            }
        }
    }
    
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }
    
    fun searchCities() {
        val query = _uiState.value.searchQuery
        if (query.length < 3) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            
            osmRepository.searchCities(query).fold(
                onSuccess = { results ->
                    val uniqueResults = results.distinctBy { it.osmType to it.osmId }
                    _uiState.update { 
                        it.copy(
                            searchResults = uniqueResults,
                            isSearching = false
                        ) 
                    }
                },
                onFailure = { e ->
                    _uiState.update { 
                        it.copy(
                            error = "Ошибка поиска: ${e.message}",
                            isSearching = false
                        ) 
                    }
                }
            )
        }
    }
    
    fun downloadCity(searchResult: NominatimSearchResult) {
        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    isDownloading = true, 
                    downloadProgress = 0f,
                    downloadStatus = "Начинаю загрузку...",
                    error = null
                ) 
            }
            
            osmRepository.downloadCityRoads(
                searchResult = searchResult,
                onProgress = { progress, status ->
                    _uiState.update { 
                        it.copy(downloadProgress = progress, downloadStatus = status) 
                    }
                }
            ).fold(
                onSuccess = { _ ->
                    _uiState.update { 
                        it.copy(
                            isDownloading = false,
                            searchResults = emptyList(),
                            searchQuery = ""
                        ) 
                    }
                },
                onFailure = { e ->
                    _uiState.update { 
                        it.copy(
                            error = "Ошибка загрузки: ${e.message}",
                            isDownloading = false
                        ) 
                    }
                }
            )
        }
    }
    
    fun selectCity(city: City) {
        viewModelScope.launch {
            osmRepository.selectCity(city.osmId)
        }
    }
    
    fun deleteCity(city: City) {
        viewModelScope.launch {
            osmRepository.deleteCity(city.osmId)
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

