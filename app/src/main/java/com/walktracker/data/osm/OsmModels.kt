package com.spywalker.data.osm

import com.google.gson.annotations.SerializedName

/**
 * Response from Nominatim search API
 */
data class NominatimSearchResult(
    @SerializedName("place_id")
    val placeId: Long,
    
    @SerializedName("osm_id")
    val osmId: Long,
    
    @SerializedName("osm_type")
    val osmType: String, // "relation", "way", "node"
    
    @SerializedName("display_name")
    val displayName: String,
    
    @SerializedName("lat")
    val latitude: String,
    
    @SerializedName("lon")
    val longitude: String,
    
    @SerializedName("boundingbox")
    val boundingBox: List<String>?, // [south, north, west, east]
    
    @SerializedName("geojson")
    val geoJson: GeoJsonGeometry?
)

/**
 * GeoJSON geometry for city boundary polygon
 */
data class GeoJsonGeometry(
    val type: String, // "Polygon", "MultiPolygon"
    val coordinates: Any // Complex nested arrays
)

/**
 * Overpass API response for roads
 */
data class OverpassResponse(
    val version: Float,
    val generator: String,
    val elements: List<OverpassElement>
)

data class OverpassElement(
    val type: String, // "node", "way", "relation"
    val id: Long,
    val lat: Double? = null,  // for nodes
    val lon: Double? = null,  // for nodes
    val nodes: List<Long>? = null, // for ways - list of node IDs
    val geometry: List<OverpassLatLon>? = null, // when using out:geom
    val tags: Map<String, String>? = null
)

data class OverpassLatLon(
    val lat: Double,
    val lon: Double
)

/**
 * Parsed road segment for storage
 */
data class ParsedRoadSegment(
    val osmId: Long,
    val name: String?,
    val roadType: String,
    val points: List<Pair<Double, Double>>, // List of (lat, lon)
    val lengthMeters: Double
)

