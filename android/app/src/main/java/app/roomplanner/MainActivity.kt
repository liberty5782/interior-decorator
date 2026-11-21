package app.roomplanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.roomplanner.api.Room
import app.roomplanner.api.RoomPlannerApi
import app.roomplanner.planner.PlannerScreen
import app.roomplanner.ui.CaptureScreen
import app.roomplanner.ui.CatalogScreen
import app.roomplanner.ui.HomeScreen

private sealed interface Screen {
    data object Home : Screen
    data class Capture(val api: RoomPlannerApi, val room: Room) : Screen
    data class Plan(val api: RoomPlannerApi, val room: Room) : Screen
    /** [returnTo] is where Back goes: the home screen or the planner it was opened from. */
    data class Catalog(val api: RoomPlannerApi, val returnTo: Screen) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                Surface(Modifier.fillMaxSize()) {
                    when (val s = screen) {
                        Screen.Home -> HomeScreen(
                            prefs = prefs,
                            onCapture = { api, room -> screen = Screen.Capture(api, room) },
                            onPlan = { api, room -> screen = Screen.Plan(api, room) },
                            onCatalog = { api -> screen = Screen.Catalog(api, Screen.Home) },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        is Screen.Capture -> CaptureScreen(
                            api = s.api,
                            room = s.room,
                            onBack = { screen = Screen.Home },
                        )
                        is Screen.Plan -> PlannerScreen(
                            api = s.api,
                            room = s.room,
                            onBack = { screen = Screen.Home },
                            onOpenCatalog = { screen = Screen.Catalog(s.api, s) },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        is Screen.Catalog -> CatalogScreen(
                            api = s.api,
                            onBack = { screen = s.returnTo },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    }
                }
            }
        }
    }
}
