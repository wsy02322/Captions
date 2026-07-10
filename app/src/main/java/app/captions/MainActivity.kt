package app.captions

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private var navigateTo by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigateTo = intent?.getStringExtra(EXTRA_NAVIGATE_TO)
        enableEdgeToEdge()
        setContent {
            CaptionsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val target = navigateTo
                    LaunchedEffect(target) {
                        if (target == ROUTE_LIVE) {
                            navController.navigate("live") {
                                launchSingleTop = true
                            }
                            navigateTo = null
                        }
                    }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigateTo = intent.getStringExtra(EXTRA_NAVIGATE_TO)
    }

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val ROUTE_LIVE = "live"
    }
}
