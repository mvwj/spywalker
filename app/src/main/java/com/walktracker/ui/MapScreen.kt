package com.spywalker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.spywalker.R
import com.spywalker.data.City
import com.spywalker.data.RoadCoverageChunk
import com.spywalker.data.WalkPoint
import com.spywalker.data.WalkSessionSummary
import com.spywalker.repository.SuggestedCityDownload
import com.spywalker.service.CurrentLocationSnapshot
import com.spywalker.ui.theme.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    uiState: MapUiState,
    isCityDownloading: Boolean,
    cityDownloadProgress: Float,
    cityDownloadStatus: String,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onMapZoomChange: (Double) -> Unit,
    onCitySelectionClick: () -> Unit,
    onToggleCoverageStats: () -> Unit,
    onToggleZoomControl: () -> Unit,
    onFocusCurrentLocation: () -> Unit,
    onShowWalks: () -> Unit,
    onHideWalks: () -> Unit,
    isSettingsVisible: Boolean,
    selectedLanguage: String,
    onShowSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onPreviewWalkRoute: (WalkSessionSummary) -> Unit,
    onDismissWalkRoutePreview: () -> Unit,
    onStopWalkSession: (Long) -> Unit,
    onDeleteWalk: (Long) -> Unit,
    onDownloadSuggestedCity: (SuggestedCityDownload) -> Unit,
    onDismissSuggestedCity: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // OSMDroid карта
        OsmMapView(
            selectedCity = uiState.selectedCity,
            coveredRoadChunks = uiState.coveredRoadChunks,
            currentLocation = uiState.currentLocation,
            mapZoomLevel = uiState.mapZoomLevel,
            focusRequestId = uiState.focusRequestId,
            focusZoomLevel = uiState.focusZoomLevel,
            showWeakSignalProximity = uiState.isWeakGpsSignal,
            onMapZoomChange = onMapZoomChange,
            modifier = Modifier.fillMaxSize()
        )
        
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoverageStatsCard(
                selectedCity = uiState.selectedCity,
                coveragePercentage = uiState.coveragePercentage,
                totalRoadsCount = uiState.totalRoadsCount,
                exploredRoadsCount = uiState.exploredRoadsCount,
                totalRoadLengthKm = uiState.totalRoadLengthKm,
                exploredRoadLengthKm = uiState.exploredRoadLengthKm,
                totalSessions = uiState.totalSessions,
                totalDistanceKm = uiState.totalDistanceKm,
                isTracking = uiState.isTracking,
                currentPoints = uiState.currentPointsCount,
                isExpanded = uiState.isCoverageStatsExpanded,
                onCityClick = onCitySelectionClick,
                onToggleExpanded = onToggleCoverageStats,
                modifier = Modifier.fillMaxWidth()
            )

            SuggestedCityDownloadCard(
                suggestion = uiState.suggestedCityDownload,
                isDownloading = isCityDownloading,
                downloadProgress = cityDownloadProgress,
                downloadStatus = cityDownloadStatus,
                onDownload = onDownloadSuggestedCity,
                onDismiss = onDismissSuggestedCity
            )
        }
        
        // Кнопка Start/Stop
        PremiumTrackingButton(
            isTracking = uiState.isTracking,
            onStartTracking = onStartTracking,
            onStopTracking = onStopTracking,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .navigationBarsPadding()
        )

        WeakGpsSignalCard(
            isVisible = uiState.isWeakGpsSignal,
            weakGpsAccuracyMeters = uiState.weakGpsAccuracyMeters,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 136.dp)
                .navigationBarsPadding()
        )

        MapActionButtons(
            mapZoomLevel = uiState.mapZoomLevel,
            isZoomControlVisible = uiState.isZoomControlVisible,
            onZoomChange = onMapZoomChange,
            onToggleZoomControl = onToggleZoomControl,
            onFocusCurrentLocation = onFocusCurrentLocation,
            onShowSettings = onShowSettings,
            onShowWalks = onShowWalks,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 40.dp)
                .navigationBarsPadding()
        )

        if (isSettingsVisible) {
            SettingsDialog(
                selectedLanguage = selectedLanguage,
                onDismiss = onDismissSettings,
                onSelectLanguage = onSelectLanguage
            )
        }

        if (uiState.showWalkSessions) {
            WalkSessionsSheet(
                sessions = uiState.sessionSummaries,
                selectedWalkRoutePreview = uiState.selectedWalkRoutePreview,
                onDismiss = onHideWalks,
                onPreviewWalkRoute = onPreviewWalkRoute,
                onDismissWalkRoutePreview = onDismissWalkRoutePreview,
                onStopWalkSession = onStopWalkSession,
                onDeleteWalk = onDeleteWalk
            )
        }
    }
}

