package com.spywalker.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.*
import com.spywalker.LocaleManager
import com.spywalker.R
import com.spywalker.ui.theme.SpyWalkerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpyWalkerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpyWalkerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SpyWalkerApp(
    mapViewModel: MapViewModel = hiltViewModel(),
    cityViewModel: CitySelectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val mapUiState by mapViewModel.uiState.collectAsState()
    val cityUiState by cityViewModel.uiState.collectAsState()
    var pendingTrackingStart by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val selectedLanguage = remember { mutableStateOf(LocaleManager.getSavedLanguage(context)) }

    BackHandler(enabled = showSettings || mapUiState.showCitySelection || mapUiState.showWalkSessions) {
        when {
            showSettings -> showSettings = false
            else -> mapViewModel.handleSystemBack()
        }
    }

    val foregroundLocationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    val backgroundLocationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rememberPermissionState(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            null
        }

    val notificationPermission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            null
        }

    val hasForegroundLocation = foregroundLocationPermissions.allPermissionsGranted
    val hasBackgroundLocation = backgroundLocationPermission?.status?.isGranted ?: true
    val hasNotificationPermission = notificationPermission?.status?.isGranted ?: true

    LaunchedEffect(Unit) {
        if (!hasForegroundLocation) {
            foregroundLocationPermissions.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(hasForegroundLocation) {
        mapViewModel.setForegroundLocationEnabled(hasForegroundLocation)
    }

    LaunchedEffect(
        pendingTrackingStart,
        hasForegroundLocation,
        hasBackgroundLocation,
        hasNotificationPermission
    ) {
        if (pendingTrackingStart && hasForegroundLocation && hasBackgroundLocation && hasNotificationPermission) {
            pendingTrackingStart = false
            mapViewModel.startTracking()
        }
    }

    val requestTrackingPermissions: () -> Unit = {
        when {
            !hasForegroundLocation -> {
                pendingTrackingStart = true
                foregroundLocationPermissions.launchMultiplePermissionRequest()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasBackgroundLocation -> {
                pendingTrackingStart = true
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                context.startActivity(intent)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation -> {
                pendingTrackingStart = true
                backgroundLocationPermission?.launchPermissionRequest()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission -> {
                pendingTrackingStart = true
                notificationPermission?.launchPermissionRequest()
            }
            else -> {
                pendingTrackingStart = false
                mapViewModel.startTracking()
            }
        }
    }
    
    when {
        hasForegroundLocation -> {
            Box(modifier = Modifier.fillMaxSize()) {
                // Main map screen
                MapScreen(
                    uiState = mapUiState,
                    isCityDownloading = cityUiState.isDownloading,
                    cityDownloadProgress = cityUiState.downloadProgress,
                    cityDownloadStatus = cityUiState.downloadStatus,
                    onStartTracking = requestTrackingPermissions,
                    onStopTracking = mapViewModel::stopTracking,
                    onMapZoomChange = mapViewModel::updateMapZoom,
                    onCitySelectionClick = mapViewModel::showCitySelection,
                    onToggleCoverageStats = mapViewModel::toggleCoverageStats,
                    onToggleZoomControl = mapViewModel::toggleZoomControl,
                    onFocusCurrentLocation = mapViewModel::requestFocusOnCurrentLocation,
                    onShowWalks = mapViewModel::showWalkSessions,
                    onHideWalks = mapViewModel::hideWalkSessions,
                    isSettingsVisible = showSettings,
                    selectedLanguage = selectedLanguage.value,
                    onShowSettings = { showSettings = true },
                    onDismissSettings = { showSettings = false },
                    onSelectLanguage = { language ->
                        selectedLanguage.value = language
                        if (LocaleManager.getSavedLanguage(context) != language) {
                            LocaleManager.saveLanguage(context, language)
                            showSettings = false
                            (context as? Activity)?.recreate()
                        } else {
                            showSettings = false
                        }
                    },
                    onPreviewWalkRoute = mapViewModel::previewWalkRoute,
                    onDismissWalkRoutePreview = mapViewModel::dismissWalkRoutePreview,
                    onStopWalkSession = mapViewModel::stopWalkSession,
                    onDeleteWalk = mapViewModel::deleteWalkSession,
                    onDownloadSuggestedCity = { suggestion ->
                        mapViewModel.dismissSuggestedCityDownload()
                        cityViewModel.downloadCity(suggestion.searchResult)
                    },
                    onDismissSuggestedCity = mapViewModel::dismissSuggestedCityDownload
                )
                
                // City selection overlay
                AnimatedVisibility(
                    visible = mapUiState.showCitySelection,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    CitySelectionScreen(
                        uiState = cityUiState,
                        onSearchQueryChange = cityViewModel::updateSearchQuery,
                        onSearch = cityViewModel::searchCities,
                        onDownloadCity = cityViewModel::downloadCity,
                        onSelectCity = cityViewModel::selectCity,
                        onDeleteCity = cityViewModel::deleteCity,
                        onDismiss = mapViewModel::hideCitySelection
                    )
                }
            }
        }
        foregroundLocationPermissions.shouldShowRationale -> {
            PermissionRationale(
                onRequestPermission = { foregroundLocationPermissions.launchMultiplePermissionRequest() },
                onOpenSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                }
            )
        }
        else -> {
            PermissionRequest()
        }
    }
}

@Composable
fun PermissionRationale(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.permission_emoji),
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.permission_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.grant_access))
            }
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.open_settings))
            }
        }
    }
}

@Composable
fun PermissionRequest() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.requesting_permissions),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

