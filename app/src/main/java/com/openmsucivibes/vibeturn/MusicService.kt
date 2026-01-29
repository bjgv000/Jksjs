package com.openmsucivibes.vibeturn // Asegúrate que el paquete sea el correcto

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
// El reproductor nativo MediaPlayer se sustituye por ExoPlayer (Media3)
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
import android.net.Uri
import android.os.* // Para Handler, Looper, PowerManager, etc.
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat
import androidx.core.app.NotificationCompat as CoreNotificationCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.* // Para Coroutines (descarga de artwork)
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MusicService : Service(), AudioManager.OnAudioFocusChangeListener {

    companion object {
        // TAG para filtrar los logs en Logcat
        private const val TAG = "MusicService_DEBUG" // Añadí _DEBUG para diferenciar

        // --- Acciones y Extras (Manteniendo los nombres originales) ---
        const val ACTION_PLAY = "com.openmsucivibes.vibeturn.ACTION_PLAY" // Usado por JS para confirmar Play/Resume
        const val ACTION_PAUSE = "com.openmsucivibes.vibeturn.ACTION_PAUSE" // Usado por JS para confirmar Pause
        const val ACTION_STOP = "com.openmsucivibes.vibeturn.ACTION_STOP"
        const val ACTION_NOTIFICATION_PLAY = "com.openmsucivibes.vibeturn.ACTION_NOTIFICATION_PLAY"
        const val ACTION_NOTIFICATION_PAUSE = "com.openmsucivibes.vibeturn.ACTION_NOTIFICATION_PAUSE"
        const val ACTION_NEXT = "com.openmsucivibes.vibeturn.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.openmsucivibes.vibeturn.ACTION_PREVIOUS"
        // Acción enviada DESDE Android a JS via Activity para alternar
        const val ACTION_REQUEST_TOGGLE_PLAY_PAUSE = "com.openmsucivibes.vibeturn.ACTION_REQUEST_TOGGLE_PLAY_PAUSE" // Acción para solicitar toggle desde Android
        const val ACTION_UPDATE_PROGRESS = "com.openmsucivibes.vibeturn.ACTION_UPDATE_PROGRESS"

        const val EXTRA_VIDEO_ID = "com.openmsucivibes.vibeturn.EXTRA_VIDEO_ID"
        const val EXTRA_TITLE = "com.openmsucivibes.vibeturn.EXTRA_TITLE"
        const val EXTRA_DURATION = "com.openmsucivibes.vibeturn.EXTRA_DURATION"
        const val EXTRA_ARTIST = "com.openmsucivibes.vibeturn.EXTRA_ARTIST"
        const val EXTRA_ARTWORK_URL = "com.openmsucivibes.vibeturn.EXTRA_ARTWORK_URL"

        const val ACTION_UPDATE_STATUS_FROM_WEB = "com.openmsucivibes.vibeturn.ACTION_UPDATE_STATUS_FROM_WEB"
        const val EXTRA_IS_PLAYING = "com.openmsucivibes.vibeturn.EXTRA_IS_PLAYING"

        // --- Constantes de Notificación ---
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "VibeTurnMusicChannelKotlin"
        // Acción para el broadcast Service -> Activity
        const val BROADCAST_ACTION_WEBVIEW_CONTROL = "com.openmsucivibes.vibeturn.WEBVIEW_CONTROL"
    }

    // --- Variables de Estado ---
    // Instancia de ExoPlayer para reproducir audio de manera real.
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false // Flag para saber si tenemos foco
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Información de la Canción Actual ---
    private var currentVideoId: String? = null
    private var currentTitle: String = "VibeTurn"
    private var currentDuration: Int = 0
    private var currentArtist: String = "Esperando música..."
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    // Estado **CONFIRMADO** por la web o iniciado aquí
    private var currentPlaybackState: Int = PlaybackStateCompat.STATE_NONE
    private var currentProgress: Int = 0 // Progreso de la canción

    // --- Coroutines ---
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // --- Binder ---
    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }
    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind called, returning binder.")
        return binder
    }

    // --- Ciclo de Vida ---

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: Service starting...")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        setupWakeLock()
        // Inicializar ExoPlayer para la reproducción de audio real
        initExoPlayer()
        currentPlaybackState = PlaybackStateCompat.STATE_NONE
        updateMediaSessionState(currentPlaybackState) // Asegura estado inicial
        Log.i(TAG, "onCreate: Service created and configured successfully.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "onStartCommand: Received action = $action, startId = $startId, flags = $flags")

        if (action == null) {
            Log.w(TAG, "onStartCommand: Action is null. Stopping self.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Adquirir WakeLock solo si la acción puede llevar a reproducción activa
        if (action == ACTION_PLAY || action == ACTION_REQUEST_TOGGLE_PLAY_PAUSE || action == ACTION_NEXT || action == ACTION_PREVIOUS) {
            acquireWakeLock()
        }

        Log.d(TAG, "onStartCommand: Processing action '$action'")
        when (action) {
            // Estas acciones vienen de JS (WebAppInterface) o de handleActionPlay interno
            ACTION_PLAY -> handleActionPlayConfirm(intent) // CONFIRMA Play/Resume
            ACTION_PAUSE -> handleActionPauseConfirm()
            ACTION_NOTIFICATION_PLAY -> updateNotificationIcon(true)// CONFIRMA Pause
            ACTION_NOTIFICATION_PAUSE -> updateNotificationIcon(false)
            // Esta acción viene de los botones de Android (MediaSession) -> Activity
            ACTION_REQUEST_TOGGLE_PLAY_PAUSE -> handleActionRequestTogglePlayPause() // SOLICITA Toggle
            ACTION_NEXT -> handleActionNext()               // SOLICITA Next
            ACTION_PREVIOUS -> handleActionPrevious()       // SOLICITA Previous
            ACTION_STOP -> handleActionStop()               // DETIENE todo
            ACTION_UPDATE_PROGRESS -> handleActionUpdateProgress(intent)
            else -> {
                Log.w(TAG, "onStartCommand: Unknown action received: '$action'")
                releaseWakeLockIfNeeded() // Liberar si la acción es desconocida
            }
        }

        // Liberar WakeLock si no estamos activamente reproduciendo o esperando cambio
        releaseWakeLockIfNeeded()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: Service destroying...")
        serviceJob.cancel()
        Log.d(TAG, "onDestroy: Coroutines cancelled.")
        // Liberar el reproductor ExoPlayer
        exoPlayer?.release()
        exoPlayer = null
        Log.d(TAG, "onDestroy: ExoPlayer released.")
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        Log.d(TAG, "onDestroy: MediaSession released.")
        releaseWakeLock() // Asegurar liberación final
        stopForeground(true)
        Log.d(TAG, "onDestroy: WakeLock released and foreground stopped.")
        Log.i(TAG, "onDestroy: Service destroyed.")
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("MusicService", "onTaskRemoved: Task removed, stopping service.")
        // Detener la reproducción si está activa
        // if (player.isPlaying()) {
        //     player.stop()
        // }
        //player.release() // Libera recursos del reproductor

        Log.i(TAG, "onDestroy: Service destroying...")
        serviceJob.cancel()
        Log.d(TAG, "onDestroy: Coroutines cancelled.")
        // Liberar ExoPlayer
        exoPlayer?.release()
        exoPlayer = null
        Log.d(TAG, "onDestroy: ExoPlayer released.")
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        Log.d(TAG, "onDestroy: MediaSession released.")
        releaseWakeLock() // Asegurar liberación final
    }

    // --- Manejadores de Acciones ---

    /**
     * LLAMADO DESDE JS (vía WebAppInterface con ACTION_PLAY):
     * Confirma que la reproducción DEBE estar activa. Actualiza estado, metadatos y notificación.
     * ¡¡MODIFICADO para solicitar foco solo si es necesario!!
     */
    private fun handleActionPlayConfirm(intent: Intent?) {
        Log.i(TAG, "handleActionPlayConfirm: Current state before confirmation: $currentPlaybackState")

        // --- CAMBIO AQUÍ: Solicitar foco SOLO si no lo tenemos ---
        if (!audioFocusGranted) {
            Log.d(TAG, "handleActionPlayConfirm: Audio focus was not granted. Requesting focus...")
            if (!requestAudioFocus()) {
                Log.w(TAG, "handleActionPlayConfirm: Failed to gain audio focus. Aborting play confirmation.")
                releaseWakeLock() // Liberar si no podemos obtener foco
                // Podríamos incluso forzar pausa aquí si falla la obtención de foco
                // handleActionPauseConfirm() // Ojo: podría causar un bucle si la pausa también falla
                return // Salir si no obtenemos foco
            }
            // Si llegamos aquí, requestAudioFocus() tuvo éxito y audioFocusGranted es true
            Log.d(TAG, "handleActionPlayConfirm: Audio focus successfully granted.")
        } else {
            Log.d(TAG, "handleActionPlayConfirm: Audio focus already granted. Proceeding.")
        }
        // ---------------------------------------------------------

        // Actualizar datos si vienen con el intent (normalmente de notifySongChanged)
        val isNewSong = intent?.hasExtra(EXTRA_VIDEO_ID) == true &&
                intent.getStringExtra(EXTRA_VIDEO_ID) != currentVideoId

        if (isNewSong) {
            // Actualizar la información de la canción con los datos recibidos del intent
            currentVideoId = intent?.getStringExtra(EXTRA_VIDEO_ID)
            currentDuration = intent?.getIntExtra(EXTRA_DURATION, 0) ?: 0
            currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: "Título Desconocido"
            currentArtist = intent?.getStringExtra(EXTRA_ARTIST) ?: "Artista Desconocido"
            currentArtworkUrl = intent?.getStringExtra(EXTRA_ARTWORK_URL)
            currentArtworkBitmap = null // Resetear artwork previamente descargado
            Log.i(TAG, "handleActionPlayConfirm: New song data received. duration: $currentDuration, title: $currentTitle, artist: $currentArtist, artworkUrl: $currentArtworkUrl. ID: $currentVideoId")

            // Configurar ExoPlayer con el nuevo elemento multimedia. Se construye un MediaItem
            // con la URI pública (por ejemplo, un video de YouTube) y se añaden metadatos para
            // mostrar título, artista y carátula en dispositivos compatibles.
            currentVideoId?.let { id ->
                val uri = Uri.parse("https://www.youtube.com/watch?v=$id")
                val metadata = MediaMetadata.Builder()
                    .setTitle(currentTitle)
                    .setArtist(currentArtist)
                    .apply {
                        currentArtworkUrl?.let { artUrl ->
                            // Establecer URI de la carátula para que ExoPlayer la exponga a los clientes
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
                Log.d(TAG, "handleActionPlayConfirm: ExoPlayer media item set and prepared for URI $uri")
            }

            updateMediaMetadata() // Actualizar metadatos y descargar artwork para la notificación
        } else {
            Log.d(TAG, "handleActionPlayConfirm: Resuming or no new song data.")
        }

        // Iniciar la reproducción en ExoPlayer. Si ya estaba preparado, simplemente reanuda.
        exoPlayer?.play()

        // Establecer estado PLAYING y actualizar notificación/foreground
        Log.d(TAG, "handleActionPlayConfirm: Updating state to PLAYING.")
        currentPlaybackState = PlaybackStateCompat.STATE_PLAYING
        updateMediaSessionState(currentPlaybackState)
        val notification = createNotification(currentPlaybackState, currentProgress)
        if (notification != null) {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "handleActionPlayConfirm: Service in foreground with PLAYING notification.")
        } else {
            Log.e(TAG, "handleActionPlayConfirm: Failed to create notification!")
        }

        // Mantener WakeLock porque estamos (o deberíamos estar) reproduciendo.
        Log.d(TAG, "handleActionPlayConfirm: Processing finished.")
    }


    /**
     * LLAMADO DESDE JS (vía WebAppInterface con ACTION_PAUSE):
     * Confirma que la reproducción DEBE estar pausada. Actualiza estado y notificación.
     */
    private fun handleActionPauseConfirm() {
        Log.i(TAG, "handleActionPauseConfirm: Current state before confirmation: $currentPlaybackState")

        // Pausar el reproductor si se está reproduciendo
        exoPlayer?.pause()

        // Cambiar estado local a PAUSED y actualizar el MediaSession
        currentPlaybackState = PlaybackStateCompat.STATE_PAUSED
        updateMediaSessionState(currentPlaybackState)

        // Mantener la notificación pero dejar de ser servicio en primer plano
        stopForeground(STOP_FOREGROUND_DETACH)

        val notification = createNotification(currentPlaybackState, currentProgress)
        if (notification != null) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "handleActionPauseConfirm: Notification updated to PAUSED state.")
        } else {
            Log.e(TAG, "handleActionPauseConfirm: Failed to create notification for PAUSED state!")
        }

        // Liberar WakeLock porque ya no estamos reproduciendo activamente
        releaseWakeLock()
        Log.d(TAG, "handleActionPauseConfirm: Processing finished.")
    }

    /**
     * LLAMADO DESDE JS O MEDIASESSION: Detiene todo.
     */
    private fun handleActionStop() {
        Log.i(TAG, "handleActionStop: Initiated.")
        // Detener y limpiar el reproductor
        exoPlayer?.pause()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        abandonAudioFocus()
        currentPlaybackState = PlaybackStateCompat.STATE_STOPPED
        updateMediaSessionState(currentPlaybackState)
        mediaSession?.isActive = false
        Log.d(TAG, "handleActionStop: MediaSession deactivated.")
        stopForeground(true) // Remover la notificación
        Log.d(TAG, "handleActionStop: stopForeground(true) called.")
        stopSelf() // Detener el servicio
        Log.i(TAG, "handleActionStop: stopSelf() called. Service will be destroyed.")
        releaseWakeLock() // Asegurar liberación
    }

    /**
     * LLAMADO DESDE MEDIASESSION (Botones Android): Solicita al WebView que alterne Play/Pause.
     * NO cambia el estado local directamente.
     */
    private fun handleActionRequestTogglePlayPause() {
        Log.i(TAG, "handleActionRequestTogglePlayPause: Sending request to WebView.")
        // SOLO envía el comando a la Activity/WebView.
        // La respuesta (confirmación de Play o Pause) vendrá de JS -> WebAppInterface -> ACTION_PLAY/ACTION_PAUSE
        sendControlActionToActivity(ACTION_REQUEST_TOGGLE_PLAY_PAUSE) // Usamos esta acción específica para la Activity
        // NO CAMBIAMOS currentPlaybackState AQUÍ. Esperamos confirmación.
        Log.d(TAG, "handleActionRequestTogglePlayPause: Request sent. Waiting for JS confirmation.")
        // Mantenemos el WakeLock aquí porque esperamos una acción resultante.
        // acquireWakeLock() // Aseguramos tenerlo mientras esperamos respuesta? Podría ser útil.
    }

    private fun handleActionNext() {
        Log.i(TAG, "handleActionNext: Sending NEXT request to WebView.")
        // 1. Enviar comando al WebView
        sendControlActionToActivity(ACTION_NEXT) // La Activity le dirá a JS playNextTrack()
        // 2. Actualizar estado local a "saltando" (visual)
        currentPlaybackState = PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
        updateMediaSessionState(currentPlaybackState) // Notificar al sistema
        // Intentar avanzar al siguiente elemento en ExoPlayer, si existe
        exoPlayer?.seekToNext()
        // Mantener WakeLock esperando que la nueva canción llame a ACTION_PLAY
        Log.d(TAG, "handleActionNext: Request sent. Waiting for new song notification from WebView.")
    }

    private fun handleActionPrevious() {
        Log.i(TAG, "handleActionPrevious: Sending PREVIOUS request to WebView.")
        // 1. Enviar comando al WebView
        sendControlActionToActivity(ACTION_PREVIOUS) // La Activity le dirá a JS playPreviousTrack()
        // 2. Actualizar estado local a "saltando" (visual)
        currentPlaybackState = PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
        updateMediaSessionState(currentPlaybackState) // Notificar al sistema
        // Intentar retroceder al elemento anterior en ExoPlayer, si existe
        exoPlayer?.seekToPrevious()
        // Mantener WakeLock esperando que la nueva canción llame a ACTION_PLAY
        Log.d(TAG, "handleActionPrevious: Request sent. Waiting for new song notification from WebView.")
    }

    private fun handleActionUpdateProgress(intent: Intent?) {
        val currentTime = intent?.getIntExtra("currentTime", 0) ?: 0
        val totalTime = intent?.getIntExtra("totalTime", 0) ?: 0
        updateSongProgress(currentTime, totalTime)
    }

    // --- Comunicación Service -> Activity ---
    private fun sendControlActionToActivity(command: String) {
        // Usar la constante definida para la acción del broadcast
        val intent = Intent(BROADCAST_ACTION_WEBVIEW_CONTROL).apply {
            // Pasamos el comando ORIGINAL (ej: NEXT, PREVIOUS, REQUEST_TOGGLE...)
            // para que la Activity sepa qué función JS llamar.
            putExtra("COMMAND", command)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.i(TAG, "sendControlActionToActivity: Sent broadcast action '$BROADCAST_ACTION_WEBVIEW_CONTROL' with command '$command'")
    }

    // --- WakeLock ---
    @SuppressLint("WakelockTimeout")
    private fun setupWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "vibeturn::MusicWakelockTag" // Tag único
            )
            wakeLock?.setReferenceCounted(false)
            Log.d(TAG, "setupWakeLock: WakeLock prepared.")
        } catch (e: Exception) {
            Log.e(TAG, "setupWakeLock: Failed to create WakeLock", e)
            wakeLock = null
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                Log.i(TAG, "acquireWakeLock: WakeLock acquired.")
            } else {
                Log.d(TAG, "acquireWakeLock: WakeLock already held or null.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock: Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "releaseWakeLock: WakeLock released.")
            } else {
                Log.d(TAG, "releaseWakeLock: WakeLock not held or null.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseWakeLock: Error releasing WakeLock", e)
        }
    }

    /**
     * Inicializa la instancia de ExoPlayer con los atributos de audio adecuados. Este método se llama
     * en onCreate() para preparar el reproductor a recibir medios. Se establecen atributos de audio
     * específicos para contenido musical y se configura para manejar correctamente los eventos
     * de "audio becoming noisy" (como desconectar auriculares). Inicialmente, el reproductor no
     * inicia la reproducción automáticamente (playWhenReady = false) hasta recibir la orden de
     * reproducción desde la web.
     */
    private fun initExoPlayer() {
        // Liberar instancia previa si existe para evitar fugas
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            // Configurar atributos de audio para uso multimedia
            val audioAttributes = ExoAudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            setAudioAttributes(audioAttributes, true)
            // Reducir volumen cuando el audio se vuelve ruidoso (ej. auriculares desconectados)
            setHandleAudioBecomingNoisy(true)
            playWhenReady = false
        }
        Log.d(TAG, "initExoPlayer: ExoPlayer initialized.")
    }

    // Helper para liberar wakelock solo si no estamos en estado activo o esperando acción
    private fun releaseWakeLockIfNeeded() {
        if (currentPlaybackState != PlaybackStateCompat.STATE_PLAYING &&
            currentPlaybackState != PlaybackStateCompat.STATE_BUFFERING &&
            currentPlaybackState != PlaybackStateCompat.STATE_SKIPPING_TO_NEXT &&
            currentPlaybackState != PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS) {
            // Podríamos añadir una condición extra para mantenerlo si acabamos de enviar un REQUEST_TOGGLE
            // y estamos esperando respuesta, pero por ahora lo liberamos si no está activo.
            Log.d(TAG, "releaseWakeLockIfNeeded: Releasing WakeLock as state is $currentPlaybackState.")
            releaseWakeLock()
        } else {
            Log.d(TAG, "releaseWakeLockIfNeeded: Keeping WakeLock as state is $currentPlaybackState.")
        }
    }


    // --- MediaSession ---
    private fun setupMediaSession() {
        Log.d(TAG, "setupMediaSession: Setting up MediaSession...")
        try {
            mediaSession = MediaSessionCompat(this, TAG /* Debugging tag */).apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                updateMediaSessionState(PlaybackStateCompat.STATE_NONE) // Estado inicial

                setCallback(object : MediaSessionCompat.Callback() {
                    // AHORA: Estos callbacks envían la ACCIÓN DE *SOLICITUD*
                    override fun onPlay() {
                        Log.i(TAG, "MediaSession Callback: onPlay received. Requesting Toggle.")
                        handleActionRequestTogglePlayPause() // Solicitar Toggle
                    }
                    override fun onPause() {
                        Log.i(TAG, "MediaSession Callback: onPause received. Requesting Toggle.")
                        handleActionRequestTogglePlayPause() // Solicitar Toggle
                    }
                    override fun onStop() {
                        Log.i(TAG, "MediaSession Callback: onStop received.")
                        handleActionStop() // Stop sí puede ser directo
                    }
                    override fun onSkipToNext() {
                        Log.i(TAG, "MediaSession Callback: onSkipToNext received.")
                        handleActionNext() // Next/Prev envían comando a JS
                    }
                    override fun onSkipToPrevious() {
                        Log.i(TAG, "MediaSession Callback: onSkipToPrevious received.")
                        handleActionPrevious()
                    }
                })

                val activityIntent = PendingIntent.getActivity(
                    applicationContext, 0,
                    Intent(applicationContext, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setSessionActivity(activityIntent)
                Log.d(TAG, "setupMediaSession: Session activity intent set.")

                isActive = true
                Log.i(TAG, "setupMediaSession: MediaSession setup complete and activated.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupMediaSession: Failed to create MediaSession!", e)
            mediaSession = null // Asegurar que es null si falla
        }
    }

    // updateMediaSessionState: Lógica de availableActions ajustada ligeramente
    private fun updateMediaSessionState(state: Int) {
        val session = mediaSession
        if (session == null) {
            Log.e(TAG, "updateMediaSessionState: MediaSession is null! Cannot update state $state.")
            return
        }
        Log.d(TAG, "updateMediaSessionState: Updating state to $state (Current was $currentPlaybackState)")

        val position = (currentProgress * 1000).toLong()
        Log.d(TAG, "updateMediaSessionState: Current progress: $position")
        // Definir qué acciones están disponibles AHORA MISMO basado en el estado 'state'
        var availableActions = PlaybackStateCompat.ACTION_PLAY_PAUSE or // Siempre permitir botón toggle (aunque lo manejamos igual)
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        // Añadir acción PLAY solo si el estado NO es PLAYING/BUFFERING
        if (state != PlaybackStateCompat.STATE_PLAYING && state != PlaybackStateCompat.STATE_BUFFERING) {
            availableActions = availableActions or PlaybackStateCompat.ACTION_PLAY
        }
        // Añadir acción PAUSE solo si el estado SÍ es PLAYING/BUFFERING
        if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
            availableActions = availableActions or PlaybackStateCompat.ACTION_PAUSE
        }


        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(availableActions) // Acciones disponibles según el estado
            .setState(state, position, 1.0f) // state, position, playback speed

        try {
            session.setPlaybackState(stateBuilder.build())
            // Solo actualizar nuestro estado interno SI la llamada a setPlaybackState tuvo éxito
            currentPlaybackState = state
            Log.i(TAG, "updateMediaSessionState: State successfully updated to $state. Available actions: $availableActions")
        } catch (e: Exception) {
            Log.e(TAG, "updateMediaSessionState: Error setting playback state for state $state!", e)
        }
    }


    // updateMediaMetadata: Sin cambios necesarios.
    private fun updateMediaMetadata() {
        val session = mediaSession
        if (session == null) {
            Log.e(TAG, "updateMediaMetadata: MediaSession is null! Cannot update metadata.")
            return
        }
        Log.i(TAG, "updateMediaMetadata: Updating for Title='$currentTitle', Artist='$currentArtist'")

        val artworkUrl = currentArtworkUrl
        val needsDownload = artworkUrl != null &&
                (currentArtworkBitmap == null || artworkUrl != session.controller.metadata?.getString(MediaMetadataCompat.METADATA_KEY_ART_URI))

        if (needsDownload) {
            Log.d(TAG, "updateMediaMetadata: Artwork URL ('$artworkUrl') requires download. Starting coroutine.")
            serviceScope.launch(Dispatchers.IO) {
                Log.d(TAG, "Artwork Coroutine: Downloading from $artworkUrl")
                val downloadedBitmap = downloadArtwork(artworkUrl)
                withContext(Dispatchers.Main) {
                    if (isActive) {
                        Log.d(TAG, "Artwork Coroutine: Download finished (Success = ${downloadedBitmap != null}). Applying metadata.")
                        currentArtworkBitmap = downloadedBitmap
                        applyMetadataToSession()
                    } else {
                        Log.d(TAG, "Artwork Coroutine: Job cancelled before applying metadata.")
                    }
                }
            }
        } else {
            if (artworkUrl == null) Log.d(TAG, "updateMediaMetadata: No artwork URL.")
            else Log.d(TAG, "updateMediaMetadata: Artwork already present or URL unchanged.")
            currentArtworkBitmap = if (artworkUrl == null) null else currentArtworkBitmap
            applyMetadataToSession()
        }
        Log.d(TAG, "updateMediaMetadata: Process finished (artwork download might be running).")
    }

    // downloadArtwork: Sin cambios necesarios.
    private suspend fun downloadArtwork(urlString: String): Bitmap? {
        Log.d(TAG, "downloadArtwork: Attempting to download from $urlString")
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            var bitmap: Bitmap? = null
            Log.d(TAG, "downloadArtwork: Connecting...")
            connection.connect()
            val responseCode = connection.responseCode
            Log.d(TAG, "downloadArtwork: HTTP Response Code: $responseCode")
            if (responseCode == HttpURLConnection.HTTP_OK) {
                var inputStream: InputStream? = null
                try {
                    inputStream = connection.inputStream
                    bitmap = BitmapFactory.decodeStream(inputStream)
                    Log.i(TAG, "downloadArtwork: Bitmap decoded successfully.")
                } finally {
                    inputStream?.close()
                }
            } else {
                Log.w(TAG,"downloadArtwork: Failed with HTTP code $responseCode")
            }
            connection.disconnect()
            bitmap
        } catch (e: IOException) {
            Log.e(TAG, "downloadArtwork: IOException - ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "downloadArtwork: OutOfMemoryError.", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "downloadArtwork: Unexpected error - ${e.message}", e)
            null
        }
    }

    // applyMetadataToSession: Sin cambios necesarios.
    private fun applyMetadataToSession() {
        val session = mediaSession ?: return
        Log.d(TAG, "applyMetadataToSession: Building metadata...")
        val metadataBuilder = MediaMetadataCompat.Builder().apply {
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION,  (currentDuration).toLong() )
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle ?: "Título desconocido")
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist ?: "Artista desconocido")
            if (currentArtworkBitmap != null) {
                putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtworkBitmap)
                putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, currentArtworkBitmap)
                putString(MediaMetadataCompat.METADATA_KEY_ART_URI, currentArtworkUrl)
                Log.d(TAG, "applyMetadataToSession: Artwork included.")
            } else {
                putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, null)
                putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, null)
                putString(MediaMetadataCompat.METADATA_KEY_ART_URI, null)
                Log.d(TAG, "applyMetadataToSession: No artwork included.")
            }
        }
        try {
            session.setMetadata(metadataBuilder.build())
            Log.i(TAG, "applyMetadataToSession: Metadata applied successfully.")

            if (currentPlaybackState != PlaybackStateCompat.STATE_NONE &&
                currentPlaybackState != PlaybackStateCompat.STATE_STOPPED) {

                val notification = createNotification(currentPlaybackState, currentProgress)
                if (notification != null) {
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                    Log.d(TAG, "applyMetadataToSession: Notification updated with new metadata.")
                } else {
                    Log.e(TAG, "applyMetadataToSession: Failed to create notification after metadata update!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyMetadataToSession: Error setting metadata!", e)
        }
    }

    // --- Notificación ---
    // createNotification: Lógica del PendingIntent de Play/Pause ya estaba actualizada para enviar REQUEST_TOGGLE.
    // ¡¡ASEGÚRATE DE TENER LOS DRAWABLES CORRECTOS!!
    private fun createNotification(playbackState: Int, progress: Int = 0): Notification? {
        val session = mediaSession
        if (session == null) {
            Log.e(TAG, "createNotification: MediaSession is null!")
            return null
        }
        Log.d(TAG, "createNotification: Creating notification for state $playbackState")

        val isPlaying = (playbackState == PlaybackStateCompat.STATE_PLAYING ||
                playbackState == PlaybackStateCompat.STATE_BUFFERING)

        // --- Iconos (¡¡REEMPLAZA CON TUS RECURSOS!!) ---
        // Necesitas tener estos drawables en res/drawable
        val playPauseIconRes = if (isPlaying) R.drawable.ic_pause_app else R.drawable.ic_play_app // NECESITAS AMBOS ICONOS
        val prevIconRes = R.drawable.ic_play_back // NECESITAS ESTE ICONO
        val nextIconRes = R.drawable.ic_play_next // NECESITAS ESTE ICONO
        val smallIconRes = R.drawable.logo // NECESITAS ESTE ICONO (blanco/transparente)

        // --- Acciones ---
        val playPauseAction = NotificationCompat.Action(
            playPauseIconRes,
            if (isPlaying) "Pause" else "Play",
            createActionIntent(ACTION_REQUEST_TOGGLE_PLAY_PAUSE, 1) // Envía solicitud de toggle
        )
        val prevAction = NotificationCompat.Action(
            prevIconRes, "Previous", createActionIntent(ACTION_PREVIOUS, 3)
        )
        val nextAction = NotificationCompat.Action(
            nextIconRes, "Next", createActionIntent(ACTION_NEXT, 2)
        )
        val stopPendingIntent = createActionIntent(ACTION_STOP, 4)

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- Construcción ---
        val builder = NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setContentTitle(currentTitle ?: "VibeTurn")
            setContentText(currentArtist ?: "")
            setLargeIcon(currentArtworkBitmap)
            setSmallIcon(smallIconRes) // ¡Asegúrate que este icono exista y sea adecuado!

            addAction(prevAction)
            addAction(playPauseAction) // Botón Play/Pause
            addAction(nextAction)
            setStyle(MediaStyle()
                .setMediaSession(session.sessionToken)
                .setShowActionsInCompactView(0, 1, 2) // prev, play/pause, next
            )

            setContentIntent(contentIntent)
            setDeleteIntent(stopPendingIntent)
            setOngoing(isPlaying)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setOnlyAlertOnce(true)
            priority = NotificationCompat.PRIORITY_LOW // Progreso de la canción
        }
        Log.d(TAG, "createNotification: Notification built successfully.")
        return builder.build()
    }

    fun updateNotificationIcon(isPlaying: Boolean) {
        // Create the notification with the updated icon
        val notification = createNotification(currentPlaybackState, currentProgress) // Ensure this method is updated to use the new icon
        if (notification != null) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } else {
            Log.e(TAG, "Failed to create notification: Notification is null.")
        }
    }

    fun updateSongProgress(currentTime: Int, totalTime: Int) {

        currentProgress = currentTime
        updateMediaSessionState(currentPlaybackState)
        val notification = createNotification(currentPlaybackState, currentProgress)
        if (notification != null) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } else {
            Log.e(TAG, "Failed to create notification: Notification is null.")
        }
    }

    // createActionIntent: Sin cambios necesarios.
    private fun createActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        Log.d(TAG, "createActionIntent: Creating PendingIntent for action '$action', requestCode $requestCode")
        return PendingIntent.getService(this, requestCode, intent, flags)
    }


    // createNotificationChannel: Sin cambios necesarios.
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "VibeTurn Music"
            val descriptionText = "Controles de reproducción de VibeTurn"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = descriptionText
                setSound(null, null)
                vibrationPattern = null
                setShowBadge(false)
            }
            val notificationManager: NotificationManager? =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel)
                Log.i(TAG, "createNotificationChannel: Channel '$CHANNEL_ID' created.")
            } else {
                Log.e(TAG, "createNotificationChannel: NotificationManager is null!")
            }
        } else {
            Log.d(TAG, "createNotificationChannel: Not needed for API < 26.")
        }
    }

    // --- Manejo de Foco de Audio ---
    // onAudioFocusChange: Lógica de llamada a handlers ajustada ligeramente en la versión anterior.
    override fun onAudioFocusChange(focusChange: Int) {
        val focusChangeDescription = when(focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS (Permanent)" // -1
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT" // -2
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK" // -3
            else -> "UNKNOWN ($focusChange)"
        }
        Log.e(TAG, "!!! onAudioFocusChange received: $focusChangeDescription !!! Current State was: $currentPlaybackState") // Log de ERROR para que resalte

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusGranted = true
                if (currentPlaybackState == PlaybackStateCompat.STATE_PAUSED /* && fuePausadoPorFoco */) {
                    Log.d(TAG, "onAudioFocusChange: Regained focus while paused, requesting toggle (will resume).")
                    handleActionRequestTogglePlayPause() // Solicitar reanudación
                }
                // mediaPlayer?.setVolume(1.0f, 1.0f) // Subir volumen si se hizo ducking
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusGranted = false
                // Si perdemos foco permanentemente, lo mejor es confirmar la pausa.
                // No enviaremos solicitud de toggle aquí, sino confirmación directa de pausa.
                Log.e(TAG, "onAudioFocusChange: PERMANENT LOSS DETECTED. Confirming Pause state.")
                //handleActionPauseConfirm()
                // Y abandonamos el foco nosotros mismos si el sistema no lo hizo ya
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioFocusGranted = false
                if (currentPlaybackState == PlaybackStateCompat.STATE_PLAYING || currentPlaybackState == PlaybackStateCompat.STATE_BUFFERING) {
                    // Si perdemos foco temporalmente, confirmar pausa.
                    Log.w(TAG, "onAudioFocusChange: TRANSIENT LOSS DETECTED. Confirming Pause state.")
                    //handleActionPauseConfirm()
                } else {
                    Log.d(TAG, "onAudioFocusChange: Transient focus loss but wasn't playing/buffering ($currentPlaybackState). Doing nothing.")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // No pausamos, solo bajaríamos volumen si tuviéramos control real
                Log.i(TAG, "onAudioFocusChange: Ducking requested. (No action taken in simulation)")
                // mediaPlayer?.setVolume(0.3f, 0.3f)
            }
        }
        Log.d(TAG, "onAudioFocusChange: Finished processing focus change.")
    }


    // requestAudioFocus: Sin cambios necesarios.
    private fun requestAudioFocus(): Boolean {
        if (audioFocusGranted) {
            Log.d(TAG,"requestAudioFocus: Already granted.")
            return true
        }
        val manager = audioManager
        if (manager == null) {
            Log.e(TAG, "requestAudioFocus: AudioManager is null!")
            return false
        }

        val result: Int
        Log.d(TAG, "requestAudioFocus: Requesting audio focus...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
                setAudioAttributes(AudioAttributes.Builder().run {
                    setUsage(AudioAttributes.USAGE_MEDIA)
                    setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    build()
                })
                setAcceptsDelayedFocusGain(true) // Permite obtener foco más tarde si está ocupado brevemente
                setOnAudioFocusChangeListener(this@MusicService, Handler(Looper.getMainLooper()))
                build()
            }
            result = manager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            result = manager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }

        audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        if (audioFocusGranted) {
            Log.i(TAG, "requestAudioFocus: GRANTED")
        } else {
            Log.e(TAG, "requestAudioFocus: FAILED (Result code: $result)")
        }
        return audioFocusGranted
    }


    // abandonAudioFocus: Sin cambios necesarios.
    private fun abandonAudioFocus() {
        if (!audioFocusGranted) {
            Log.d(TAG,"abandonAudioFocus: Focus not granted, nothing to abandon.")
            return
        }
        val manager = audioManager
        if (manager == null) {
            Log.e(TAG, "abandonAudioFocus: AudioManager is null!")
            return
        }

        Log.i(TAG,"abandonAudioFocus: Abandoning audio focus...")
        val result: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result = audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            result = manager.abandonAudioFocus(this)
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG,"abandonAudioFocus: Success.")
        } else {
            Log.e(TAG,"abandonAudioFocus: FAILED (Result code: $result)")
        }
        // Importante: Marcar como no concedido independientemente del resultado de abandonar
        audioFocusGranted = false
    }
}
