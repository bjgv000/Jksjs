package com.openmsucivibes.vibeturn

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager


class MainActivity : AppCompatActivity() {

    // Declaración 'lateinit var': Prometemos que inicializaremos esta variable antes de usarla
    // (lo haremos en onCreate). Es común para vistas.

    private lateinit var webView: WebView
    lateinit var musicService: MusicService
    private lateinit var webAppInterface: WebAppInterface
    private var webViewControlReceiver: BroadcastReceiver? = null // Puede ser null si no se registra
    private val handler = Handler(Looper.getMainLooper())
    private val delayedPauseAction = Runnable {
        // *** Este código se ejecutará 500ms DESPUÉS de que onPause() termine ***
        Log.d(TAG, "Delayed action after onPause: Executing WebView JS.")

        // Aquí pones el código JavaScript que quieres ejecutar después del delay
        webView?.post { // Aunque ya estamos en el hilo principal, webView.post es una buena práctica
            val javascriptToExecute = "javascript:window.VibeTurnPlayer.play();"

            // Example: Attempting to play video again (adjust the script as needed)
            // val javascriptToExecute = "javascript:if (typeof player !== 'undefined' && player.playVideo) { player.playVideo(); }"
            // Or using your VibeTurnPlayer:
            // val javascriptToExecute = "javascript:if (typeof window.VibeTurnPlayer !== 'undefined' && window.VibeTurnPlayer.play) { window.VibeTurnPlayer.play(); }"

            webView.evaluateJavascript(javascriptToExecute) { value ->
                // El bloque lambda opcional recibe el resultado de la última expresión en el JS.
                // Puedes loguear el resultado si es útil para depuración.
                Log.d(TAG, "onPause: evaluateJavascript result: $value")
            }
        }
    }
//    private val playerStatusChecker = object : Runnable {
//        override fun run() {
//            webAppInterface.checkPlayerStatus() // Call the new method
//            handler.postDelayed(this, 1000) // Check every second
//        }
//    }
    companion object {
        private const val TAG = "MainActivity"
        const val INITIAL_URL = "https://vibeturn.com/" // URL de tu web
    }

    // @SuppressLint es necesario porque añadir una interfaz JS puede ser un riesgo de seguridad
    // si la web no es de confianza, pero aquí es nuestra propia web controlada.
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Carga el diseño XML
        hideSystemNavigationBar()
        Log.d(TAG, "onCreate called")

        // Inicializamos el WebView usando su ID del XML
        webView = findViewById(R.id.webview)

        // --- Configuración del WebView ---
        webView.settings.apply {
            javaScriptEnabled = true // ¡Fundamental para que funcione la web y la interfaz!
            //domStorageEnabled = true // Necesario para localStorage/sessionStorage que usan muchas webs
            mediaPlaybackRequiresUserGesture = false // Intenta permitir autoplay (puede no funcionar)
            javaScriptCanOpenWindowsAutomatically = true // Para popups si tu web los usa
            loadWithOverviewMode = true // Ajusta el contenido al ancho del WebView
            useWideViewPort = true // Permite usar la etiqueta <meta name="viewport">
            setRenderPriority(WebSettings.RenderPriority.HIGH) // Mejorar rendimiento en BG
            cacheMode = WebSettings.LOAD_DEFAULT // Cache optimizado
           userAgentString = "Mozilla/5.0 (X11; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0" // Opcional: si necesitas un User Agent específico
            // setSupportZoom(true) // Habilitar zoom (opcional)
            // builtInZoomControls = true // Mostrar controles de zoom (opcional)
            // displayZoomControls = false // Ocultar los controles de zoom en pantalla (si builtInZoomControls es true)
        }

        // --- Interfaz JavaScript ---
        // Creamos la instancia del puente, pasándole el contexto (this Activity) y el WebView
        webAppInterface = WebAppInterface(this, webView)
        // Añadimos la interfaz al WebView. "Android" será el nombre del objeto en JavaScript.
        webView.addJavascriptInterface(webAppInterface, "Android")
        Log.d(TAG, "JavaScriptInterface 'Android' added")