@Composable
private fun SuggestedCityDownloadCard(
    suggestion: SuggestedCityDownload?,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadStatus: String,
    onDownload: (SuggestedCityDownload) -> Unit,
    onDismiss: () -> Unit
) {
    if (suggestion == null && !isDownloading) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.96f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.LocationCity,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.current_city_not_downloaded_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextOnDark,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (!isDownloading) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.later_action))
                    }
                }
            }

            if (suggestion != null) {
                Text(
                    text = stringResource(R.string.current_city_not_downloaded_message, suggestion.cityName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextOnDarkSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = downloadProgress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = Primary,
                    trackColor = DarkSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = downloadStatus.ifBlank { stringResource(R.string.loading_action) },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextOnDarkSecondary
                )
            } else if (suggestion != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { onDownload(suggestion) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.download_current_city_action, suggestion.cityName))
                }
            }
        }
    }
}

@Composable
fun OsmMapView(
    selectedCity: City?,
    coveredRoadChunks: List<RoadCoverageChunk>,
    currentLocation: CurrentLocationSnapshot?,
    mapZoomLevel: Double,
    focusRequestId: Int,
    focusZoomLevel: Double,
    showWeakSignalProximity: Boolean,
    onMapZoomChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val locationMarkerIcon = remember(context) { createCurrentLocationMarkerDrawable(context) }
    val latestOnMapZoomChange by rememberUpdatedState(onMapZoomChange)
    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(5.0)
            // Нейтральная стартовая позиция до выбора города
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }
    
    // Центрируем на выбранном городе или последней точке
    LaunchedEffect(selectedCity) {
        selectedCity?.let {
            mapView.controller.animateTo(GeoPoint(it.centerLat, it.centerLon))
        }
    }

    LaunchedEffect(mapZoomLevel, currentLocation) {
        if (abs(mapView.zoomLevelDouble - mapZoomLevel) > 0.05) {
            currentLocation?.let { location ->
                mapView.controller.setCenter(GeoPoint(location.latitude, location.longitude))
            }
            mapView.controller.setZoom(mapZoomLevel)
        }
    }

    LaunchedEffect(coveredRoadChunks, currentLocation, showWeakSignalProximity) {
        mapView.overlays.clear()

        coveredRoadChunks
            .distinctBy { it.roadOsmId to it.chunkIndex }
            .forEach { chunk ->
                val polyline = Polyline().apply {
                    outlinePaint.color = Color.parseColor("#00BFA6")
                    outlinePaint.strokeWidth = 16f
                    outlinePaint.alpha = 235
                    outlinePaint.style = Paint.Style.STROKE
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    setPoints(
                        listOf(
                            GeoPoint(chunk.startLatitude, chunk.startLongitude),
                            GeoPoint(chunk.endLatitude, chunk.endLongitude)
                        )
                    )
                }
                mapView.overlays.add(polyline)
            }

        currentLocation?.let { location ->
            val geoPoint = GeoPoint(location.latitude, location.longitude)

            if (showWeakSignalProximity) {
                val proximityRadiusMeters = location.accuracy
                    .toDouble()
                    .coerceIn(10.0, 40.0)

                val accuracyCircle = Polygon().apply {
                    points = Polygon.pointsAsCircle(geoPoint, proximityRadiusMeters)
                    fillPaint.color = Color.parseColor("#33FFB74D")
                    fillPaint.style = Paint.Style.FILL
                    outlinePaint.color = Color.parseColor("#CCFFB74D")
                    outlinePaint.strokeWidth = 3f
                }
                mapView.overlays.add(accuracyCircle)
            }

            val currentLocationMarker = Marker(mapView).apply {
                position = geoPoint
                icon = locationMarkerIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Current location"
            }
            mapView.overlays.add(currentLocationMarker)
        }

        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        val mapListener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                event?.zoomLevel?.let(latestOnMapZoomChange)
                return false
            }
        }

        mapView.addMapListener(mapListener)

        onDispose {
            mapView.removeMapListener(mapListener)
            mapView.onDetach()
        }
    }

    LaunchedEffect(focusRequestId) {
        if (focusRequestId > 0) {
            currentLocation?.let { location ->
                mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                mapView.controller.setZoom(focusZoomLevel)
            }
        }
    }
    
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { }
    )
}

