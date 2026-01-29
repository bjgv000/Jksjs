package com.openmsucivibes.vibeturn

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.os.Handler
import android.os.Looper
import android.content.Intent
import kotlin.jvm.java
import kotlin.text.toLong

class splash_activity : AppCompatActivity() {
    private val SPLASH_DELAY: Long = 3000.toLong()// 2 segundos en milisegundos
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Handler(Looper.getMainLooper()).postDelayed({
            // Crear el Intent para iniciar MainActivity
            val intent = Intent(this@splash_activity, MainActivity::class.java)
            startActivity(intent)

            // Finalizar la splash_activity para que el usuario no pueda volver a ella
            // presionando el botón de retroceso
            finish()
        }, SPLASH_DELAY)

    }
}