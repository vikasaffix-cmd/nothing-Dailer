package com.example.allinonedailer.widget

import android.app.Activity
import android.os.Bundle
import com.example.allinonedailer.data.CallPlacer

/**
 * Invisible activity behind the widget's call taps. Widgets may only start
 * activities (no service or receiver trampolines), so this places the call
 * through [CallPlacer] and closes. Without the call permission CallPlacer
 * opens the keypad with the number filled in instead.
 */
class WidgetCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
        if (!number.isNullOrBlank()) CallPlacer.placeCall(this, number)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
