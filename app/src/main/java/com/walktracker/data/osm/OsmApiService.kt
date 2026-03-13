package com.spywalker.data.osm

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Headers

/**
 * Nominatim API for searching cities and getting boundaries
 * https://nominatim.openstreetmap.org/
 */
interface NominatimApi {
    
    @GET("search")
    @Headers("User-Agent: SpyWalker/1.0")
    suspend fun searchCity(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("polygon_geojson") polygonGeoJson: Int = 0,
        @Query("limit") limit: Int = 10,
        @Query("addressdetails") addressDetails: Int = 1
    ): List<NominatimSearchResult>
}

/**
 * Overpass API for fetching road network
 * https://overpass-api.de/
 */
interface OverpassApi {
    @FormUrlEncoded
    @POST("interpreter")
    @Headers(
        "Content-Type: application/x-www-form-urlencoded",
        "Accept: application/json",
        "User-Agent: SpyWalker/1.0"
    )
    suspend fun queryRoads(
        @Field("data") query: String
    ): OverpassResponse
}

/**
 * Query builder for Overpass API
 */
object OverpassQueryBuilder {
    
    /**
     * Build query to fetch all public walkable roads within a bounding box
     * Excludes private roads, driveways, parking aisles
     */
    fun buildRoadsQuery(
        south: Double,
        west: Double, 
        north: Double,
        east: Double
    ): String {
        return """
            [out:json][timeout:180];
            (
              way["highway"="primary"](${south},${west},${north},${east});
              way["highway"="secondary"](${south},${west},${north},${east});
              way["highway"="tertiary"](${south},${west},${north},${east});
              way["highway"="residential"](${south},${west},${north},${east});
              way["highway"="living_street"](${south},${west},${north},${east});
              way["highway"="unclassified"](${south},${west},${north},${east});
              way["highway"="pedestrian"](${south},${west},${north},${east});
            );
            out geom;
        """.trimIndent()
    }
    
    /**
     * Build query for city boundary polygon
     */
    fun buildCityBoundaryQuery(osmId: Long): String {
        return """
            [out:json][timeout:60];
            relation(${osmId});
            out geom;
        """.trimIndent()
    }
}

