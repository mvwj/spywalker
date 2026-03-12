package com.spywalker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for City and RoadSegment operations
 */
@Dao
interface CityDao {
    
    // === Cities ===
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCity(city: City)
    
    @Update
    suspend fun updateCity(city: City)
    
    @Query("SELECT * FROM cities ORDER BY name ASC")
    fun getAllCities(): Flow<List<City>>
    
    @Query("SELECT * FROM cities WHERE isSelected = 1 LIMIT 1")
    fun getSelectedCity(): Flow<City?>
    
    @Query("SELECT * FROM cities WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedCitySync(): City?
    
    @Query("SELECT * FROM cities WHERE osmId = :osmId")
    suspend fun getCityById(osmId: Long): City?
    
    @Query("UPDATE cities SET isSelected = 0")
    suspend fun deselectAllCities()
    
    @Query("UPDATE cities SET isSelected = 1 WHERE osmId = :osmId")
    suspend fun selectCity(osmId: Long)
    
    @Transaction
    suspend fun setSelectedCity(osmId: Long) {
        deselectAllCities()
        selectCity(osmId)
    }
    
    @Query("DELETE FROM cities WHERE osmId = :osmId")
    suspend fun deleteCity(osmId: Long)
    
    // === Road Segments ===
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoadSegments(segments: List<RoadSegment>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoadPoints(points: List<RoadPoint>)
    
    @Query("SELECT * FROM road_segments WHERE cityOsmId = :cityOsmId")
    fun getRoadSegmentsForCity(cityOsmId: Long): Flow<List<RoadSegment>>
    
    @Query("SELECT * FROM road_segments WHERE cityOsmId = :cityOsmId AND isExplored = 1")
    fun getExploredRoadsForCity(cityOsmId: Long): Flow<List<RoadSegment>>

    @Query(
        """
        SELECT * FROM road_segments
        WHERE cityOsmId = :cityOsmId
        AND isStatsExcluded = 0
        AND osmId IN (
            SELECT DISTINCT roadOsmId FROM road_coverage_chunks WHERE cityOsmId = :cityOsmId
        )
        """
    )
    fun getRoadsWithCoveredChunksForCity(cityOsmId: Long): Flow<List<RoadSegment>>

    @Query("SELECT * FROM road_coverage_chunks WHERE cityOsmId = :cityOsmId")
    fun getCoveredRoadChunksForCity(cityOsmId: Long): Flow<List<RoadCoverageChunk>>

    @Query("SELECT * FROM road_coverage_chunks")
    fun getAllCoveredRoadChunks(): Flow<List<RoadCoverageChunk>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCoveredRoadChunks(chunks: List<RoadCoverageChunk>): List<Long>

    @Query("SELECT DISTINCT cityOsmId FROM road_coverage_chunks WHERE sessionId = :sessionId")
    suspend fun getCoveredCityIdsForSession(sessionId: Long): List<Long>
    
    @Query("SELECT * FROM road_points WHERE roadOsmId = :roadOsmId ORDER BY orderIndex")
    suspend fun getRoadPoints(roadOsmId: Long): List<RoadPoint>
    
    @Query("SELECT COUNT(*) FROM road_segments WHERE cityOsmId = :cityOsmId AND isStatsExcluded = 0")
    fun getTotalRoadsCount(cityOsmId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM road_segments WHERE cityOsmId = :cityOsmId AND isExplored = 1")
    fun getExploredRoadsCount(cityOsmId: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(DISTINCT chunks.roadOsmId)
        FROM road_coverage_chunks AS chunks
        INNER JOIN road_segments AS roads ON roads.osmId = chunks.roadOsmId
        WHERE chunks.cityOsmId = :cityOsmId
        AND roads.isStatsExcluded = 0
        """
    )
    fun getExploredRoadsCountFromChunks(cityOsmId: Long): Flow<Int>
    
    @Query("SELECT SUM(lengthMeters) FROM road_segments WHERE cityOsmId = :cityOsmId AND isStatsExcluded = 0")
    fun getTotalRoadLength(cityOsmId: Long): Flow<Double?>
    
    @Query("SELECT SUM(lengthMeters) FROM road_segments WHERE cityOsmId = :cityOsmId AND isExplored = 1")
    fun getExploredRoadLength(cityOsmId: Long): Flow<Double?>

    @Query(
        """
        SELECT COALESCE(SUM(lengthMeters), 0) FROM (
            SELECT chunks.roadOsmId, chunks.chunkIndex, MAX(chunks.lengthMeters) AS lengthMeters
            FROM road_coverage_chunks AS chunks
            INNER JOIN road_segments AS roads ON roads.osmId = chunks.roadOsmId
            WHERE chunks.cityOsmId = :cityOsmId
            AND roads.isStatsExcluded = 0
            GROUP BY chunks.roadOsmId, chunks.chunkIndex
        )
        """
    )
    fun getExploredRoadLengthFromChunks(cityOsmId: Long): Flow<Double?>
    
    /**
     * Find road segments near a GPS point (within bounding box check)
     * More precise matching should be done in Kotlin
     */
    @Query("""
        SELECT * FROM road_segments 
        WHERE cityOsmId = :cityOsmId 
        AND isStatsExcluded = 0
        AND minLat <= :lat + :tolerance 
        AND maxLat >= :lat - :tolerance
        AND minLon <= :lon + :tolerance 
        AND maxLon >= :lon - :tolerance
        AND isExplored = 0
    """)
    suspend fun findNearbyUnexploredRoads(
        cityOsmId: Long,
        lat: Double,
        lon: Double,
        tolerance: Double = 0.0005 // ~50 meters
    ): List<RoadSegment>

    @Query("""
        SELECT * FROM road_segments
        WHERE cityOsmId = :cityOsmId
        AND isStatsExcluded = 0
        AND minLat <= :lat + :tolerance
        AND maxLat >= :lat - :tolerance
        AND minLon <= :lon + :tolerance
        AND maxLon >= :lon - :tolerance
    """)
    suspend fun findNearbyRoads(
        cityOsmId: Long,
        lat: Double,
        lon: Double,
        tolerance: Double = 0.0005
    ): List<RoadSegment>
    
    @Query("UPDATE road_segments SET isExplored = 1, exploredAt = :timestamp, exploredBySessionId = :sessionId WHERE osmId = :roadOsmId")
    suspend fun markRoadAsExplored(roadOsmId: Long, timestamp: Long, sessionId: Long)
    
    /**
     * Update city statistics after road exploration
     */
    @Query("""
        UPDATE cities 
        SET exploredRoadLengthMeters = (
            SELECT COALESCE(SUM(lengthMeters), 0) FROM (
                SELECT chunks.roadOsmId, chunks.chunkIndex, MAX(chunks.lengthMeters) AS lengthMeters
                FROM road_coverage_chunks AS chunks
                INNER JOIN road_segments AS roads ON roads.osmId = chunks.roadOsmId
                WHERE chunks.cityOsmId = :cityOsmId
                AND roads.isStatsExcluded = 0
                GROUP BY chunks.roadOsmId, chunks.chunkIndex
            )
        ),
        lastUpdatedAt = :timestamp
        WHERE osmId = :cityOsmId
    """)
    suspend fun updateCityExploredLength(cityOsmId: Long, timestamp: Long = System.currentTimeMillis())
}

