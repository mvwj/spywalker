package com.spywalker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a grid cell for city coverage tracking.
 * The city is divided into a grid of cells (approximately 50m x 50m).
 * When a user walks through a cell, it becomes "explored".
 * 
 * Grid cell coordinates are calculated by dividing lat/lng by CELL_SIZE.
 */
@Entity(
    tableName = "explored_cells",
    indices = [Index(value = ["gridX", "gridY"], unique = true)]
)
data class ExploredCell(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Grid coordinates (lat/lng divided by cell size and floored)
    val gridX: Int,
    val gridY: Int,
    
    // Actual center coordinates for display
    val centerLatitude: Double,
    val centerLongitude: Double,
    
    // When this cell was first explored
    val exploredAt: Long = System.currentTimeMillis(),
    
    // Session that first explored this cell
    val sessionId: Long
) {
    companion object {
        // Cell size in degrees (approximately 50 meters)
        // 1 degree latitude â‰ˆ 111km, so 0.00045 â‰ˆ 50m
        const val CELL_SIZE = 0.00045
        
        /**
         * Calculate grid coordinates from GPS coordinates
         */
        fun calculateGridX(longitude: Double): Int = 
            (longitude / CELL_SIZE).toInt()
        
        fun calculateGridY(latitude: Double): Int = 
            (latitude / CELL_SIZE).toInt()
        
        /**
         * Calculate center coordinates from grid coordinates
         */
        fun calculateCenterLng(gridX: Int): Double = 
            (gridX + 0.5) * CELL_SIZE
        
        fun calculateCenterLat(gridY: Int): Double = 
            (gridY + 0.5) * CELL_SIZE
    }
}

/**
 * City boundary for coverage calculation.
 * Stores the bounding box of the city/area being tracked.
 */
@Entity(tableName = "city_bounds")
data class CityBounds(
    @PrimaryKey
    val id: Int = 1, // Only one city at a time
    
    val name: String = "Мой город",
    
    // Bounding box
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
    
    // Total estimated cells in this area (for percentage calculation)
    val totalCells: Int,
    
    // Last updated
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Calculate total cells in this bounding box
     */
    companion object {
        fun calculateTotalCells(
            minLat: Double, maxLat: Double,
            minLng: Double, maxLng: Double
        ): Int {
            val latCells = ((maxLat - minLat) / ExploredCell.CELL_SIZE).toInt()
            val lngCells = ((maxLng - minLng) / ExploredCell.CELL_SIZE).toInt()
            return latCells * lngCells
        }
    }
}