@Composable
private fun MapActionButtons(
    mapZoomLevel: Double,
    isZoomControlVisible: Boolean,
    onZoomChange: (Double) -> Unit,
    onToggleZoomControl: () -> Unit,
    onFocusCurrentLocation: () -> Unit,
    onShowSettings: () -> Unit,
    onShowWalks: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End
    ) {
        SmallFloatingActionButton(onClick = onToggleZoomControl) {
            Icon(
                imageVector = if (isZoomControlVisible) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = stringResource(
                    if (isZoomControlVisible) {
                        R.string.hide_zoom_control_action
                    } else {
                        R.string.show_zoom_control_action
                    }
                )
            )
        }
        AnimatedVisibility(
            visible = isZoomControlVisible,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            VerticalZoomControl(
                zoomLevel = mapZoomLevel,
                onZoomChange = onZoomChange
            )
        }
        SmallFloatingActionButton(onClick = onFocusCurrentLocation) {
            Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.focus_location_action))
        }
        SmallFloatingActionButton(onClick = onShowSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.app_settings_action))
        }
        SmallFloatingActionButton(onClick = onShowWalks) {
            Icon(
                painter = painterResource(R.drawable.ic_analytics),
                contentDescription = stringResource(R.string.walk_history_action)
            )
        }
    }
}

@Composable
private fun WeakGpsSignalCard(
    isVisible: Boolean,
    weakGpsAccuracyMeters: Float?,
    modifier: Modifier = Modifier
) {
    if (!isVisible || weakGpsAccuracyMeters == null) return

    Card(
        modifier = modifier.widthIn(max = 220.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCard.copy(alpha = 0.96f)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.weak_gps_title),
                    color = TextOnDark,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.weak_gps_message, weakGpsAccuracyMeters),
                    color = TextOnDarkSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    selectedLanguage: String,
    onDismiss: () -> Unit,
    onSelectLanguage: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.settings_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.language_settings_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextOnDark
                )

                LanguageOptionRow(
                    label = stringResource(R.string.language_russian),
                    selected = selectedLanguage == "ru",
                    onClick = { onSelectLanguage("ru") }
                )
                LanguageOptionRow(
                    label = stringResource(R.string.language_english),
                    selected = selectedLanguage == "en",
                    onClick = { onSelectLanguage("en") }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close_action))
            }
        },
        containerColor = DarkCard,
        textContentColor = TextOnDark,
        titleContentColor = TextOnDark
    )
}

@Composable
private fun LanguageOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = TextOnDark,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun VerticalZoomControl(
    zoomLevel: Double,
    onZoomChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val sliderValue = zoomLevel.toFloat().coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.94f)),
        shape = RoundedCornerShape(22.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = DarkSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .height(230.dp)
                    .width(56.dp),
                contentAlignment = Alignment.Center
            ) {
                FullHeightVerticalZoomSlider(
                    value = sliderValue,
                    onValueChange = { onZoomChange(it.toDouble()) },
                    valueRange = MIN_ZOOM_LEVEL..MAX_ZOOM_LEVEL,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun FullHeightVerticalZoomSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    var sliderHeightPx by remember { mutableStateOf(0) }
    val clampedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val normalizedValue = ((clampedValue - valueRange.start) /
        (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .onSizeChanged { sliderHeightPx = it.height }
            .pointerInput(valueRange, sliderHeightPx) {
                if (sliderHeightPx == 0) return@pointerInput

                detectTapGestures { offset ->
                    onValueChange(
                        offsetToZoomValue(
                            y = offset.y,
                            heightPx = sliderHeightPx.toFloat(),
                            valueRange = valueRange
                        )
                    )
                }
            }
            .pointerInput(valueRange, sliderHeightPx) {
                if (sliderHeightPx == 0) return@pointerInput

                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        onValueChange(
                            offsetToZoomValue(
                                y = offset.y,
                                heightPx = sliderHeightPx.toFloat(),
                                valueRange = valueRange
                            )
                        )
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onValueChange(
                            offsetToZoomValue(
                                y = change.position.y,
                                heightPx = sliderHeightPx.toFloat(),
                                valueRange = valueRange
                            )
                        )
                    }
                )
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            val trackWidth = 6.dp.toPx()
            val thumbRadius = 10.dp.toPx()
            val centerX = size.width / 2f
            val trackTop = thumbRadius
            val trackBottom = size.height - thumbRadius
            val trackHeight = (trackBottom - trackTop).coerceAtLeast(1f)
            val thumbCenterY = trackBottom - (normalizedValue * trackHeight)

            drawLine(
                color = ComposeColor.White.copy(alpha = 0.18f),
                start = Offset(centerX, trackTop),
                end = Offset(centerX, trackBottom),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )

            drawLine(
                color = Primary,
                start = Offset(centerX, thumbCenterY),
                end = Offset(centerX, trackBottom),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round
            )

            drawCircle(
                color = Primary.copy(alpha = 0.22f),
                radius = thumbRadius * 1.65f,
                center = Offset(centerX, thumbCenterY)
            )
            drawCircle(
                color = Primary,
                radius = thumbRadius,
                center = Offset(centerX, thumbCenterY)
            )
            drawCircle(
                color = ComposeColor.White,
                radius = thumbRadius * 0.38f,
                center = Offset(centerX, thumbCenterY)
            )
        }

    }
}

