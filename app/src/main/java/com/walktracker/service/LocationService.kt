package com.spywalker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.spywalker.R
import com.spywalker.repository.OsmRepository
import com.spywalker.repository.WalkRepository
import com.spywalker.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class CurrentLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float
)

@AndroidEntryPoint
class LocationService : Service() {
    
    @Inject
    lateinit var walkRepository: WalkRepository
    
    @Inject
    lateinit var osmRepository: OsmRepository
    
    @Inject
    lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentSessionId: Long? = null
    private val trackingPrefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                _currentLocation.value = CurrentLocationSnapshot(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy
                )

                currentSessionId?.let { sessionId ->
                    serviceScope.launch {
                        // Ð¡Ð¾Ñ…Ñ€Ð°Ð½ÑÐµÐ¼ Ñ‚Ð¾Ñ‡ÐºÑƒ Ð¼Ð°Ñ€ÑˆÑ€ÑƒÑ‚Ð°
                        walkRepository.addPoint(
                            sessionId = sessionId,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy
                        )
                        
                        // ÐžÑ‚Ð¼ÐµÑ‡Ð°ÐµÐ¼ Ð¿Ñ€Ð¾Ð¹Ð´ÐµÐ½Ð½Ñ‹Ðµ Ð´Ð¾Ñ€Ð¾Ð³Ð¸ (OSM)
                        osmRepository.markRoadsExplored(
                            lat = location.latitude,
                            lon = location.longitude,
                            sessionId = sessionId,
                            accuracy = location.accuracy
                        )
                        
                        _pointsCount.value += 1
                    }
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            null -> restoreTrackingIfNeeded()
        }
        return START_STICKY
    }
    
    private fun startTracking() {
        if (_isTracking.value || currentSessionId != null) return
        
        startForeground(NOTIFICATION_ID, createNotification())

        serviceScope.launch {
            try {
                val sessionId = walkRepository.startNewSession()
                currentSessionId = sessionId
                persistActiveSessionId(sessionId)
                _pointsCount.value = 0
                _isTracking.value = true
                startLocationUpdates()
            } catch (e: Exception) {
                e.printStackTrace()
                resetTrackingState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    
    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)

        serviceScope.launch {
            currentSessionId?.let { walkRepository.endSession(it) }
            resetTrackingState()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreTrackingIfNeeded() {
        if (_isTracking.value || currentSessionId != null) return

        val persistedSessionId = getPersistedSessionId() ?: run {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification())
        currentSessionId = persistedSessionId
        _isTracking.value = true
        startLocationUpdates()
    }
    
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            resetTrackingState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_INTERVAL
        ).apply {
            setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
            setWaitForAccurateLocation(true)
        }.build()
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
            resetTrackingState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun persistActiveSessionId(sessionId: Long) {
        trackingPrefs.edit()
            .putLong(KEY_ACTIVE_SESSION_ID, sessionId)
            .apply()
    }

    private fun getPersistedSessionId(): Long? {
        val sessionId = trackingPrefs.getLong(KEY_ACTIVE_SESSION_ID, -1L)
        return sessionId.takeIf { it > 0L }
    }

    private fun resetTrackingState() {
        currentSessionId = null
        _isTracking.value = false
        _pointsCount.value = 0
        trackingPrefs.edit().remove(KEY_ACTIVE_SESSION_ID).apply()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, LocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_walk)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop_action), stopPendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
    
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "location_channel"
        private const val PREFS_NAME = "location_service_prefs"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        
        // Ð˜Ð½Ñ‚ÐµÑ€Ð²Ð°Ð» Ð¾Ð±Ð½Ð¾Ð²Ð»ÐµÐ½Ð¸Ñ - 3 ÑÐµÐºÑƒÐ½Ð´Ñ‹
        private const val LOCATION_INTERVAL = 3000L
        // ÐœÐ¸Ð½Ð¸Ð¼Ð°Ð»ÑŒÐ½Ð¾Ðµ Ñ€Ð°ÑÑÑ‚Ð¾ÑÐ½Ð¸Ðµ - 5 Ð¼ÐµÑ‚Ñ€Ð¾Ð²
        private const val MIN_DISTANCE_METERS = 5f
        
        // Ð¡Ð¾ÑÑ‚Ð¾ÑÐ½Ð¸Ðµ Ñ‚Ñ€ÐµÐºÐ¸Ð½Ð³Ð° Ð´Ð»Ñ UI
        private val _isTracking = MutableStateFlow(false)
        val isTracking: StateFlow<Boolean> = _isTracking
        
        private val _pointsCount = MutableStateFlow(0)
        val pointsCount: StateFlow<Int> = _pointsCount

        private val _currentLocation = MutableStateFlow<CurrentLocationSnapshot?>(null)
        val currentLocation: StateFlow<CurrentLocationSnapshot?> = _currentLocation
        
        fun start(context: Context) {
            Intent(context, LocationService::class.java).apply {
                action = ACTION_START
                context.startForegroundService(this)
            }
        }
        
        fun stop(context: Context) {
            Intent(context, LocationService::class.java).apply {
                action = ACTION_STOP
                context.startService(this)
            }
        }
    }
}

