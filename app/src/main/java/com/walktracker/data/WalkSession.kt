package com.spywalker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ð¡ÐµÑÑÐ¸Ñ Ð¿Ñ€Ð¾Ð³ÑƒÐ»ÐºÐ¸ - Ð³Ñ€ÑƒÐ¿Ð¿Ð¸Ñ€ÑƒÐµÑ‚ Ð²ÑÐµ Ñ‚Ð¾Ñ‡ÐºÐ¸ Ð¾Ð´Ð½Ð¾Ð¹ Ð¿Ñ€Ð¾Ð³ÑƒÐ»ÐºÐ¸
 */
@Entity(tableName = "walk_sessions")
data class WalkSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null
)

data class WalkSessionSummary(
    val sessionId: Long,
    val startTime: Long,
    val endTime: Long?,
    val durationMs: Long,
    val distanceKm: Double,
    val coveredRoadKm: Double,
    val pointsCount: Int,
    val isActive: Boolean
)