private const val MIN_ZOOM_LEVEL = 5f
private const val MAX_ZOOM_LEVEL = 18f

private fun createCurrentLocationMarkerDrawable(context: Context): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (28 * density).toInt().coerceAtLeast(28)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7")
        style = Paint.Style.FILL
    }

    val center = sizePx / 2f
    canvas.drawCircle(center, center, sizePx * 0.36f, outerPaint)
    canvas.drawCircle(center, center, sizePx * 0.24f, innerPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createRouteEndpointMarkerDrawable(context: Context, fillColor: Int): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (22 * density).toInt().coerceAtLeast(22)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }

    val center = sizePx / 2f
    canvas.drawCircle(center, center, sizePx * 0.42f, outerPaint)
    canvas.drawCircle(center, center, sizePx * 0.28f, innerPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun createBoundingBox(points: List<GeoPoint>): BoundingBox {
    val maxLat = points.maxOf { it.latitude }
    val minLat = points.minOf { it.latitude }
    val maxLon = points.maxOf { it.longitude }
    val minLon = points.minOf { it.longitude }

    val latPadding = ((maxLat - minLat) * 0.15).coerceAtLeast(0.001)
    val lonPadding = ((maxLon - minLon) * 0.15).coerceAtLeast(0.001)

    return BoundingBox(
        maxLat + latPadding,
        maxLon + lonPadding,
        minLat - latPadding,
        minLon - lonPadding
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun WalkSessionsSheet(
    sessions: List<WalkSessionSummary>,
    selectedWalkRoutePreview: WalkRoutePreview?,
    onDismiss: () -> Unit,
    onPreviewWalkRoute: (WalkSessionSummary) -> Unit,
    onDismissWalkRoutePreview: () -> Unit,
    onStopWalkSession: (Long) -> Unit,
    onDeleteWalk: (Long) -> Unit
) {
    var pendingDeleteSessionId by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        contentColor = TextOnDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.walk_history_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.walk_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextOnDarkSecondary,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        WalkSessionCard(
                            session = session,
                            onClick = { onPreviewWalkRoute(session) },
                            onStopWalkSession = { onStopWalkSession(session.sessionId) },
                            onDelete = { pendingDeleteSessionId = session.sessionId }
                        )
                    }
                }
            }
        }
    }

    selectedWalkRoutePreview?.let { routePreview ->
        WalkRoutePreviewDialog(
            routePreview = routePreview,
            onDismiss = onDismissWalkRoutePreview
        )
    }

    pendingDeleteSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSessionId = null },
            title = { Text(stringResource(R.string.delete_walk_title)) },
            text = { Text(stringResource(R.string.delete_walk_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteWalk(sessionId)
                        pendingDeleteSessionId = null
                    }
                ) {
                    Text(stringResource(R.string.delete_action), color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSessionId = null }) {
                    Text(stringResource(R.string.cancel_action))
                }
            }
        )
    }
}

