package com.example.dwelly

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.dwelly.ui.navigation.DwellyNavHost
import com.example.dwelly.ui.theme.DwellyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dwelly is always dark, so the system bars always use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val dwelly = (application as DwellyApplication).dwellyService
        setContent {
            DwellyTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DwellyNavHost(dwelly, Modifier.padding(innerPadding))
                }
            }
        }
    }
}
