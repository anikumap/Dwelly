package com.example.dwelly

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
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