@Composable
private fun WalkSessionCard(
    session: WalkSessionSummary,
    onClick: () -> Unit,
    onStopWalkSession: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateFormatter.format(Date(session.startTime)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextOnDark
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.walk_session_stats,
                        formatDuration(session.durationMs),
                        session.distanceKm,
                        session.coveredRoadKm,
                        session.pointsCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextOnDarkSecondary
                )
                if (session.isActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.walk_active_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (session.isActive) {
                    FilledTonalIconButton(onClick = onStopWalkSession) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = stringResource(R.string.stop_action),
                            tint = Error
                        )
                    }
                } else {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete_action),
                            tint = Error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WalkRoutePreviewDialog(
    routePreview: WalkRoutePreview,
    onDismiss: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = DarkCard,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.walk_route_preview_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextOnDark
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = dateFormatter.format(Date(routePreview.session.startTime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextOnDarkSecondary
                        )
                    }

                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close_action))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    routePreview.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    routePreview.points.size < 2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.walk_route_preview_empty),
                                color = TextOnDarkSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    else -> {
                        WalkRouteMapView(
                            points = routePreview.points,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .clip(RoundedCornerShape(18.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WalkRouteMapView(
    points: List<WalkPoint>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val startMarkerIcon = remember(context) {
        createRouteEndpointMarkerDrawable(context, Color.parseColor("#4CAF50"))
    }
    val endMarkerIcon = remember(context) {
        createRouteEndpointMarkerDrawable(context, Color.parseColor("#FF7043"))
    }
    val routePoints = remember(points) { points.map { GeoPoint(it.latitude, it.longitude) } }
    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }

    LaunchedEffect(routePoints) {
        mapView.overlays.clear()

        if (routePoints.isNotEmpty()) {
            val routeLine = Polyline().apply {
                outlinePaint.color = Color.parseColor("#4FC3F7")
                outlinePaint.strokeWidth = 12f
                outlinePaint.alpha = 240
                outlinePaint.style = Paint.Style.STROKE
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                setPoints(routePoints)
            }
            mapView.overlays.add(routeLine)

            val startMarker = Marker(mapView).apply {
                position = routePoints.first()
                icon = startMarkerIcon
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Start"
            }
            mapView.overlays.add(startMarker)

            if (routePoints.size > 1) {
                val endMarker = Marker(mapView).apply {
                    position = routePoints.last()
                    icon = endMarkerIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Finish"
                }
                mapView.overlays.add(endMarker)
            }

            if (routePoints.size == 1) {
                mapView.controller.setCenter(routePoints.first())
                mapView.controller.setZoom(17.0)
            } else {
                mapView.zoomToBoundingBox(createBoundingBox(routePoints), true, 64)
            }
        }

        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { }
    )
}

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 1000 / 60).coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d ч %02d мин", hours, minutes)
    } else {
        String.format(Locale.getDefault(), "%d мин", minutes)
    }
}

