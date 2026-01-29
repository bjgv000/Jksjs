package com.openmsucivibes.vibeturn // Asegúrate que el paquete sea el correcto

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Clase Puente entre JavaScript (en WebView) y Kotlin (Android App).
 *
 * @property context El contexto de la aplicación (normalmente la Activity).
 * @property webView Referencia al WebView para poder llamar a funciones JavaScript. Nullable por seguridad.
 */
class WebAppInterface(private val context: Context, private val webView: WebView?) {

    // Handler para ejecutar acciones en el hilo principal (UI thread) de forma segura.
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // TAG para filtrar logs, consistente con el servicio
        private const val TAG = "WebAppInterface_DEBUG"
    }

    /**
     * LLAMADO DESDE JAVASCRIPT: Cuando una nueva canción empieza a sonar en la web.
     * Envía la información al MusicService para actualizar la notificación y metadatos.
     */
    @JavascriptInterface
    fun notifySongChanged(videoId: String?, title: String?, artist: String?, artworkUrl: String?, duration: Int = 1800) {
        Log.i(TAG, "notifySongChanged called from JS. ID=$videoId, Title=$title") // Log de Información
        // Validar datos recibidos (opcional pero recomendado)
        if (videoId.isNullOrEmpty() || title.isNullOrEmpty()) {
            Log.w(TAG, "notifySongChanged: Received incomplete data (videoId or title is null/empty).") // Log de Advertencia
            // Podrías decidir no enviar el intent si faltan datos cruciales
            // return
        }

        // Ejecutar en hilo principal para seguridad con Intents/Context
        mainHandler.post {
            Log.d(TAG, "notifySongChanged: Preparing ACTION_PLAY $duration intent for MusicService.")
            val intent = Intent(context, MusicService::class.java).apply {
                action = MusicService.ACTION_PLAY // Indica iniciar/cambiar canción
                // Pasar datos usando let para seguridad con nulos
                videoId?.let { putExtra(MusicService.EXTRA_VIDEO_ID, it) }
                duration?.let { putExtra(MusicService.EXTRA_DURATION, it) }
                title?.let { putExtra(MusicService.EXTRA_TITLE, it) }
                artist?.let { putExtra(MusicService.EXTRA_ARTIST, it) }
                artworkUrl?.let { putExtra(MusicService.EXTRA_ARTWORK_URL, it) }
            }
            Log.i(TAG, "notifySongChanged: Sending ACTION_PLAY intent to MusicService.") // Log de Información
            // Iniciar servicio (necesario si no está corriendo) y enviar comando
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "notifySongChanged: Service start requested.")
            } catch (e: Exception) {
                Log.e(TAG, "notifySongChanged: Error starting service!", e) // Log de Error
            }
        }
        Log.d(TAG, "notifySongChanged: Processing finished.")
    }

    /**
     * LLAMADO DESDE JAVASCRIPT: Cuando se desea detener explícitamente la reproducción
     * y el servicio (ej: botón "Salir" en la web).
     * ¡NO LLAMAR simplemente porque una canción terminó!
     */
    @JavascriptInterface
    fun notifyPlaybackStopped() {
        Log.i(TAG, "notifyPlaybackStopped called from JS.") // Log de Información
        mainHandler.post {
            Log.d(TAG, "notifyPlaybackStopped: Preparing ACTION_STOP intent for MusicService.")
            val intent = Intent(context, MusicService::class.java).apply {
                action = MusicService.ACTION_STOP // Acción para detener el servicio
            }
            Log.i(TAG, "notifyPlaybackStopped: Sending ACTION_STOP intent to MusicService.") // Log de Información
            try {
                context.startService(intent) // No necesita ser foreground para detenerse
                Log.d(TAG, "notifyPlaybackStopped: Service stop requested.")
            } catch (e: Exception) {
                Log.e(TAG, "notifyPlaybackStopped: Error stopping service!", e) // Log de Error
            }
        }
        Log.d(TAG, "notifyPlaybackStopped: Processing finished.")
    }

    /**
     * LLAMADO DESDE JAVASCRIPT: Cuando el usuario PAUSA la reproducción DESDE LA WEB.
     * Ayuda a mantener sincronizado el estado de la notificación Android.
     */
    @JavascriptInterface // ¡Asegúrate de tener esta anotación!
    fun notifyPlaybackPaused() {
        Log.i(TAG, "notifyPlaybackPaused called from JS.") // Log de Información
        mainHandler.post {
            Log.d(TAG, "notifyPlaybackPaused: Preparing ACTION_PAUSE intent for MusicService.")
            val intent = Intent(context, MusicService::class.java).apply {
                action = MusicService.ACTION_PAUSE // Informar al servicio que pause (actualizar estado/notif)
            }
            Log.i(TAG, "notifyPlaybackPaused: Sending ACTION_PAUSE intent to MusicService.") // Log de Información
            try {
                context.startService(intent)
                Log.d(TAG, "notifyPlaybackPaused: Service pause requested.")
            } catch (e: Exception) {
                Log.e(TAG, "notifyPlaybackPaused: Error sending pause to service!", e) // Log de Error
            }
        }
        Log.d(TAG, "notifyPlaybackPaused: Processing finished.")
    }


    /**
     * LLAMADO DESDE JAVASCRIPT: Cuando el usuario REANUDA la reproducción DESDE LA WEB.
     * Ayuda a mantener sincronizado el estado de la notificación Android.
     */
    @JavascriptInterface // ¡Asegúrate de tener esta anotación!
    fun notifyPlaybackResumed() {
        Log.i(TAG, "notifyPlaybackResumed called from JS.") // Log de Información
        mainHandler.post {
            Log.d(TAG, "notifyPlaybackResumed: Preparing ACTION_PLAY intent for MusicService.")
            val intent = Intent(context, MusicService::class.java).apply {
                // Usamos ACTION_PLAY aquí también. El servicio sabrá si es reanudar o iniciar.
                action = MusicService.ACTION_PLAY
            }
            Log.i(TAG, "notifyPlaybackResumed: Sending ACTION_PLAY intent to MusicService (for resume).") // Log de Información
            try {
                // Si el servicio ya está corriendo, esto solo le enviará el comando.
                // Si no, lo iniciará. Usar startForegroundService es más seguro en O+.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "notifyPlaybackResumed: Service start/resume requested.")
            } catch (e: Exception) {
                Log.e(TAG, "notifyPlaybackResumed: Error sending play to service!", e) // Log de Error
            }
        }
        Log.d(TAG, "notifyPlaybackResumed: Processing finished.")
    }

    /**
     * LLAMADO DESDE JAVASCRIPT: Verifica el estado del reproductor.
     */

    /**
     * LLAMADO DESDE JAVASCRIPT: Actualiza el progreso de la canción.
     * @param currentTime Tiempo transcurrido en segundos.
     * @param totalTime Tiempo total de la canción en segundos.
     */
    @JavascriptInterface
    fun updateSongProgress(currentTime: Int, totalTime: Int) {
        Log.i(TAG, "updateSongProgress called from JS. Current time: $currentTime, Total time: $totalTime")
        // Llamar al método de MusicService para actualizar el progreso
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_UPDATE_PROGRESS
            putExtra("currentTime", currentTime)
            putExtra("totalTime", totalTime)
        }
        context.startService(intent)
    }

    // --- Funciones para que Kotlin (MainActivity/Service) llame a JavaScript ---

    /** LLAMADO DESDE KOTLIN: Solicita a la web reproducir la siguiente pista. */
    fun requestPlayNextInWebView() {
        Log.i(TAG, "requestPlayNextInWebView: Requesting JS function 'playNextTrack()'") // Log de Información
        // Asegúrate que 'playNextTrack' exista en tu JS y funcione correctamente
        callJavascript("javascript:window.VibeTurnPlayer.playNextTrack();")
    }

    /** LLAMADO DESDE KOTLIN: Solicita a la web reproducir la pista anterior. */
    fun requestPlayPreviousInWebView() {
        Log.i(TAG, "requestPlayPreviousInWebView: Requesting JS function 'playPreviousTrack()'") // Log de Información
        // Asegúrate que 'playPreviousTrack' exista en tu JS y funcione correctamente
        callJavascript("javascript:window.VibeTurnPlayer.playPreviousTrack();")
    }

    /** LLAMADO DESDE KOTLIN: Solicita a la web alternar Play/Pause. */
    fun requestTogglePlayPauseInWebView() {
        // Este log ya lo tenías, lo mantengo pero lo marco como Info
        Log.i(TAG, "requestTogglePlayPauseInWebView: Requesting JS function 'togglePlayPause()'") // Log de Información
        // Asegúrate que 'togglePlayPause' exista en tu JS y funcione correctamente
        callJavascript("javascript:window.VibeTurnPlayer.togglePlayPause();")
    }

    /**
     * Ejecuta un script JavaScript en el WebView deh forma segura en el hilo UI.
     *
     * @param script El código JavaScript a ejecutar (ej: "javascript:miFuncion();").
     */
    private fun callJavascript(script: String) {
        // Comprobar si webView es null antes de intentar usarlo
        val currentWebView = webView
        if (currentWebView == null) {
            Log.e(TAG, "callJavascript: WebView is null! Cannot execute script '$script'.") // Log de Error
            return
        }

        // Ejecutar en el hilo UI usando post
        currentWebView.post {
            Log.d(TAG, "callJavascript: Evaluating on UI thread: '$script'")
            // evaluateJavascript es asíncrono. El resultado (value) se recibe en el lambda (callback).
            currentWebView.evaluateJavascript(script) { value ->
                // Loguear el resultado de la ejecución de JS (puede ser útil para debug)
                // 'value' será 'null' si la función JS no devuelve nada o si hubo un error no capturado.
                // Si la función JS devuelve algo (ej: un booleano, un string), aparecerá aquí.
                Log.d(TAG, "callJavascript: JS execution ('$script') result: '$value'")
            }
        }
    }
    /*@JavascriptInterface
    fun checkPlayerStatus() {
        webView?.post {
            webView.evaluateJavascript("window.VibeTurnPlayer.getPlayerStatus();") { status ->

                if (status != "null") {
                    val isPlaying = status == "playing"
                    Log.d(TAG, "Player status from JS: $status, parsed as isPlaying: $isPlaying")

                    val serviceIntent = Intent(context, MusicService::class.java)

                    // Aquí está el IF para decidir la acción del Intent
                    if (isPlaying) {
                        serviceIntent.action = MusicService.ACTION_NOTIFICATION_PLAY
                        Log.d(TAG, "Sending command to MusicService: ACTION_WEB_CONFIRMED_PLAY")
                    } else {
                        serviceIntent.action = MusicService.ACTION_NOTIFICATION_PAUSE
                        Log.d(TAG, "Sending command to MusicService: ACTION_WEB_CONFIRMED_PAUSE")
                    }

                    try {
                        // Para Android O+, si el servicio podría NO estar corriendo y necesitas que
                        // se ponga en foreground por la acción ACTION_WEB_CONFIRMED_PLAY,
                        // considera startForegroundService. El MusicService llamará a startForeground().
                        if (isPlaying && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                            Log.d(TAG, "startForegroundService called for ${serviceIntent.action}")
                        } else {
                            context.startService(serviceIntent)
                            Log.d(TAG, "startService called for ${serviceIntent.action}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error calling startService/startForegroundService for ${serviceIntent.action}", e)
                    }
                }
            }
        }
    }*/
}