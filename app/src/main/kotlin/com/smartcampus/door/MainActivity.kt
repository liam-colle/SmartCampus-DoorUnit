package com.smartcampus.door

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.smartcampus.door.ui.AppRoot
import com.smartcampus.door.ui.DoorTheme

class MainActivity : ComponentActivity() {
    private lateinit var cards: NfcCardReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterKioskMode()
        val app = application as DoorApp
        cards = NfcCardReader(this, app::onCardRead)
        setContent {
            DoorTheme {
                val phase by app.phase.collectAsState()
                AppRoot(phase = phase, app = app)
            }
        }
    }

    // Reader mode only works in the foreground; the kiosk is always there, but a dialog or the
    // settings screen must hand the antenna back.
    override fun onResume() {
        super.onResume()
        cards.start()
    }

    override fun onPause() {
        cards.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterKioskMode() // the system bars come back after a dialog or a swipe
    }

    /** Full screen, no bars, never dims: the terminal is a sign, not a phone. */
    private fun enterKioskMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) hideBarsLegacy()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Before Android 11 the system clears the hide flags after a touch or when the keyboard closes, and the
     * bars stay on screen (and cover the bottom of the alert). Setting the flags again whenever they change
     * keeps the terminal in full screen.
     */
    @Suppress("DEPRECATION")
    private fun hideBarsLegacy() {
        val decor = window.decorView
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decor.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) decor.post { hideBarsLegacy() }
        }
    }
}