@Composable
fun CoverageStatsCard(
    selectedCity: City?,
    coveragePercentage: Float,
    totalRoadsCount: Int,
    exploredRoadsCount: Int,
    totalRoadLengthKm: Double,
    exploredRoadLengthKm: Double,
    totalSessions: Int,
    totalDistanceKm: Double,
    isTracking: Boolean,
    currentPoints: Int,
    isExpanded: Boolean,
    onCityClick: () -> Unit,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Primary.copy(alpha = 0.3f),
                spotColor = Primary.copy(alpha = 0.3f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCard.copy(alpha = 0.95f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onCityClick() }
                        .background(DarkSurfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationCity,
                        contentDescription = stringResource(R.string.city_label),
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedCity?.name ?: stringResource(R.string.choose_city),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextOnDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selectedCity != null) {
                            Text(
                                text = stringResource(R.string.roads_and_length, totalRoadsCount, totalRoadLengthKm),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextOnDarkSecondary
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.tap_to_download_roads),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextOnDarkSecondary
                            )
                        }
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_explore),
                        contentDescription = null,
                        tint = TextOnDarkSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                FilledIconButton(
                    onClick = onToggleExpanded,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = DarkSurfaceVariant.copy(alpha = 0.75f),
                        contentColor = TextOnDark
                    )
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (isExpanded) {
                                R.string.collapse_city_stats_action
                            } else {
                                R.string.expand_city_stats_action
                            }
                        )
                    )
                }
            }

            if (selectedCity != null && isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))

                // Coverage info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circular progress
                    CircularCoverageProgress(
                        percentage = coveragePercentage,
                        modifier = Modifier.size(80.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    // Stats
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.explored_roads),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextOnDarkSecondary
                        )
                        Text(
                            text = "${String.format("%.2f", coveragePercentage)}%",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp
                            ),
                            color = Primary
                        )
                        Text(
                            text = stringResource(R.string.roads_fraction, exploredRoadsCount, totalRoadsCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextOnDarkSecondary
                        )
                        Text(
                            text = stringResource(R.string.length_fraction_km, exploredRoadLengthKm, totalRoadLengthKm),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextOnDarkSecondary
                        )
                    }

                    // Recording indicator
                    if (isTracking) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Error.copy(alpha = pulseAlpha)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_my_location),
                                contentDescription = stringResource(R.string.recording_label),
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Primary.copy(alpha = 0f),
                                    Primary.copy(alpha = 0.5f),
                                    Primary.copy(alpha = 0f)
                                )
                            )
                        )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItemPremium(
                        icon = R.drawable.ic_route,
                        label = stringResource(R.string.walks_label),
                        value = totalSessions.toString()
                    )
                    StatItemPremium(
                        icon = R.drawable.ic_location,
                        label = stringResource(R.string.distance_label),
                        value = stringResource(R.string.distance_km, totalDistanceKm)
                    )
                    if (isTracking) {
                        StatItemPremium(
                            icon = R.drawable.ic_my_location,
                            label = stringResource(R.string.current_label),
                            value = currentPoints.toString(),
                            isHighlighted = true
                        )
                    }
                }
            }
        }
    }
}

private fun offsetToZoomValue(
    y: Float,
    heightPx: Float,
    valueRange: ClosedFloatingPointRange<Float>
): Float {
    val coercedY = y.coerceIn(0f, heightPx)
    val fraction = 1f - (coercedY / heightPx.coerceAtLeast(1f))
    return (valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
        .coerceIn(valueRange.start, valueRange.endInclusive)
}

@Composable
fun CircularCoverageProgress(
    percentage: Float,
    modifier: Modifier = Modifier
) {
    val animatedPercentage by animateFloatAsState(
        targetValue = percentage,
        animationSpec = tween(1000, easing = EaseOutCubic),
        label = "progressAnimation"
    )
    
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 10.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)
            
            // Background circle
            drawCircle(
                color = DarkSurfaceVariant,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            
            // Progress arc
            val sweepAngle = (animatedPercentage / 100f) * 360f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Primary, Secondary, Primary),
                    center = center
                ),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        
        Icon(
            painter = painterResource(R.drawable.ic_explore),
            contentDescription = stringResource(R.string.exploration_label),
            tint = Primary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun StatItemPremium(
    icon: Int,
    label: String,
    value: String,
    isHighlighted: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (isHighlighted) Accent else TextOnDarkSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = if (isHighlighted) Accent else TextOnDark
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextOnDarkSecondary
        )
    }
}

@Composable
fun PremiumTrackingButton(
    isTracking: Boolean,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonSize by animateDpAsState(
        targetValue = if (isTracking) 72.dp else 80.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "buttonSize"
    )
    
    val infiniteTransition = rememberInfiniteTransition(label = "buttonPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isTracking) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Glow effect
        if (isTracking) {
            Box(
                modifier = Modifier
                    .size(buttonSize * pulseScale + 20.dp)
                    .clip(CircleShape)
                    .background(Error.copy(alpha = 0.2f))
                    .blur(8.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(buttonSize + 16.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.2f))
                    .blur(8.dp)
            )
        }
        
        FloatingActionButton(
            onClick = { if (isTracking) onStopTracking() else onStartTracking() },
            modifier = Modifier.size(buttonSize),
            shape = CircleShape,
            containerColor = if (isTracking) Error else Primary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 12.dp,
                pressedElevation = 8.dp
            )
        ) {
            Icon(
                painter = painterResource(
                    id = if (isTracking) R.drawable.ic_stop else R.drawable.ic_walk
                ),
                contentDescription = if (isTracking) stringResource(R.string.stop_action) else stringResource(R.string.start_action),
                modifier = Modifier.size(32.dp),
                tint = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}

