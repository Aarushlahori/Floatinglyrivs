package com.example.floatinglyrics

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startButton = Button(this).apply {
            text = "Grant Permissions & Start Overlay"
            setOnClickListener { checkAndLaunch() }
        }
        setContentView(startButton)
    }

    private fun checkAndLaunch() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(overlayIntent)
            return
        }

        // Open Notification Access Settings if required
        val notifAccessIntent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        startActivity(notifAccessIntent)

        startService(Intent(this, FloatingLyricsService::class.java))
    }
}
