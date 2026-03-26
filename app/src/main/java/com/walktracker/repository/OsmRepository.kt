package com.spywalker.repository

import android.util.Log
import com.spywalker.data.*
import com.spywalker.data.osm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

data class SuggestedCityDownload(
    val cityName: String,
    val searchResult: NominatimSearchResult
)

/**
 * Repository for OpenStreetMap data - city search and road network
 */
@Singleton
class OsmRepository @Inject constructor(
    private val nominatimApi: NominatimApi,
    private val overpassApi: OverpassApi,
    private val cityDao: CityDao
) {
    companion object {
        private const val TAG = "OsmRepository"
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val CITY_BOUNDARY_TOLERANCE_DEGREES = 0.0035
        private const val MAX_CITY_FALLBACK_DISTANCE_METERS = 2_500.0
        private const val MAX_USABLE_SNAP_ACCURACY_METERS = 35f
        private const val MAX_ROAD_MATCH_DISTANCE_METERS = 35.0
        private const val ROAD_CHUNK_SIZE_METERS = 20.0
        private const val MAX_COVERAGE_RADIUS_METERS = 18.0
        private const val MIN_COVERAGE_RADIUS_METERS = 8.0
        private const val MAX_PARALLEL_ROAD_DISTANCE_METERS = 32.0
        private const val MIN_PARALLEL_ROAD_DISTANCE_METERS = 4.0
        private const val MIN_PARALLEL_LENGTH_RATIO = 0.55
        private const val MAX_PARALLEL_ORIENTATION_DIFF_DEGREES = 18.0
        private const val ROAD_SWITCH_PENALTY_METERS = 8.0
        private const val SAME_ROAD_BONUS_METERS = 10.0
        private const val SNAP_JUMP_PENALTY_METERS = 16.0
        private const val MAX_REASONABLE_SNAP_SPEED_MPS = 4.5
        private const val AMBIGUOUS_MATCH_PENALTY_METERS = 7.0
        private const val REQUIRED_MATCH_ADVANTAGE_METERS = 4.5
        private const val REQUIRED_MATCH_ADVANTAGE_WEAK_METERS = 8.0
        private const val SAME_ROAD_CONFIRMATION_DISTANCE_METERS = 22.0
        private const val WEAK_SIGNAL_SNAP_ACCURACY_METERS = 22f
        private const val POINTS_INSERT_BATCH_SIZE = 4_000
        private const val MAX_TILE_EDGE_KM = 10.0
        private const val MAX_TILE_GRID_DIMENSION = 4
        private const val MAX_RETRY_ATTEMPTS_PER_ENDPOINT = 2

        private val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )

        private val RETRYABLE_HTTP_CODES = setOf(429, 502, 503, 504)
    }

    private val sessionMatchStates = mutableMapOf<Long, MatchState>()
    
    // === City Search ===
    
    /**
     * Search for cities by name
     */
    suspend fun searchCities(query: String): Result<List<NominatimSearchResult>> {
        return withContext(Dispatchers.IO) {
            try {
                val results = nominatimApi.searchCity(query)
                Result.success(results)
            } catch (e: IOException) {
                Result.failure(Exception(networkUnavailableMessage("поиска городов"), e))
            } catch (e: HttpException) {
                Result.failure(Exception(formatHttpError("Nominatim", e), e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    /**
     * Download city roads from Overpass API and save to database
     */
    suspend fun downloadCityRoads(
        searchResult: NominatimSearchResult,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<City> {
        return withContext(Dispatchers.IO) {
            try {
                onProgress(0.05f, "Получение границ города...")
                
                // Parse bounding box
                val boundingBox = searchResult.boundingBox
                    ?: return@withContext Result.failure(Exception("Нет границ города"))
                if (boundingBox.size < 4) {
                    return@withContext Result.failure(Exception("Некорректные границы города от Nominatim"))
                }
                
                val south = boundingBox[0].toDouble()
                val north = boundingBox[1].toDouble()
                val west = boundingBox[2].toDouble()
                val east = boundingBox[3].toDouble()
                
                onProgress(0.15f, "Загрузка дорог из OpenStreetMap...")

                val tiles = buildOverpassTiles(south, west, north, east)
                val collectedElements = mutableListOf<OverpassElement>()

                tiles.forEachIndexed { index, tile ->
                    val query = OverpassQueryBuilder.buildRoadsQuery(
                        south = tile.south,
                        west = tile.west,
                        north = tile.north,
                        east = tile.east
                    )
                    val tileLabel = if (tiles.size > 1) " (${index + 1}/${tiles.size})" else ""
                    onProgress(
                        0.15f + (index.toFloat() / tiles.size.toFloat()) * 0.20f,
                        "Загрузка дорог из OpenStreetMap$tileLabel..."
                    )

                    val tileResponse = queryOverpassWithFallback(query)
                    collectedElements += tileResponse.elements
                }

                val uniqueElements = collectedElements.distinctBy { it.type to it.id }
                if (uniqueElements.isEmpty()) {
                    return@withContext Result.failure(
                        Exception("Overpass не вернул дороги для выбранной области")
                    )
                }
                
                onProgress(0.35f, "Обработка дорожной сети...")
                
                // Parse road segments without duplicating all geometry points in memory.
                val parsedRoads = parseRoadSegments(
                    uniqueElements,
                    searchResult.osmId,
                    onProgress = { processedWays, totalWays ->
                        val fraction = if (totalWays > 0) {
                            processedWays.toFloat() / totalWays.toFloat()
                        } else {
                            1f
                        }
                        onProgress(
                            0.35f + fraction * 0.45f,
                            "Обработка дорог: $processedWays / $totalWays"
                        )
                    }
                )
                val normalizedRoads = assignStatisticalRoads(
                    parsedRoads,
                    onProgress = { processedGroups, totalGroups ->
                        val fraction = if (totalGroups > 0) {
                            processedGroups.toFloat() / totalGroups.toFloat()
                        } else {
                            1f
                        }
                        onProgress(
                            0.80f + fraction * 0.05f,
                            "Нормализация дорог: $processedGroups / $totalGroups"
                        )
                    }
                )
                val normalizedSegments = normalizedRoads.map { it.segment }
                
                onProgress(0.85f, "Сохранение ${normalizedSegments.count { !it.isStatsExcluded }} дорог...")
                
                // Calculate total road length
                val totalLength = normalizedSegments
                    .filterNot { it.isStatsExcluded }
                    .sumOf { it.lengthMeters }
                
                // Create City entity
                val city = City(
                    osmId = searchResult.osmId,
                    name = extractCityName(searchResult.displayName),
                    displayName = searchResult.displayName,
                    boundsSouth = south,
                    boundsNorth = north,
                    boundsWest = west,
                    boundsEast = east,
                    centerLat = searchResult.latitude.toDouble(),
                    centerLon = searchResult.longitude.toDouble(),
                    totalRoadSegments = normalizedSegments.count { !it.isStatsExcluded },
                    totalRoadLengthMeters = totalLength,
                    isSelected = true
                )
                
                // Save to database
                cityDao.deselectAllCities()
                onProgress(0.90f, "Сохранение города...")
                cityDao.insertCity(city)
                onProgress(0.94f, "Сохранение сегментов дорог...")
                cityDao.insertRoadSegments(normalizedSegments)
                onProgress(0.98f, "Сохранение геометрии дорог...")
                insertRoadPointsInBatches(normalizedRoads)
                
                onProgress(1.0f, "Готово! Загружено ${normalizedSegments.count { !it.isStatsExcluded }} дорог")
                
                Result.success(city)
                
            } catch (e: IOException) {
                Result.failure(Exception(networkUnavailableMessage("загрузки дорог"), e))
            } catch (e: HttpException) {
                Result.failure(Exception(formatHttpError("Overpass", e), e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun formatHttpError(service: String, exception: HttpException): String {
        val statusCode = exception.code()
        Log.w(TAG, "HTTP error from $service: $statusCode ${exception.message()}", exception)

        return when (service) {
            "Overpass" -> when (statusCode) {
                429 -> "Сервис OpenStreetMap временно ограничил запросы. Попробуйте ещё раз чуть позже."
                502, 503, 504 -> "Сервер OpenStreetMap сейчас перегружен или недоступен. Попробуйте ещё раз позже."
                else -> "Не удалось загрузить дороги из OpenStreetMap (HTTP $statusCode). Попробуйте ещё раз позже."
            }
            "Nominatim" -> when (statusCode) {
                429 -> "Сервис поиска городов временно ограничил запросы. Попробуйте чуть позже."
                502, 503, 504 -> "Сервис поиска городов сейчас недоступен. Попробуйте ещё раз позже."
                else -> "Не удалось выполнить поиск города (HTTP $statusCode)."
            }
            else -> "Ошибка $service (HTTP $statusCode)"
        }
    }

    private fun networkUnavailableMessage(target: String): String {
        return "Не удалось подключиться к сервису $target. Проверьте интернет и попробуйте снова."
    }

    private suspend fun queryOverpassWithFallback(query: String): OverpassResponse {
        var lastError: Exception? = null

        for (endpoint in OVERPASS_ENDPOINTS) {
            repeat(MAX_RETRY_ATTEMPTS_PER_ENDPOINT) { attempt ->
                try {
                    return overpassApi.queryRoads(endpoint, query)
                } catch (e: IOException) {
                    lastError = Exception(networkUnavailableMessage("OpenStreetMap"), e)
                    Log.w(
                        TAG,
                        "Network failure while requesting Overpass endpoint=$endpoint attempt=${attempt + 1}",
                        e
                    )
                } catch (e: HttpException) {
                    Log.w(
                        TAG,
                        "HTTP failure while requesting Overpass endpoint=$endpoint attempt=${attempt + 1} code=${e.code()}",
                        e
                    )

                    if (e.code() !in RETRYABLE_HTTP_CODES) {
                        throw Exception(formatHttpError("Overpass", e), e)
                    }

                    lastError = Exception(formatHttpError("Overpass", e), e)
                }

                if (attempt < MAX_RETRY_ATTEMPTS_PER_ENDPOINT - 1) {
                    delay(750L * (attempt + 1))
                }
            }
        }

        throw lastError ?: Exception("Не удалось загрузить дороги из OpenStreetMap. Попробуйте ещё раз позже.")
    }

    private fun buildOverpassTiles(
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ): List<BoundingBox> {
        val midLatRadians = Math.toRadians((south + north) / 2.0)
        val latKm = abs(north - south) * 111.32
        val lonKm = abs(east - west) * 111.32 * cos(midLatRadians).coerceAtLeast(0.2)

        val rows = ceil(latKm / MAX_TILE_EDGE_KM)
            .toInt()
            .coerceIn(1, MAX_TILE_GRID_DIMENSION)
        val columns = ceil(lonKm / MAX_TILE_EDGE_KM)
            .toInt()
            .coerceIn(1, MAX_TILE_GRID_DIMENSION)

        if (rows == 1 && columns == 1) {
            return listOf(BoundingBox(south = south, west = west, north = north, east = east))
        }

        val latStep = (north - south) / rows.toDouble()
        val lonStep = (east - west) / columns.toDouble()

        return buildList {
            for (row in 0 until rows) {
                val tileSouth = south + latStep * row
                val tileNorth = if (row == rows - 1) north else tileSouth + latStep

                for (column in 0 until columns) {
                    val tileWest = west + lonStep * column
                    val tileEast = if (column == columns - 1) east else tileWest + lonStep
                    add(
                        BoundingBox(
                            south = tileSouth,
                            west = tileWest,
                            north = tileNorth,
                            east = tileEast
                        )
                    )
                }
            }
        }
    }
    
    /**
     * Parse Overpass elements into RoadSegment and RoadPoint entities
     */
    private fun parseRoadSegments(
        elements: List<OverpassElement>,
        cityOsmId: Long,
        onProgress: (processedWays: Int, totalWays: Int) -> Unit = { _, _ -> }
    ): List<ParsedRoadImport> {
        val roads = mutableListOf<ParsedRoadImport>()
        
        // Filter only ways with geometry
        val ways = elements.filter { it.type == "way" && it.geometry != null }
        val totalWays = ways.size
        
        for ((index, way) in ways.withIndex()) {
            val geometry = way.geometry ?: continue
            if (geometry.size < 2) {
                onProgress(index + 1, totalWays)
                continue
            }
            
            // Filter out private roads (client-side filtering)
            val access = way.tags?.get("access")
            if (access == "private" || access == "no") {
                onProgress(index + 1, totalWays)
                continue
            }
            
            val service = way.tags?.get("service")
            if (service == "driveway" || service == "parking_aisle") {
                onProgress(index + 1, totalWays)
                continue
            }
            
            val roadType = way.tags?.get("highway") ?: "unknown"
            val name = way.tags?.get("name")
            
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLon = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            var length = 0.0
            for (i in geometry.indices) {
                val point = geometry[i]
                if (point.lat < minLat) minLat = point.lat
                if (point.lat > maxLat) maxLat = point.lat
                if (point.lon < minLon) minLon = point.lon
                if (point.lon > maxLon) maxLon = point.lon

                if (i == geometry.lastIndex) continue
                length += haversineDistance(
                    point.lat, point.lon,
                    geometry[i + 1].lat, geometry[i + 1].lon
                )
            }
            
            val segment = RoadSegment(
                osmId = way.id,
                cityOsmId = cityOsmId,
                name = name,
                roadType = roadType,
                minLat = minLat,
                maxLat = maxLat,
                minLon = minLon,
                maxLon = maxLon,
                lengthMeters = length * 1000, // km to meters
                statsRoadOsmId = way.id
            )
            roads.add(ParsedRoadImport(segment = segment, geometry = geometry))
            onProgress(index + 1, totalWays)
        }
        
        return roads
    }

    private suspend fun insertRoadPointsInBatches(roads: List<ParsedRoadImport>) {
        val batch = ArrayList<RoadPoint>(POINTS_INSERT_BATCH_SIZE)

        for (road in roads) {
            road.geometry.forEachIndexed { index, point ->
                batch.add(
                    RoadPoint(
                        roadOsmId = road.segment.osmId,
                        orderIndex = index,
                        latitude = point.lat,
                        longitude = point.lon
                    )
                )

                if (batch.size >= POINTS_INSERT_BATCH_SIZE) {
                    cityDao.insertRoadPoints(batch.toList())
                    batch.clear()
                }
            }
        }

        if (batch.isNotEmpty()) {
            cityDao.insertRoadPoints(batch.toList())
        }
    }
    
    /**
     * Extract short city name from full display name
     */
    private fun extractCityName(displayName: String): String {
        return displayName.split(",").firstOrNull()?.trim() ?: displayName
    }
    
    /**
     * Haversine distance in kilometers
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
    
    // === City Management ===
    
    fun getAllCities(): Flow<List<City>> = cityDao.getAllCities()

    suspend fun getAllCitiesSync(): List<City> = cityDao.getAllCitiesSync()
    
    fun getSelectedCity(): Flow<City?> = cityDao.getSelectedCity()

    suspend fun getSelectedCitySync(): City? = cityDao.getSelectedCitySync()
    
    suspend fun selectCity(osmId: Long) = cityDao.setSelectedCity(osmId)

    suspend fun clearSelectedCity() = cityDao.deselectAllCities()
    
    suspend fun deleteCity(osmId: Long) = cityDao.deleteCity(osmId)

    suspend fun findDownloadedCityForLocation(lat: Double, lon: Double): City? {
        return withContext(Dispatchers.IO) {
            val cities = cityDao.getAllCitiesSync()
            if (cities.isEmpty()) return@withContext null

            val containingCity = cities
                .filter { city -> cityContainsLocation(city, lat, lon) }
                .minByOrNull { cityArea(city = it) }

            if (containingCity != null) {
                return@withContext containingCity
            }

            cities
                .map { city -> city to distanceToCityBoundsMeters(city, lat, lon) }
                .filter { (_, distanceMeters) -> distanceMeters <= MAX_CITY_FALLBACK_DISTANCE_METERS }
                .minByOrNull { (_, distanceMeters) -> distanceMeters }
                ?.first
        }
    }

    suspend fun suggestCityDownloadForLocation(
        lat: Double,
        lon: Double
    ): Result<SuggestedCityDownload?> {
        return withContext(Dispatchers.IO) {
            try {
                val reverseResult = nominatimApi.reverseGeocode(lat, lon)
                val cityName = reverseResult.address.extractBestCityName()
                    ?: extractCityName(reverseResult.displayName)

                if (cityName.isBlank()) {
                    return@withContext Result.success(null)
                }

                val candidates = nominatimApi.searchCity(cityName)
                    .distinctBy { it.osmType to it.osmId }

                val bestCandidate = candidates
                    .firstOrNull { result -> searchResultContainsLocation(result, lat, lon) }
                    ?: candidates.minByOrNull { result ->
                        haversineDistance(
                            lat,
                            lon,
                            result.latitude.toDoubleOrNull() ?: lat,
                            result.longitude.toDoubleOrNull() ?: lon
                        ) * 1000.0
                    }

                Result.success(
                    bestCandidate?.let { candidate ->
                        SuggestedCityDownload(
                            cityName = cityName,
                            searchResult = candidate
                        )
                    }
                )
            } catch (e: IOException) {
                Result.failure(Exception(networkUnavailableMessage("определения текущего города"), e))
            } catch (e: HttpException) {
                Result.failure(Exception(formatHttpError("Nominatim", e), e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // === Road Coverage ===
    
    fun getTotalRoadsCount(cityOsmId: Long): Flow<Int> = cityDao.getTotalRoadsCount(cityOsmId)
    
    fun getExploredRoadsCount(cityOsmId: Long): Flow<Int> = cityDao.getExploredRoadsCountFromChunks(cityOsmId)
    
    fun getTotalRoadLength(cityOsmId: Long): Flow<Double?> = cityDao.getTotalRoadLength(cityOsmId)
    
    fun getExploredRoadLength(cityOsmId: Long): Flow<Double?> = cityDao.getExploredRoadLengthFromChunks(cityOsmId)
    
    fun getExploredRoads(cityOsmId: Long): Flow<List<RoadSegment>> = 
        cityDao.getRoadsWithCoveredChunksForCity(cityOsmId)

    fun getCoveredRoadChunks(cityOsmId: Long): Flow<List<RoadCoverageChunk>> =
        cityDao.getCoveredRoadChunksForCity(cityOsmId)
    
    /**
     * Mark roads as explored based on GPS point
     * Returns number of newly explored roads
     */
    suspend fun markRoadsExplored(
        lat: Double, 
        lon: Double, 
        sessionId: Long,
        accuracy: Float = 0f
    ): Int {
        val city = cityDao.getSelectedCitySync() ?: return 0
        if (accuracy > MAX_USABLE_SNAP_ACCURACY_METERS) return 0
        val now = System.currentTimeMillis()
        val previousMatch = sessionMatchStates[sessionId]
        
        // Find nearby roads
        val nearbyRoads = cityDao.findNearbyRoads(
            cityOsmId = city.osmId,
            lat = lat,
            lon = lon,
            tolerance = 0.00045 // ~45-50 meters
        )
        if (nearbyRoads.isEmpty()) return 0
        
        var bestCandidate: SnappedRoadCandidate? = null
        var secondBestCandidate: SnappedRoadCandidate? = null
        val allowedMatchDistance = dynamicAllowedMatchDistanceMeters(accuracy)
        
        for (road in nearbyRoads) {
            val points = cityDao.getRoadPoints(road.osmId)
            if (points.size < 2) continue

            val projection = projectPointToPolyline(lat, lon, points)
            if (projection.distanceToRoadMeters <= allowedMatchDistance) {
                var score = projection.distanceToRoadMeters
                previousMatch?.let { previous ->
                    score += if (previous.roadOsmId == road.osmId) {
                        -SAME_ROAD_BONUS_METERS
                    } else {
                        ROAD_SWITCH_PENALTY_METERS
                    }

                    val timeDeltaSeconds = ((now - previous.timestamp) / 1000.0).coerceAtLeast(1.0)
                    val snappedMoveMeters = haversineDistance(
                        previous.snappedLatitude,
                        previous.snappedLongitude,
                        projection.snappedLatitude,
                        projection.snappedLongitude
                    ) * 1000.0
                    val rawMoveMeters = haversineDistance(
                        previous.rawLatitude,
                        previous.rawLongitude,
                        lat,
                        lon
                    ) * 1000.0
                    val snappedSpeedMps = snappedMoveMeters / timeDeltaSeconds

                    if (
                        snappedSpeedMps > MAX_REASONABLE_SNAP_SPEED_MPS &&
                        rawMoveMeters < snappedMoveMeters * 0.6
                    ) {
                        score += SNAP_JUMP_PENALTY_METERS
                    }

                    if (
                        previous.roadOsmId != road.osmId &&
                        previous.distanceToRoadMeters <= SAME_ROAD_CONFIRMATION_DISTANCE_METERS &&
                        projection.distanceToRoadMeters <= SAME_ROAD_CONFIRMATION_DISTANCE_METERS &&
                        accuracy >= WEAK_SIGNAL_SNAP_ACCURACY_METERS
                    ) {
                        score += AMBIGUOUS_MATCH_PENALTY_METERS
                    }
                }

                val candidate = SnappedRoadCandidate(
                    road = road,
                    points = points,
                    distanceToRoadMeters = projection.distanceToRoadMeters,
                    distanceAlongRoadMeters = projection.distanceAlongRoadMeters,
                    snappedLatitude = projection.snappedLatitude,
                    snappedLongitude = projection.snappedLongitude,
                    score = score
                )

                if (bestCandidate?.score == null || candidate.score < bestCandidate.score) {
                    secondBestCandidate = bestCandidate
                    bestCandidate = candidate
                } else if (secondBestCandidate?.score == null || candidate.score < secondBestCandidate.score) {
                    secondBestCandidate = candidate
                }
            }
        }

        val candidate = bestCandidate ?: return 0
        val scoreAdvantage = (secondBestCandidate?.score ?: Double.MAX_VALUE) - candidate.score
        val requiredAdvantage = if (accuracy >= WEAK_SIGNAL_SNAP_ACCURACY_METERS) {
            REQUIRED_MATCH_ADVANTAGE_WEAK_METERS
        } else {
            REQUIRED_MATCH_ADVANTAGE_METERS
        }
        if (scoreAdvantage < requiredAdvantage && previousMatch?.roadOsmId != candidate.road.osmId) {
            return 0
        }

        sessionMatchStates[sessionId] = MatchState(
            roadOsmId = candidate.road.osmId,
            rawLatitude = lat,
            rawLongitude = lon,
            snappedLatitude = candidate.snappedLatitude,
            snappedLongitude = candidate.snappedLongitude,
            distanceToRoadMeters = candidate.distanceToRoadMeters,
            timestamp = now
        )
        val chunks = buildCoverageChunks(
            cityOsmId = city.osmId,
            road = candidate.road,
            points = candidate.points,
            sessionId = sessionId,
            distanceAlongRoadMeters = candidate.distanceAlongRoadMeters,
            accuracy = accuracy
        )
        if (chunks.isEmpty()) return 0

        val inserted = cityDao.insertCoveredRoadChunks(chunks).count { it != -1L }
        
        if (inserted > 0) {
            cityDao.updateCityExploredLength(city.osmId)
        }
        
        return inserted
    }

    private fun assignStatisticalRoads(
        roads: List<ParsedRoadImport>,
        onProgress: (processedGroups: Int, totalGroups: Int) -> Unit = { _, _ -> }
    ): List<ParsedRoadImport> {
        val excludedRoadIds = mutableSetOf<Long>()
        val representatives = roads.associate { it.segment.osmId to it.segment.osmId }.toMutableMap()

        val eligibleGroups = roads
            .mapNotNull { road ->
                val segment = road.segment
                val normalizedName = normalizeRoadName(segment.name)
                if (!isRoadEligibleForStats(segment.roadType) || normalizedName.isNullOrBlank()) {
                    null
                } else {
                    GroupedRoadImport(
                        road = road,
                        normalizedName = normalizedName
                    )
                }
            }
            .groupBy { RoadGroupingKey(it.road.segment.roadType, it.normalizedName) }
            .values

        val totalGroups = eligibleGroups.size
        eligibleGroups.forEachIndexed { groupIndex, groupedRoads ->
            val sortedGroup = groupedRoads
                .sortedByDescending { it.road.segment.lengthMeters }

            for (index in sortedGroup.indices) {
                val baseRoadImport = sortedGroup[index].road
                val baseRoad = baseRoadImport.segment
                if (baseRoad.osmId in excludedRoadIds) continue
                val basePoints = baseRoadImport.geometry
                if (basePoints.size < 2) continue

                for (candidateIndex in index + 1 until sortedGroup.size) {
                    val candidateRoadImport = sortedGroup[candidateIndex].road
                    val candidateRoad = candidateRoadImport.segment
                    if (candidateRoad.osmId in excludedRoadIds) continue
                    val candidatePoints = candidateRoadImport.geometry
                    if (candidatePoints.size < 2) continue

                    if (areLikelyParallelDuplicates(baseRoad, basePoints, candidateRoad, candidatePoints)) {
                        representatives[candidateRoad.osmId] = baseRoad.osmId
                        excludedRoadIds += candidateRoad.osmId
                    }
                }
            }

            onProgress(groupIndex + 1, totalGroups)
        }

        return roads.map { road ->
            val segment = road.segment
            val excluded = !isRoadEligibleForStats(segment.roadType) || segment.osmId in excludedRoadIds
            road.copy(
                segment = segment.copy(
                    statsRoadOsmId = representatives[segment.osmId] ?: segment.osmId,
                    isStatsExcluded = excluded
                )
            )
        }
    }

    private fun isRoadEligibleForStats(roadType: String): Boolean {
        return roadType in setOf(
            "primary",
            "secondary",
            "tertiary",
            "residential",
            "living_street",
            "unclassified",
            "pedestrian"
        )
    }

    private fun areLikelyParallelDuplicates(
        first: RoadSegment,
        firstPoints: List<OverpassLatLon>,
        second: RoadSegment,
        secondPoints: List<OverpassLatLon>
    ): Boolean {
        val firstName = normalizeRoadName(first.name) ?: return false
        val secondName = normalizeRoadName(second.name) ?: return false
        if (firstName != secondName) return false
        if (first.roadType != second.roadType) return false

        val lengthRatio = min(first.lengthMeters, second.lengthMeters) /
            max(first.lengthMeters, second.lengthMeters)
        if (lengthRatio < MIN_PARALLEL_LENGTH_RATIO) return false

        val midpointDistanceMeters = haversineDistance(
            firstPoints[firstPoints.lastIndex / 2].lat,
            firstPoints[firstPoints.lastIndex / 2].lon,
            secondPoints[secondPoints.lastIndex / 2].lat,
            secondPoints[secondPoints.lastIndex / 2].lon
        ) * 1000.0
        if (
            midpointDistanceMeters < MIN_PARALLEL_ROAD_DISTANCE_METERS ||
            midpointDistanceMeters > MAX_PARALLEL_ROAD_DISTANCE_METERS
        ) {
            return false
        }

        val firstBearing = polylineBearingDegrees(firstPoints)
        val secondBearing = polylineBearingDegrees(secondPoints)
        if (orientationDifferenceDegrees(firstBearing, secondBearing) > MAX_PARALLEL_ORIENTATION_DIFF_DEGREES) {
            return false
        }

        return boundingBoxOverlapRatio(first, second) >= 0.45
    }

    private fun normalizeRoadName(name: String?): String? {
        return name
            ?.trim()
            ?.lowercase()
            ?.replace("ё", "е")
            ?.takeIf { it.isNotBlank() }
    }

    private fun polylineBearingDegrees(points: List<OverpassLatLon>): Double {
        val start = points.first()
        val end = points.last()
        val lat1 = Math.toRadians(start.lat)
        val lat2 = Math.toRadians(end.lat)
        val dLon = Math.toRadians(end.lon - start.lon)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun orientationDifferenceDegrees(first: Double, second: Double): Double {
        val directDifference = abs(first - second) % 360.0
        val wrappedDifference = min(directDifference, 360.0 - directDifference)
        return min(wrappedDifference, abs(180.0 - wrappedDifference))
    }

    private fun boundingBoxOverlapRatio(first: RoadSegment, second: RoadSegment): Double {
        val latOverlap = max(0.0, min(first.maxLat, second.maxLat) - max(first.minLat, second.minLat))
        val lonOverlap = max(0.0, min(first.maxLon, second.maxLon) - max(first.minLon, second.minLon))
        val minLatSpan = min(first.maxLat - first.minLat, second.maxLat - second.minLat).coerceAtLeast(1e-6)
        val minLonSpan = min(first.maxLon - first.minLon, second.maxLon - second.minLon).coerceAtLeast(1e-6)
        return max(latOverlap / minLatSpan, lonOverlap / minLonSpan)
    }

    private fun buildCoverageChunks(
        cityOsmId: Long,
        road: RoadSegment,
        points: List<RoadPoint>,
        sessionId: Long,
        distanceAlongRoadMeters: Double,
        accuracy: Float
    ): List<RoadCoverageChunk> {
        val chunkGeometries = buildChunkGeometries(points)
        if (chunkGeometries.isEmpty()) return emptyList()

        val coverageRadiusMeters = dynamicCoverageRadiusMeters(accuracy)
        val fromMeters = (distanceAlongRoadMeters - coverageRadiusMeters).coerceAtLeast(0.0)
        val toMeters = distanceAlongRoadMeters + coverageRadiusMeters

        return chunkGeometries
            .filter { geometry ->
                geometry.endDistanceMeters >= fromMeters && geometry.startDistanceMeters <= toMeters
            }
            .map { geometry ->
                RoadCoverageChunk(
                    cityOsmId = cityOsmId,
                    roadOsmId = road.osmId,
                    sessionId = sessionId,
                    chunkIndex = geometry.chunkIndex,
                    startLatitude = geometry.startLatitude,
                    startLongitude = geometry.startLongitude,
                    endLatitude = geometry.endLatitude,
                    endLongitude = geometry.endLongitude,
                    lengthMeters = geometry.lengthMeters
                )
            }
    }

    private fun buildChunkGeometries(points: List<RoadPoint>): List<ChunkGeometry> {
        if (points.size < 2) return emptyList()

        val sortedPoints = points.sortedBy { it.orderIndex }
        val totalLengthMeters = polylineLengthMeters(sortedPoints)
        if (totalLengthMeters <= 0.0) return emptyList()

        val chunkCount = ceil(totalLengthMeters / ROAD_CHUNK_SIZE_METERS).toInt()
        return (0 until chunkCount).mapNotNull { chunkIndex ->
            val startDistance = chunkIndex * ROAD_CHUNK_SIZE_METERS
            val endDistance = min(totalLengthMeters, (chunkIndex + 1) * ROAD_CHUNK_SIZE_METERS)
            val startPoint = interpolatePointAtDistance(sortedPoints, startDistance) ?: return@mapNotNull null
            val endPoint = interpolatePointAtDistance(sortedPoints, endDistance) ?: return@mapNotNull null

            ChunkGeometry(
                chunkIndex = chunkIndex,
                startDistanceMeters = startDistance,
                endDistanceMeters = endDistance,
                startLatitude = startPoint.first,
                startLongitude = startPoint.second,
                endLatitude = endPoint.first,
                endLongitude = endPoint.second,
                lengthMeters = endDistance - startDistance
            )
        }
    }

    private fun interpolatePointAtDistance(
        points: List<RoadPoint>,
        targetDistanceMeters: Double
    ): Pair<Double, Double>? {
        if (points.isEmpty()) return null
        if (targetDistanceMeters <= 0.0) {
            return points.first().latitude to points.first().longitude
        }

        var traversedMeters = 0.0
        for (i in 0 until points.lastIndex) {
            val start = points[i]
            val end = points[i + 1]
            val segmentLengthMeters = haversineDistance(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude
            ) * 1000.0

            if (segmentLengthMeters == 0.0) continue

            if (traversedMeters + segmentLengthMeters >= targetDistanceMeters) {
                val fraction = ((targetDistanceMeters - traversedMeters) / segmentLengthMeters)
                    .coerceIn(0.0, 1.0)
                return lerpLatLon(start, end, fraction)
            }

            traversedMeters += segmentLengthMeters
        }

        return points.last().latitude to points.last().longitude
    }

    private fun projectPointToPolyline(
        lat: Double,
        lon: Double,
        points: List<RoadPoint>
    ): PolylineProjection {
        var minDistanceMeters = Double.MAX_VALUE
        var bestDistanceAlongRoadMeters = 0.0
        var snappedLatitude = points.first().latitude
        var snappedLongitude = points.first().longitude
        var traversedMeters = 0.0

        for (i in 0 until points.lastIndex) {
            val start = points[i]
            val end = points[i + 1]
            val segmentLengthMeters = haversineDistance(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude
            ) * 1000.0
            if (segmentLengthMeters == 0.0) continue

            val projection = projectPointToSegment(
                pointLat = lat,
                pointLon = lon,
                startLat = start.latitude,
                startLon = start.longitude,
                endLat = end.latitude,
                endLon = end.longitude
            )

            if (projection.distanceMeters < minDistanceMeters) {
                minDistanceMeters = projection.distanceMeters
                bestDistanceAlongRoadMeters = traversedMeters + segmentLengthMeters * projection.segmentFraction
                snappedLatitude = projection.snappedLatitude
                snappedLongitude = projection.snappedLongitude
            }

            traversedMeters += segmentLengthMeters
        }

        return PolylineProjection(
            distanceToRoadMeters = minDistanceMeters,
            distanceAlongRoadMeters = bestDistanceAlongRoadMeters,
            snappedLatitude = snappedLatitude,
            snappedLongitude = snappedLongitude
        )
    }

    private fun projectPointToSegment(
        pointLat: Double,
        pointLon: Double,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): SegmentProjection {
        val referenceLat = Math.toRadians((pointLat + startLat + endLat) / 3.0)

        fun project(lat: Double, lon: Double): Pair<Double, Double> {
            val x = Math.toRadians(lon) * EARTH_RADIUS_METERS * cos(referenceLat)
            val y = Math.toRadians(lat) * EARTH_RADIUS_METERS
            return x to y
        }

        val (px, py) = project(pointLat, pointLon)
        val (ax, ay) = project(startLat, startLon)
        val (bx, by) = project(endLat, endLon)

        val abx = bx - ax
        val aby = by - ay
        val abLengthSquared = abx * abx + aby * aby
        if (abLengthSquared == 0.0) {
            return SegmentProjection(
                distanceMeters = sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay)),
                segmentFraction = 0.0,
                snappedLatitude = startLat,
                snappedLongitude = startLon
            )
        }

        val apx = px - ax
        val apy = py - ay
        val t = ((apx * abx) + (apy * aby)) / abLengthSquared
        val clampedT = t.coerceIn(0.0, 1.0)
        val closestX = ax + abx * clampedT
        val closestY = ay + aby * clampedT

        return SegmentProjection(
            distanceMeters = sqrt((px - closestX) * (px - closestX) + (py - closestY) * (py - closestY)),
            segmentFraction = clampedT,
            snappedLatitude = startLat + (endLat - startLat) * clampedT,
            snappedLongitude = startLon + (endLon - startLon) * clampedT
        )
    }

    private fun lerpLatLon(start: RoadPoint, end: RoadPoint, fraction: Double): Pair<Double, Double> {
        val lat = start.latitude + (end.latitude - start.latitude) * fraction
        val lon = start.longitude + (end.longitude - start.longitude) * fraction
        return lat to lon
    }

    private fun polylineLengthMeters(points: List<RoadPoint>): Double {
        var total = 0.0
        for (i in 0 until points.lastIndex) {
            total += haversineDistance(
                points[i].latitude,
                points[i].longitude,
                points[i + 1].latitude,
                points[i + 1].longitude
            ) * 1000.0
        }
        return total
    }

    private fun dynamicAllowedMatchDistanceMeters(accuracy: Float): Double {
        if (accuracy <= 0f) return MAX_ROAD_MATCH_DISTANCE_METERS

        return when {
            accuracy <= 8f -> MAX_ROAD_MATCH_DISTANCE_METERS
            accuracy <= 15f -> 28.0
            accuracy <= 22f -> 22.0
            else -> 18.0
        }
    }

    private fun dynamicCoverageRadiusMeters(accuracy: Float): Double {
        if (accuracy <= 0f) return MAX_COVERAGE_RADIUS_METERS

        return when {
            accuracy <= 8f -> MAX_COVERAGE_RADIUS_METERS
            accuracy <= 15f -> 14.0
            accuracy <= 22f -> 11.0
            else -> MIN_COVERAGE_RADIUS_METERS
        }
    }

    private fun cityContainsLocation(city: City, lat: Double, lon: Double): Boolean {
        return lat in (city.boundsSouth - CITY_BOUNDARY_TOLERANCE_DEGREES)..(city.boundsNorth + CITY_BOUNDARY_TOLERANCE_DEGREES) &&
            lon in (city.boundsWest - CITY_BOUNDARY_TOLERANCE_DEGREES)..(city.boundsEast + CITY_BOUNDARY_TOLERANCE_DEGREES)
    }

    private fun cityArea(city: City): Double {
        return (city.boundsNorth - city.boundsSouth).absoluteValue *
            (city.boundsEast - city.boundsWest).absoluteValue
    }

    private fun distanceToCityBoundsMeters(city: City, lat: Double, lon: Double): Double {
        val clampedLat = lat.coerceIn(city.boundsSouth, city.boundsNorth)
        val clampedLon = lon.coerceIn(city.boundsWest, city.boundsEast)
        return haversineDistance(lat, lon, clampedLat, clampedLon) * 1000.0
    }

    private fun searchResultContainsLocation(
        result: NominatimSearchResult,
        lat: Double,
        lon: Double
    ): Boolean {
        val bounds = result.boundingBox ?: return false
        if (bounds.size < 4) return false

        val south = bounds[0].toDoubleOrNull() ?: return false
        val north = bounds[1].toDoubleOrNull() ?: return false
        val west = bounds[2].toDoubleOrNull() ?: return false
        val east = bounds[3].toDoubleOrNull() ?: return false

        return lat in (south - CITY_BOUNDARY_TOLERANCE_DEGREES)..(north + CITY_BOUNDARY_TOLERANCE_DEGREES) &&
            lon in (west - CITY_BOUNDARY_TOLERANCE_DEGREES)..(east + CITY_BOUNDARY_TOLERANCE_DEGREES)
    }

    private fun NominatimAddress?.extractBestCityName(): String? {
        return this?.city
            ?: this?.town
            ?: this?.village
            ?: this?.municipality
            ?: this?.county
            ?: this?.state
    }

    private data class SnappedRoadCandidate(
        val road: RoadSegment,
        val points: List<RoadPoint>,
        val distanceToRoadMeters: Double,
        val distanceAlongRoadMeters: Double,
        val snappedLatitude: Double,
        val snappedLongitude: Double,
        val score: Double
    )

    private data class PolylineProjection(
        val distanceToRoadMeters: Double,
        val distanceAlongRoadMeters: Double,
        val snappedLatitude: Double,
        val snappedLongitude: Double
    )

    private data class SegmentProjection(
        val distanceMeters: Double,
        val segmentFraction: Double,
        val snappedLatitude: Double,
        val snappedLongitude: Double
    )

    private data class MatchState(
        val roadOsmId: Long,
        val rawLatitude: Double,
        val rawLongitude: Double,
        val snappedLatitude: Double,
        val snappedLongitude: Double,
        val distanceToRoadMeters: Double,
        val timestamp: Long
    )

    private data class ChunkGeometry(
        val chunkIndex: Int,
        val startDistanceMeters: Double,
        val endDistanceMeters: Double,
        val startLatitude: Double,
        val startLongitude: Double,
        val endLatitude: Double,
        val endLongitude: Double,
        val lengthMeters: Double
    )

    private data class ParsedRoadImport(
        val segment: RoadSegment,
        val geometry: List<OverpassLatLon>
    )

    private data class GroupedRoadImport(
        val road: ParsedRoadImport,
        val normalizedName: String
    )

    private data class RoadGroupingKey(
        val roadType: String,
        val normalizedName: String
    )

    private data class BoundingBox(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double
    )
}

