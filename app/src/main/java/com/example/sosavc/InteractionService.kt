package com.example.sosavc

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Context

class InteractionService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            // Considera qualquer evento como interação
            val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_interaction", System.currentTimeMillis()).apply()
        }
    }

    override fun onInterrupt() {}
} 