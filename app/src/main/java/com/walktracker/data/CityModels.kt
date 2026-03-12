package com.spywalker.data

import androidx.room.*

/**
 * City entity with boundary information
 */
@Entity(tableName = "cities")
data class City(
    @PrimaryKey
    val osmId: Long,
    
    val name: String,
    val displayName: String,
    
    // Bounding box for the city
    val boundsSouth: Double,
    val boundsNorth: Double,
    val boundsWest: Double,
    val boundsEast: Double,
    
    // Center point
    val centerLat: Double,
    val centerLon: Double,
    
    // Statistics
    val totalRoadSegments: Int = 0,
    val totalRoadLengthMeters: Double = 0.0,
    val exploredRoadLengthMeters: Double = 0.0,
    
    // Timestamps
    val downloadedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    
    // Is this the currently selected city?
    val isSelected: Boolean = false
)

/**
 * Road segment from OpenStreetMap
 */
@Entity(
    tableName = "road_segments",
    indices = [
        Index(value = ["cityOsmId"]),
        Index(value = ["osmId"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = City::class,
            parentColumns = ["osmId"],
            childColumns = ["cityOsmId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RoadSegment(
    @PrimaryKey
    val osmId: Long,
    
    val cityOsmId: Long,
    
    val name: String?,
    val roadType: String, // primary, secondary, residential, footway, etc.
    
    // Simplified bounding box for quick spatial queries
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    
    // Road length in meters
    val lengthMeters: Double,
    
    // Is this road explored?
    val isExplored: Boolean = false,
    val exploredAt: Long? = null,
    val exploredBySessionId: Long? = null
)

/**
 * Store road geometry points separately for efficient storage
 * Each road can have many points
 */
@Entity(
    tableName = "road_points",
    indices = [Index(value = ["roadOsmId"])],
    foreignKeys = [
        ForeignKey(
            entity = RoadSegment::class,
            parentColumns = ["osmId"],
            childColumns = ["roadOsmId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RoadPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val roadOsmId: Long,
    val orderIndex: Int, // Order of points in the road
    val latitude: Double,
    val longitude: Double
)

/**
 * Fixed-size covered fragment of a road, linked to the walk session that covered it.
 * This allows partial road coverage, correct rollback on walk deletion,
 * and rendering the painted road itself rather than a raw GPS line.
 */
@Entity(
    tableName = "road_coverage_chunks",
    indices = [
        Index(value = ["cityOsmId"]),
        Index(value = ["roadOsmId"]),
        Index(value = ["sessionId"]),
        Index(value = ["roadOsmId", "sessionId", "chunkIndex"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = City::class,
            parentColumns = ["osmId"],
            childColumns = ["cityOsmId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RoadSegment::class,
            parentColumns = ["osmId"],
            childColumns = ["roadOsmId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WalkSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RoadCoverageChunk(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cityOsmId: Long,
    val roadOsmId: Long,
    val sessionId: Long,
    val chunkIndex: Int,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double,
    val endLongitude: Double,
    val lengthMeters: Double,
    val coveredAt: Long = System.currentTimeMillis()
)

