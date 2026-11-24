package com.leo.secondbrain

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.WindowInfoTracker
import com.leo.secondbrain.data.Appearance
import com.leo.secondbrain.ui.BrainApp
import com.leo.secondbrain.ui.BrainViewModel
import com.leo.secondbrain.ui.theme.SecondBrainTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val launchRequest = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launchRequest.value = intent
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(0, 0),
            navigationBarStyle = SystemBarStyle.auto(0, 0)
        )
        val windowLayout = WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
        setContent {
            val model = viewModel { BrainViewModel(application as BrainApplication) }
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val layout by windowLayout.collectAsStateWithLifecycle(initialValue = null)
            val requested by launchRequest.collectAsStateWithLifecycle()
            val dark = when (preferences.appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            SecondBrainTheme(dark, preferences) {
                BrainApp(model, preferences, layout, requested) { launchRequest.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequest.value = intent
    }

    override fun onResume() {
        super.onResume()
        val app = application as BrainApplication
        app.automaticOrganization.kick()
        app.edits.retry()
        app.reminders.prepareChannel()
        app.reminders.requestRefresh()
    }
}