        // --- WebChromeClient (Opcional pero útil para Debug) ---
        // Permite ver los mensajes de la consola JS en el Logcat de Android Studio.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WebViewConsole", "${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}")
                }
                return true // Indicamos que hemos manejado el mensaje
            }
            // Aquí podrías manejar otros eventos como onProgressChanged, onReceivedTitle, etc.
        }

        // --- WebViewClient ---
        // Controla cómo se cargan las URLs. Esencial para que la navegación se quede DENTRO del WebView.
        webView.webViewClient = object : WebViewClient() {
            // Este método se llama cuando el WebView va a cargar una URL.
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                Log.d(TAG, "Loading URL: $url")
                // Cargamos la URL dentro del mismo WebView. Si devolviéramos 'false',
                // Android podría intentar abrirla en un navegador externo.
                url?.let { view?.loadUrl(it) } // Carga segura por si url es null
                return true // Indicamos que hemos manejado la carga
            }

            // En tu WebViewClient.onPageFinished

            // Se llama cuando la página ha terminado de cargar.
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page Finished: $url")
                val script = """
        (function() {
            var iframes = document.getElementsByTagName('iframe');
            var youtubeIframeFound = false;
            var testVideoId = "dQw4w9WgXcQ"; // Datos de prueba: Video ID (Rick Astley - Never Gonna Give You Up)
            var testTitle = "Test Song Title"; // Datos de prueba: Título
            var testArtist = "Test Artist Name"; // Datos de prueba: Artista
            var testArtworkUrl = "https://www.example.com/test_artwork.jpg"; // Datos de prueba: URL de la carátula

            for (var i = 0; i < iframes.length; i++) {
                if (iframes[i].src && iframes[i].src.includes('youtube.com/embed')) {
                    youtubeIframeFound = true;
                    // Opcional: Si quieres intentar extraer el videoId real de la URL del iframe src
                    // var srcUrl = new URL(iframes[i].src);
                    // var pathnameParts = srcUrl.pathname.split('/');
                    // if (pathnameParts.length > 2 && pathnameParts[2]) {
                    //     testVideoId = pathnameParts[2];
                    // }
                    break; // Encontramos un iframe de YouTube, podemos salir del bucle
                }
            }

            if (youtubeIframeFound) {
                Log.d('InjectedJS', 'YouTube iframe found, calling notifySongChanged with test data.');
                // Llama a notifySongChanged en la interfaz Android
                // Pasa los datos de prueba
                Android.notifySongChanged(testVideoId, testTitle, testArtist, testArtworkUrl);
            } else {
                Log.d('InjectedJS', 'No YouTube iframe found.');
                // Opcional: Notificar de alguna manera a Android si no se encontró nada,
                // o simplemente no hacer nada. Depende de si necesitas manejar este caso.
                // Android.onNoIframeFound(); // Necesitarías agregar este método a WebAppInterface
            }
        })();
    """.trimIndent()

                view?.evaluateJavascript(script, null)
                // Podrías ejecutar JS aquí si necesitas hacer algo después de que cargue la página
                // view?.evaluateJavascript("javascript:console.log('Page loaded!');", null)
            }

            // Se llama si ocurre un error al cargar la página.
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, "WebView Error: $errorCode - $description URL: $failingUrl")
                // Aquí podrías mostrar un mensaje de error o una página de error local
                // view?.loadUrl("file:///android_asset/error.html")
            }
        }

        // --- Cargar la URL Inicial ---
        // Solo cargamos si es la primera vez que se crea la Activity (savedInstanceState es null)
        // o si el estado guardado no incluye ya una URL (para evitar recargar en rotaciones de pantalla si manejas el estado)
        if (savedInstanceState == null) {
            Log.d(TAG,"Loading initial URL: $INITIAL_URL")
            webView.loadUrl(INITIAL_URL)
        }
        // Si necesitaras manejar el estado del webview en rotaciones, deberías usar
        // savedInstanceState para guardar y restaurar la URL o el estado del webview.
        // webView.saveState(outState) y webView.restoreState(savedInstanceState)

        // --- Configurar Receptor de Comandos del Servicio ---
        setupWebViewControlReceiver()
        //handler.post(playerStatusChecker) // Start the periodic check
    }
    private fun hideSystemNavigationBar() {
        // Accede a la vista decor de la ventana
        val decorView = window.decorView

        // Configura las banderas de la interfaz de usuario
        @Suppress("DEPRECATION") // Las flags están deprecadas, pero son la forma directa de ocultar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Para API 19 (KitKat) y superior
            // SYSTEM_UI_FLAG_HIDE_NAVIGATION: Oculta la barra de navegación.
            // SYSTEM_UI_FLAG_IMMERSIVE_STICKY: Hace que la barra aparezca temporalmente
            //                                   con un deslizamiento, y luego se oculte de nuevo.
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        } else {
            // Para API inferiores (menos común ahora)
            // Esta bandera solo oculta la barra en algunas versiones y puede no ser consistente.
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }

        // Opcional: Si quieres que tu contenido se extienda detrás de la barra
        //            cuando está oculta, también puedes usar:
        // decorView.systemUiVisibility = decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        // Escuchar cambios en la visibilidad de la UI (opcional, para manejar redimensionamiento)
        // decorView.setOnSystemUiVisibilityChangeListener { visibility ->
        //     // Haz algo si la visibilidad de la UI cambia
        //     if (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
        //         // La barra de navegación está visible
        //     } else {
        //         // La barra de navegación está oculta
        //     }
        // }
    }
    // --- Manejo del estado (Ejemplo básico para guardar/restaurar URL en rotación) ---
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState) // Guarda el estado de navegación del WebView
        Log.d(TAG, "onSaveInstanceState: WebView state saved")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState) // Restaura el estado
        Log.d(TAG, "onRestoreInstanceState: WebView state restored")
    }


    // --- Configurar BroadcastReceiver ---
    // Escucha mensajes enviados desde el MusicService
    private fun setupWebViewControlReceiver() {
        webViewControlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.openmsucivibes.vibeturn.WEBVIEW_CONTROL") {
                    val command = intent.getStringExtra("COMMAND")
                    Log.d(TAG, "Received command: $command")

                    when (command) {
                        MusicService.ACTION_NEXT -> {
                            Log.d(TAG, "Executing NEXT")
                            webAppInterface.requestPlayNextInWebView()
                        }
                        MusicService.ACTION_PREVIOUS -> {
                            Log.d(TAG, "Executing PREVIOUS")
                            webAppInterface.requestPlayPreviousInWebView()
                        }
                        MusicService.ACTION_REQUEST_TOGGLE_PLAY_PAUSE -> {
                            Log.d(TAG, "Executing TOGGLE PAUSE")
                            webAppInterface.requestTogglePlayPauseInWebView()
                        }
                    }
                }
            }
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            webViewControlReceiver!!,
            IntentFilter("com.openmsucivibes.vibeturn.WEBVIEW_CONTROL")
        )
    }

    /*override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity onPause: Attempting to force play in WebView.")

        // **Llamada para intentar forzar la reproducción**
        // Usa la variable miembro webAppInterface que guardaste en onCreate

        // If you want to use your VibeTurnPlayer object:
        // val javascriptToExecute = "javascript:if (typeof window.VibeTurnPlayer !== 'undefined' && window.VibeTurnPlayer.play) { window.VibeTurnPlayer.play(); }"
        // Or if you specifically want to call togglePlayPause:
        // val javascriptToExecute = "javascript:if (typeof window.VibeTurnPlayer !== 'undefined' && window.VibeTurnPlayer.togglePlayPause) { window.VibeTurnPlayer.togglePlayPause(); }"
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        handler.postDelayed(delayedPauseAction, 100)
        // Si quieres ser más explícito y llamar a player.playVideo() directamente:
        // webView?.post {
        //     Log.d(TAG, "onPause: Executing player.playVideo() Javascript.")
        //     webView.evaluateJavascript("javascript:if (typeof player !== 'undefined' && player.playVideo) { player.playVideo(); }", null)
        // }


        // Opcional pero recomendado: Si llamaste a webView.onResume() en onResume,
        // quizás quieras llamar a webView.onPause() aquí para un ciclo de vida más completo
        // aunque esto no garantiza evitar la pausa de medios.
        // webView?.onPause() // Comenta o descomenta según tu necesidad
    }*/
    // --- Botón "Atrás" del dispositivo ---
    // Queremos que vaya hacia atrás en el historial del WebView si es posible.
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack() // Navega a la página anterior en el historial del WebView
            Log.d(TAG, "onBackPressed: WebView navigating back")
        } else {
            super.onBackPressed() // Comportamiento normal (cerrar la Activity)
            Log.d(TAG, "onBackPressed: No history, calling super")
        }
    }

    // --- Limpieza al destruir la Activity ---
    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        // Desregistrar el BroadcastReceiver para evitar memory leaks
        webViewControlReceiver?.let {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            Log.d(TAG, "WebViewControlReceiver unregistered")
        }
        webViewControlReceiver = null // Limpiar referencia

        // --- Limpieza profunda del WebView ---
        // Es importante destruir el WebView correctamente para liberar recursos y evitar leaks.
        webView.apply {
            // Parar cualquier carga o JS en ejecución
            stopLoading()
            onPause() // Pausar estado interno
            // Quitar la interfaz JS antes de destruir
            removeJavascriptInterface("Android")
            // Cargar una página en blanco ayuda a liberar memoria de la página actual
            loadUrl("https://vibeturn.com/")
            // Limpiar historial, caché (opcional, pero bueno para limpieza completa)
            // clearHistory()
            // clearCache(true)
            // clearFormData()
            // Anular los clientes
          //  webViewClient = null
         //   webChromeClient = null
            // Finalmente, destruir el WebView
            destroy()
        }
        Log.d(TAG, "WebView destroyed")

        // Considera detener el servicio si la app se destruye completamente.
        // Depende de si quieres que la música siga si el usuario cierra la app desde recientes.
        // val stopIntent = Intent(this, MusicService::class.java)
        // stopService(stopIntent)
        // Log.d(TAG, "Requested MusicService stop on destroy")

        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy completed")
    }
}
class MyWebView : WebView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE && visibility != View.INVISIBLE) super.onWindowVisibilityChanged(visibility)
    }
}
