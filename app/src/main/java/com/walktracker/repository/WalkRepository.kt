package com.spywalker.repository

import com.spywalker.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalkRepository @Inject constructor(
    private val walkDao: WalkDao,
    private val cityDao: CityDao
) {
    companion object {
        private const val MAX_USABLE_ACCURACY_METERS = 35f
        private const val MIN_SIGNIFICANT_MOVE_METERS = 3.0
        private const val MAX_REASONABLE_WALKING_SPEED_MPS = 3.2
    }

    // === Ð¡ÐµÑÑÐ¸Ð¸ ===
    
    suspend fun startNewSession(): Long {
        val now = System.currentTimeMillis()
        walkDao.endActiveSessions(now)
        val session = WalkSession(startTime = now)
        return walkDao.insertSession(session)
    }
    
    suspend fun endSession(sessionId: Long) {
        walkDao.getSession(sessionId)?.let { session ->
            walkDao.updateSession(session.copy(endTime = System.currentTimeMillis()))
        }
    }

    suspend fun deleteSession(sessionId: Long) {
        val affectedCityIds = cityDao.getCoveredCityIdsForSession(sessionId)
        walkDao.deleteSession(sessionId)
        affectedCityIds.forEach { cityDao.updateCityExploredLength(it) }
    }
    
    fun getAllSessions(): Flow<List<WalkSession>> = walkDao.getAllSessions()

    fun getAllSessionSummaries(): Flow<List<WalkSessionSummary>> = combine(
        walkDao.getAllSessions(),
        walkDao.getAllPoints(),
        cityDao.getAllCoveredRoadChunks()
    ) { sessions, points, chunks ->
        val pointsBySession = points.groupBy { it.sessionId }
        val chunksBySession = chunks.groupBy { it.sessionId }
        val now = System.currentTimeMillis()

        sessions.map { session ->
            val sessionPoints = pointsBySession[session.id].orEmpty().sortedBy { it.timestamp }
            val sessionChunks = chunksBySession[session.id].orEmpty()

            WalkSessionSummary(
                sessionId = session.id,
                startTime = session.startTime,
                endTime = session.endTime,
                durationMs = (session.endTime ?: now) - session.startTime,
                distanceKm = calculateSessionDistanceKm(sessionPoints),
                coveredRoadKm = sessionChunks
                    .distinctBy { it.roadOsmId to it.chunkIndex }
                    .sumOf { it.lengthMeters } / 1000.0,
                pointsCount = sessionPoints.size,
                isActive = session.endTime == null
            )
        }.sortedByDescending { it.startTime }
    }
    
    // === Ð¢Ð¾Ñ‡ÐºÐ¸ Ð¼Ð°Ñ€ÑˆÑ€ÑƒÑ‚Ð° ===
    
    suspend fun addPoint(sessionId: Long, latitude: Double, longitude: Double, accuracy: Float) {
        val point = WalkPoint(
            sessionId = sessionId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy
        )
        walkDao.insertPoint(point)
    }
    
    fun getPointsForSession(sessionId: Long): Flow<List<WalkPoint>> = 
        walkDao.getPointsForSession(sessionId)
    
    fun getAllPoints(): Flow<List<WalkPoint>> = walkDao.getAllPoints()
    
    // === Ð¡Ñ‚Ð°Ñ‚Ð¸ÑÑ‚Ð¸ÐºÐ° ===
    
    fun getTotalPointsCount(): Flow<Int> = walkDao.getTotalPointsCount()
    fun getTotalSessionsCount(): Flow<Int> = walkDao.getTotalSessionsCount()
    fun getTotalWalkingTimeMs(): Flow<Long> = walkDao.getTotalWalkingTimeMs().map { it ?: 0L }
    
    /**
     * Calculate total distance in km using Haversine formula.
     * Calculated in Kotlin because SQLite doesn't support trig functions.
     */
    fun getTotalDistanceKm(): Flow<Double> = walkDao.getAllPoints().map { points ->
        calculateTotalDistance(points)
    }
    
    private fun calculateTotalDistance(points: List<WalkPoint>): Double {
        if (points.size < 2) return 0.0

        return points
            .groupBy { it.sessionId }
            .values
            .sumOf { sessionPoints -> calculateSessionDistanceKm(sessionPoints) }
    }

    private fun calculateSessionDistanceKm(points: List<WalkPoint>): Double {
        if (points.size < 2) return 0.0

        val sortedPoints = points.sortedBy { it.timestamp }
        var previousAccepted = sortedPoints.firstOrNull { it.accuracy <= MAX_USABLE_ACCURACY_METERS }
            ?: sortedPoints.first()
        var totalDistanceKm = 0.0

        for (current in sortedPoints.dropWhile { it.id != previousAccepted.id }.drop(1)) {
            if (current.accuracy > MAX_USABLE_ACCURACY_METERS) continue

            val distanceKm = haversineDistance(
                previousAccepted.latitude,
                previousAccepted.longitude,
                current.latitude,
                current.longitude
            )
            val distanceMeters = distanceKm * 1000.0
            val timeDeltaSeconds = ((current.timestamp - previousAccepted.timestamp) / 1000.0)
                .coerceAtLeast(1.0)
            val speedMps = distanceMeters / timeDeltaSeconds

            if (distanceMeters < MIN_SIGNIFICANT_MOVE_METERS) {
                previousAccepted = current
                continue
            }

            if (speedMps > MAX_REASONABLE_WALKING_SPEED_MPS) {
                continue
            }

            totalDistanceKm += distanceKm
            previousAccepted = current
        }

        return totalDistanceKm
    }
    
    /**
     * Haversine formula to calculate distance between two GPS points in km
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // Earth's radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
    
}

