package com.aios.flitr.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aios.flitr.ui.theme.FlitrTheme
import com.aios.flitr.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: FlitrViewModel = viewModel()
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()

            // 用户选了"浅色"但仍希望跟随系统时, 系统深色下自动切到高对比深色。
            // 显式选了深色/纯黑时不覆盖用户意图。
            val effectiveMode = remember(prefs.themeMode, prefs.followSystemDark, systemDark) {
                if (prefs.themeMode == ThemeMode.Light && prefs.followSystemDark && systemDark) {
                    ThemeMode.DarkHighContrast
                } else {
                    prefs.themeMode
                }
            }

            FlitrTheme(mode = effectiveMode, accent = prefs.accent) {
                FlitrRoot(vm)
            }
        }
    }
}
