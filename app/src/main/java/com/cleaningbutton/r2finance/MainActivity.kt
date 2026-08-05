package com.cleaningbutton.r2finance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.cleaningbutton.r2finance.ui.navigation.AppNavHost
import com.cleaningbutton.r2finance.ui.theme.R2FinanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as R2FinanceApplication
        setContent {
            R2FinanceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(container = app.container)
                }
            }
        }
    }
}
