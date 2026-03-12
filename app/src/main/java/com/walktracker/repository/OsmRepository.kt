package com.spywalker.repository

import com.spywalker.data.*
import com.spywalker.data.osm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

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
        private const val EARTH_RADIUS_METERS = 6_371_000.0
        private const val MAX_USABLE_SNAP_ACCURACY_METERS = 35f
        private const val MAX_ROAD_MATCH_DISTANCE_METERS = 35.0
        private const val ROAD_CHUNK_SIZE_METERS = 20.0
        private const val COVERAGE_RADIUS_METERS = 18.0
        private const val MAX_PARALLEL_ROAD_DISTANCE_METERS = 32.0
        private const val MIN_PARALLEL_ROAD_DISTANCE_METERS = 4.0
        private const val MIN_PARALLEL_LENGTH_RATIO = 0.55
        private const val MAX_PARALLEL_ORIENTATION_DIFF_DEGREES = 18.0
        private const val ROAD_SWITCH_PENALTY_METERS = 8.0
        private const val SAME_ROAD_BONUS_METERS = 10.0
        private const val SNAP_JUMP_PENALTY_METERS = 16.0
        private const val MAX_REASONABLE_SNAP_SPEED_MPS = 4.5
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
                
                // Build Overpass query
                val query = OverpassQueryBuilder.buildRoadsQuery(south, west, north, east)
                
                // Fetch roads from Overpass
                val overpassResponse = overpassApi.queryRoads(query)
                if (overpassResponse.elements.isEmpty()) {
                    return@withContext Result.failure(
                        Exception("Overpass не вернул дороги для выбранной области")
                    )
                }
                
                onProgress(0.35f, "Обработка дорожной сети...")
                
                // Parse road segments
                val (segments, points) = parseRoadSegments(
                    overpassResponse.elements,
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
                val normalizedSegments = assignStatisticalRoads(segments, points)
                
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
                cityDao.insertRoadPoints(points)
                
                onProgress(1.0f, "Готово! Загружено ${normalizedSegments.count { !it.isStatsExcluded }} дорог")
                
                Result.success(city)
                
            } catch (e: HttpException) {
                Result.failure(Exception(formatHttpError("Overpass", e), e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun formatHttpError(service: String, exception: HttpException): String {
        val errorBody = try {
            exception.response()?.errorBody()?.string()?.take(500)
        } catch (_: Exception) {
            null
        }

        return buildString {
            append("Ошибка ${service}: HTTP ${exception.code()}")
            val message = exception.message().takeIf { it.isNotBlank() }
            if (message != null) {
                append(" (${message})")
            }
            if (!errorBody.isNullOrBlank()) {
                append(". ")
                append(errorBody)
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
    ): Pair<List<RoadSegment>, List<RoadPoint>> {
        val segments = mutableListOf<RoadSegment>()
        val allPoints = mutableListOf<RoadPoint>()
        
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
            
            // Calculate bounding box
            val lats = geometry.map { it.lat }
            val lons = geometry.map { it.lon }
            
            // Calculate road length
            var length = 0.0
            for (i in 0 until geometry.size - 1) {
                length += haversineDistance(
                    geometry[i].lat, geometry[i].lon,
                    geometry[i + 1].lat, geometry[i + 1].lon
                )
            }
            
            val segment = RoadSegment(
                osmId = way.id,
                cityOsmId = cityOsmId,
                name = name,
                roadType = roadType,
                minLat = lats.minOrNull() ?: 0.0,
                maxLat = lats.maxOrNull() ?: 0.0,
                minLon = lons.minOrNull() ?: 0.0,
                maxLon = lons.maxOrNull() ?: 0.0,
                lengthMeters = length * 1000, // km to meters
                statsRoadOsmId = way.id
            )
            segments.add(segment)
            
            // Create road points
            geometry.forEachIndexed { index, point ->
                allPoints.add(
                    RoadPoint(
                        roadOsmId = way.id,
                        orderIndex = index,
                        latitude = point.lat,
                        longitude = point.lon
                    )
                )
            }
            onProgress(index + 1, totalWays)
        }
        
        return Pair(segments, allPoints)
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
    
    fun getSelectedCity(): Flow<City?> = cityDao.getSelectedCity()
    
    suspend fun selectCity(osmId: Long) = cityDao.setSelectedCity(osmId)
    
    suspend fun deleteCity(osmId: Long) = cityDao.deleteCity(osmId)
    
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
        
        for (road in nearbyRoads) {
            val points = cityDao.getRoadPoints(road.osmId)
            if (points.size < 2) continue

            val projection = projectPointToPolyline(lat, lon, points)
            if (projection.distanceToRoadMeters <= MAX_ROAD_MATCH_DISTANCE_METERS) {
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

                if (bestCandidate == null || candidate.score < bestCandidate!!.score) {
                    bestCandidate = candidate
                }
            }
        }

        val candidate = bestCandidate ?: return 0
        sessionMatchStates[sessionId] = MatchState(
            roadOsmId = candidate.road.osmId,
            rawLatitude = lat,
            rawLongitude = lon,
            snappedLatitude = candidate.snappedLatitude,
            snappedLongitude = candidate.snappedLongitude,
            timestamp = now
        )
        val chunks = buildCoverageChunks(
            cityOsmId = city.osmId,
            road = candidate.road,
            points = candidate.points,
            sessionId = sessionId,
            distanceAlongRoadMeters = candidate.distanceAlongRoadMeters
        )
        if (chunks.isEmpty()) return 0

        val inserted = cityDao.insertCoveredRoadChunks(chunks).count { it != -1L }
        
        if (inserted > 0) {
            cityDao.updateCityExploredLength(city.osmId)
        }
        
        return inserted
    }

    private fun assignStatisticalRoads(
        segments: List<RoadSegment>,
        points: List<RoadPoint>
    ): List<RoadSegment> {
        val pointsByRoadId = points
            .groupBy { it.roadOsmId }
            .mapValues { (_, roadPoints) -> roadPoints.sortedBy { it.orderIndex } }
        val excludedRoadIds = mutableSetOf<Long>()
        val representatives = segments.associate { it.osmId to it.osmId }.toMutableMap()
        val eligibleSegments = segments
            .filter { isRoadEligibleForStats(it.roadType) && !normalizeRoadName(it.name).isNullOrBlank() }
            .sortedByDescending { it.lengthMeters }

        for ((index, baseRoad) in eligibleSegments.withIndex()) {
            if (baseRoad.osmId in excludedRoadIds) continue
            val basePoints = pointsByRoadId[baseRoad.osmId].orEmpty()
            if (basePoints.size < 2) continue

            for (candidateRoad in eligibleSegments.drop(index + 1)) {
                if (candidateRoad.osmId in excludedRoadIds) continue
                val candidatePoints = pointsByRoadId[candidateRoad.osmId].orEmpty()
                if (candidatePoints.size < 2) continue

                if (areLikelyParallelDuplicates(baseRoad, basePoints, candidateRoad, candidatePoints)) {
                    representatives[candidateRoad.osmId] = baseRoad.osmId
                    excludedRoadIds += candidateRoad.osmId
                }
            }
        }

        return segments.map { road ->
            val excluded = !isRoadEligibleForStats(road.roadType) || road.osmId in excludedRoadIds
            road.copy(
                statsRoadOsmId = representatives[road.osmId] ?: road.osmId,
                isStatsExcluded = excluded
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
        firstPoints: List<RoadPoint>,
        second: RoadSegment,
        secondPoints: List<RoadPoint>
    ): Boolean {
        val firstName = normalizeRoadName(first.name) ?: return false
        val secondName = normalizeRoadName(second.name) ?: return false
        if (firstName != secondName) return false
        if (first.roadType != second.roadType) return false

        val lengthRatio = min(first.lengthMeters, second.lengthMeters) /
            max(first.lengthMeters, second.lengthMeters)
        if (lengthRatio < MIN_PARALLEL_LENGTH_RATIO) return false

        val midpointDistanceMeters = haversineDistance(
            firstPoints[firstPoints.lastIndex / 2].latitude,
            firstPoints[firstPoints.lastIndex / 2].longitude,
            secondPoints[secondPoints.lastIndex / 2].latitude,
            secondPoints[secondPoints.lastIndex / 2].longitude
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

    private fun polylineBearingDegrees(points: List<RoadPoint>): Double {
        val start = points.first()
        val end = points.last()
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)

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
        distanceAlongRoadMeters: Double
    ): List<RoadCoverageChunk> {
        val chunkGeometries = buildChunkGeometries(points)
        if (chunkGeometries.isEmpty()) return emptyList()

        val fromMeters = (distanceAlongRoadMeters - COVERAGE_RADIUS_METERS).coerceAtLeast(0.0)
        val toMeters = distanceAlongRoadMeters + COVERAGE_RADIUS_METERS

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
}

