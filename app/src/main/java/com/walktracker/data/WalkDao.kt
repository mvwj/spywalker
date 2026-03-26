package com.spywalker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WalkDao {
    
    // === Ð¡ÐµÑÑÐ¸Ð¸ ===
    
    @Insert
    suspend fun insertSession(session: WalkSession): Long
    
    @Update
    suspend fun updateSession(session: WalkSession)
    
    @Query("SELECT * FROM walk_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<WalkSession>>
    
    @Query("SELECT * FROM walk_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: Long): WalkSession?

    @Query("UPDATE walk_sessions SET endTime = :endTime WHERE endTime IS NULL")
    suspend fun endActiveSessions(endTime: Long)

    @Query("DELETE FROM walk_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
    
    // === Ð¢Ð¾Ñ‡ÐºÐ¸ Ð¼Ð°Ñ€ÑˆÑ€ÑƒÑ‚Ð° ===
    
    @Insert
    suspend fun insertPoint(point: WalkPoint)
    
    @Insert
    suspend fun insertPoints(points: List<WalkPoint>)
    
    @Query("SELECT * FROM walk_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getPointsForSession(sessionId: Long): Flow<List<WalkPoint>>
    
    @Query("SELECT * FROM walk_points ORDER BY timestamp ASC")
    fun getAllPoints(): Flow<List<WalkPoint>>
    
    // === Ð¡Ñ‚Ð°Ñ‚Ð¸ÑÑ‚Ð¸ÐºÐ° ===
    
    @Query("SELECT COUNT(*) FROM walk_points")
    fun getTotalPointsCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM walk_sessions")
    fun getTotalSessionsCount(): Flow<Int>
    
    // === ÐŸÐ¾ÐºÑ€Ñ‹Ñ‚Ð¸Ðµ Ð³Ð¾Ñ€Ð¾Ð´Ð° ===
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExploredCell(cell: ExploredCell): Long
    
    @Query("SELECT * FROM explored_cells")
    fun getAllExploredCells(): Flow<List<ExploredCell>>
    
    @Query("SELECT COUNT(*) FROM explored_cells")
    fun getExploredCellsCount(): Flow<Int>
    
    @Query("SELECT EXISTS(SELECT 1 FROM explored_cells WHERE gridX = :gridX AND gridY = :gridY)")
    suspend fun isCellExplored(gridX: Int, gridY: Int): Boolean
    
    // === Ð“Ñ€Ð°Ð½Ð¸Ñ†Ñ‹ Ð³Ð¾Ñ€Ð¾Ð´Ð° ===
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCityBounds(bounds: CityBounds)
    
    @Query("SELECT * FROM city_bounds WHERE id = 1")
    fun getCityBounds(): Flow<CityBounds?>
    
    @Query("SELECT * FROM city_bounds WHERE id = 1")
    suspend fun getCityBoundsSync(): CityBounds?
    
    // === Ð Ð°ÑÑˆÐ¸Ñ€ÐµÐ½Ð½Ð°Ñ ÑÑ‚Ð°Ñ‚Ð¸ÑÑ‚Ð¸ÐºÐ° ===
    
    @Query("SELECT SUM(CASE WHEN endTime IS NOT NULL THEN endTime - startTime ELSE 0 END) FROM walk_sessions")
    fun getTotalWalkingTimeMs(): Flow<Long?>
    
    // Distance calculation is done in Kotlin, not SQL, because SQLite doesn't support 
    // mathematical functions like POW, SQRT, COS, RADIANS
}


