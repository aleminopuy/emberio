package app.emberio.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.emberio.EmberioApp
import app.emberio.service.MugService

class MainActivity : ComponentActivity() {
    private val repository get() = (application as EmberioApp).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmberioTheme {
                MainScreen(repository)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        repository.refreshPermissions()
        MugService.startIfReady(this)
    }
}

private val Ember = Color(0xFFFF5500)

@Composable
fun EmberioTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Ember)
        else -> lightColorScheme(primary = Ember)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
