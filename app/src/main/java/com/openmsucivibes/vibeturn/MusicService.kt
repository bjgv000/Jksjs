package com.openmsucivibes.vibeturn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        private const val TAG = "MusicService_DEBUG"
        const val ACTION_PLAY = "com.openmsucivibes.vibeturn.ACTION_PLAY"
        const val ACTION_PAUSE = "com.openmsucivibes.vibeturn.ACTION_PAUSE"
        const val ACTION_STOP = "com.openmsucivibes.vibeturn.ACTION_STOP"
        const val ACTION_NOTIFICATION_PLAY = "com.openmsucivibes.vibeturn.ACTION_NOTIFICATION_PLAY"
        const val ACTION_NOTIFICATION_PAUSE = "com.openmsucivibes.vibeturn.ACTION_NOTIFICATION_PAUSE"
        const val ACTION_NEXT = "com.openmsucivibes.vibeturn.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.openmsucivibes.vibeturn.ACTION_PREVIOUS"
        const val ACTION_REQUEST_TOGGLE_PLAY_PAUSE = "com.openmsucivibes.vibeturn.ACTION_REQUEST_TOGGLE_PLAY_PAUSE"
        const val ACTION_UPDATE_PROGRESS = "com.openmsucivibes.vibeturn.ACTION_UPDATE_PROGRESS"
        const val EXTRA_VIDEO_ID = "com.openmsucivibes.vibeturn.EXTRA_VIDEO_ID"
        const val EXTRA_TITLE = "com.openmsucivibes.vibeturn.EXTRA_TITLE"
        const val EXTRA_DURATION = "com.openmsucivibes.vibeturn.EXTRA_DURATION"
        const val EXTRA_ARTIST = "com.openmsucivibes.vibeturn.EXTRA_ARTIST"
        const val EXTRA_ARTWORK_URL = "com.openmsucivibes.vibeturn.EXTRA_ARTWORK_URL"
        const val ACTION_UPDATE_STATUS_FROM_WEB = "com.openmsucivibes.vibeturn.ACTION_UPDATE_STATUS_FROM_WEB"
        const val EXTRA_IS_PLAYING = "com.openmsucivibes.vibeturn.EXTRA_IS_PLAYING"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "VibeTurnMusicChannelKotlin"
        const val BROADCAST_ACTION_WEBVIEW_CONTROL = "com.openmsucivibes.vibeturn.WEBVIEW_CONTROL"
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentVideoId: String? = null
    private var currentTitle: String = "VibeTurn"
    private var currentDuration: Int = 0
    private var currentArtist: String = "Esperando música..."
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private var currentPlaybackState: Int = PlaybackStateCompat.STATE_NONE
    private var currentProgress: Int = 0
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        setupWakeLock()
        initExoPlayer()
        currentPlaybackState = PlaybackStateCompat.STATE_NONE
        updateMediaSessionState(currentPlaybackState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_PLAY || action == ACTION_REQUEST_TOGGLE_PLAY_PAUSE || action == ACTION_NEXT || action == ACTION_PREVIOUS) {
            acquireWakeLock()
        }
        when (action) {
            ACTION_PLAY -> handleActionPlayConfirm(intent)
            ACTION_PAUSE -> handleActionPauseConfirm()
            ACTION_NOTIFICATION_PLAY -> updateNotificationIcon(true)
            ACTION_NOTIFICATION_PAUSE -> updateNotificationIcon(false)
            ACTION_REQUEST_TOGGLE_PLAY_PAUSE -> handleActionRequestTogglePlayPause()
            ACTION_NEXT -> handleActionNext()
            ACTION_PREVIOUS -> handleActionPrevious()
            ACTION_STOP -> handleActionStop()
            ACTION_UPDATE_PROGRESS -> handleActionUpdateProgress(intent)
        }
        releaseWakeLockIfNeeded()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        exoPlayer?.release()
        exoPlayer = null
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        releaseWakeLock()
        stopForeground(true)
        super.onDestroy()
    }

    private fun handleActionPlayConfirm(intent: Intent?) {
        if (!audioFocusGranted) {
            if (!requestAudioFocus()) return
        }
        val isNewSong = intent?.hasExtra(EXTRA_VIDEO_ID) == true &&
                intent.getStringExtra(EXTRA_VIDEO_ID) != currentVideoId

        if (isNewSong) {
            currentVideoId = intent?.getStringExtra(EXTRA_VIDEO_ID)
            currentDuration = intent?.getIntExtra(EXTRA_DURATION, 0) ?: 0
            currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "Título Desconocido"
            currentArtist = intent?.getStringExtra(EXTRA_ARTIST) ?: "Artista Desconocido"
            currentArtworkUrl = intent?.getStringExtra(EXTRA_ARTWORK_URL)
            currentArtworkBitmap = null

            currentVideoId?.let { id ->
                val uri = Uri.parse("https://www.youtube.com/watch?v=$id")
                val metadata = MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .setArtist(currentArtist)
                    .apply {
                        currentArtworkUrl?.let { artUrl ->
                            setArtworkUri(Uri.parse(artUrl))
                        }
                    }
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
            }
            updateMediaMetadata()
        }
        exoPlayer?.play()
        currentPlaybackState = PlaybackStateCompat.STATE_PLAYING
        updateMediaSessionState(currentPlaybackState)
        val notification = createNotification(currentPlaybackState, currentProgress)
        if (notification != null) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun handleActionPauseConfirm() {
        exoPlayer?.pause()
        currentPlaybackState = PlaybackStateCompat.STATE_PAUSED
        updateMediaSessionState(currentPlaybackState)
        stopForeground(false) // Cambiado a false para no remover la notificación al pausar
        val notification = createNotification(currentPlaybackState, currentProgress)
        if (notification != null) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
        releaseWakeLock()
    }

    private fun handleActionStop() {
        exoPlayer?.pause()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        abandonAudioFocus()
        currentPlaybackState = PlaybackStateCompat.STATE_STOPPED
        updateMediaSessionState(currentPlaybackState)
        mediaSession?.isActive = false
        stopForeground(true)
        stopSelf()
        releaseWakeLock()
    }

    private fun handleActionRequestTogglePlayPause() {
        sendControlActionToActivity(ACTION_REQUEST_TOGGLE_PLAY_PAUSE)
    }

    private fun handleActionNext() {
        sendControlActionToActivity(ACTION_NEXT)
        currentPlaybackState = PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
        updateMediaSessionState(currentPlaybackState)
        exoPlayer?.seekToNext()
    }

    private fun handleActionPrevious() {
        sendControlActionToActivity(ACTION_PREVIOUS)
        currentPlaybackState = PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
        updateMediaSessionState(currentPlaybackState)
        exoPlayer?.seekToPrevious()
    }

    private fun handleActionUpdateProgress(intent: Intent?) {
        val currentTime = intent?.getIntExtra("currentTime", 0) ?: 0
        val totalTime = intent?.getIntExtra("totalTime", 0) ?: 0
        updateSongProgress(currentTime, totalTime)
    }

    private fun sendControlActionToActivity(command: String) {
        val intent = Intent(BROADCAST_ACTION_WEBVIEW_CONTROL).apply {
            putExtra("COMMAND", command)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    @SuppressLint("WakelockTimeout")
    private fun setupWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibeturn::MusicWakelockTag")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            wakeLock = null
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun initExoPlayer() {
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            val audioAttributes = ExoAudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            playWhenReady = false
        }
    }

    private fun releaseWakeLockIfNeeded() {
        if (currentPlaybackState != PlaybackStateCompat.STATE_PLAYING &&
            currentPlaybackState != PlaybackStateCompat.STATE_BUFFERING &&
            currentPlaybackState != PlaybackStateCompat.STATE_SKIPPING_TO_NEXT &&
            currentPlaybackState != PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS) {
            releaseWakeLock()
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            updateMediaSessionState(PlaybackStateCompat.STATE_NONE)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = handleActionRequestTogglePlayPause()
                override fun onPause() = handleActionRequestTogglePlayPause()
                override fun onStop() = handleActionStop()
                override fun onSkipToNext() = handleActionNext()
                override fun onSkipToPrevious() = handleActionPrevious()
            })
            val activityIntent = PendingIntent.getActivity(
                applicationContext, 0,
                Intent(applicationContext, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setSessionActivity(activityIntent)
            isActive = true
        }
    }

    private fun updateMediaSessionState(state: Int) {
        val session = mediaSession ?: return
        val position = (currentProgress * 1000).toLong()
        var availableActions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        if (state != PlaybackStateCompat.STATE_PLAYING && state != PlaybackStateCompat.STATE_BUFFERING) {
            availableActions = availableActions or PlaybackStateCompat.ACTION_PLAY
        }
        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
            availableActions = availableActions or PlaybackStateCompat.ACTION_PAUSE
        }
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(availableActions)
            .setState(state, position, 1.0f)
        session.setPlaybackState(stateBuilder.build())
        currentPlaybackState = state
    }

    private fun updateMediaMetadata() {
        val session = mediaSession ?: return
        val artworkUrl = currentArtworkUrl
        val needsDownload = artworkUrl != null &&
                (currentArtworkBitmap == null || artworkUrl != session.controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ART_URI))

        if (needsDownload) {
            serviceScope.launch(Dispatchers.IO) {
                val downloadedBitmap = downloadArtwork(artworkUrl!!)
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        currentArtworkBitmap = downloadedBitmap
                        applyMetadataToSession()
                    }
                }
            }
        } else {
            applyMetadataToSession()
        }
    }

    private suspend fun downloadArtwork(urlString: String): Bitmap? {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } else null
        } catch (e: Exception) { null }
    }

    private fun applyMetadataToSession() {
        val session = mediaSession ?: return
        val metadataBuilder = MediaMetadataCompat.Builder().apply {
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration.toLong())
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            if (currentArtworkBitmap != null) {
                putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtworkBitmap)
                putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, currentArtworkBitmap)
                putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentArtworkUrl)
            }
        }
        session.setMetadata(metadataBuilder.build())
        if (currentPlaybackState != PlaybackStateCompat.STATE_NONE && currentPlaybackState != PlaybackStateCompat.STATE_STOPPED) {
            val notification = createNotification(currentPlaybackState, currentProgress)
            if (notification != null) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(playbackState: Int, progress: Int = 0): Notification? {
        val session = mediaSession ?: return null
        val isPlaying = (playbackState == PlaybackStateCompat.STATE_PLAYING || playbackState == PlaybackStateCompat.STATE_BUFFERING)
        
        // RECURSOS (Asegúrate de que estos existen en tu carpeta res/drawable)
        val playPauseIconRes = if (isPlaying) R.drawable.ic_pause_app else R.drawable.ic_play_app
        val prevIconRes = R.drawable.ic_play_back
        val nextIconRes = R.drawable.ic_play_next
        val smallIconRes = R.drawable.logo

        val playPauseAction = NotificationCompat.Action(
            playPauseIconRes, if (isPlaying) "Pause" else "Play",
            createActionIntent(ACTION_REQUEST_TOGGLE_PLAY_PAUSE, 1)
        )
        val prevAction = NotificationCompat.Action(prevIconRes, "Previous", createActionIntent(ACTION_PREVIOUS, 3))
        val nextAction = NotificationCompat.Action(nextIconRes, "Next", createActionIntent(ACTION_NEXT, 2))
        val stopPendingIntent = createActionIntent(ACTION_STOP, 4)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setContentTitle(currentTitle)
            setContentText(currentArtist)
            setLargeIcon(currentArtworkBitmap)
            setSmallIcon(smallIconRes)
            addAction(prevAction)
            addAction(playPauseAction)
            addAction(nextAction)
            // AQUÍ SE USA EL ALIAS MediaNotificationCompat para evitar conflictos
            setStyle(MediaNotificationCompat.MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
            )
            setContentIntent(contentIntent)
            setDeleteIntent(stopPendingIntent)
            setOngoing(isPlaying)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setOnlyAlertOnce(true)
            priority = NotificationCompat.PRIORITY_LOW
        }
        return builder.build()
    }

    fun updateNotificationIcon(isPlaying: Boolean) {
        val notification = createNotification(currentPlaybackState, currentProgress)
        if (notification != null) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    fun updateSongProgress(currentTime: Int, totalTime: Int) {
        currentProgress = currentTime
        val notification = createNotification(currentPlaybackState, currentProgress)
        if (notification != null) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun createActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
          val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        // Always set FLAG_IMMUTABLE for pending intents on API 23+ to satisfy Android 12+ security
        // requirements. Also use FLAG_UPDATE_CURRENT so that multiple calls with the same
        // requestCode update the existing pending intent rather than creating duplicates.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // On API 26+ use getForegroundService so the OS will call startForegroundService
            // instead of startService when the pending intent fires.
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VibeTurn Music", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controles de reproducción de VibeTurn"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.createNotificationChannel(channel)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusGranted = true
                if (currentPlaybackState == PlaybackStateCompat.STATE_PAUSED) handleActionRequestTogglePlayPause()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusGranted = false
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> audioFocusGranted = false
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioFocusGranted) return true
        val manager = audioManager ?: return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    build()
                })
                setAcceptsDelayedFocusGain(true)
                setOnAudioFocusChangeListener(this@MusicService, Handler(Looper.getMainLooper()))
                build()
            }
            manager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return audioFocusGranted
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(this)
        }
        audioFocusGranted = false
    }
}
