package app.captions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.captions.ui.home.HomeScreen
import app.captions.ui.live.LiveCaptionScreen
import app.captions.ui.settings.SettingsScreen
import app.captions.ui.theme.CaptionsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CaptionsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenLive = { navController.navigate("live") },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("live") {
                            LiveCaptionScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
