package com.gpm.virtual

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpm.virtual.ui.components.DrawerDestination
import com.gpm.virtual.ui.components.NavDrawerContent
import com.gpm.virtual.ui.screens.FavoritesScreen
import com.gpm.virtual.ui.screens.MapScreen
import com.gpm.virtual.ui.screens.SettingsScreen
import com.gpm.virtual.ui.theme.MyApplicationTheme
import com.gpm.virtual.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val config by viewModel.config.collectAsStateWithLifecycle()

            MyApplicationTheme(themeMode = config.themeMode) {
                GpmApp(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val config = viewModel.config.value
        if (config.isFloatingOverlayEnabled && android.provider.Settings.canDrawOverlays(this)) {
            com.gpm.virtual.service.FloatingOverlayService.start(this)
        }
    }
}

@Composable
fun GpmApp(viewModel: MainViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(DrawerDestination.MAP) }

    // Handle back button: close drawer if open, or return to Map if on another screen
    BackHandler(enabled = drawerState.isOpen || currentScreen != DrawerDestination.MAP) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            currentScreen = DrawerDestination.MAP
        }
    }

    val isLsposedActive by viewModel.isLsposedActive.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NavDrawerContent(
                currentDestination = currentScreen,
                onDestinationSelected = { dest ->
                    currentScreen = dest
                    scope.launch { drawerState.close() }
                },
                isLsposedActive = isLsposedActive
            )
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            when (currentScreen) {
                DrawerDestination.MAP -> {
                    MapScreen(
                        viewModel = viewModel,
                        onOpenDrawer = {
                            scope.launch { drawerState.open() }
                        },
                        onNavigateFavorites = {
                            currentScreen = DrawerDestination.FAVORITES
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                DrawerDestination.FAVORITES -> {
                    FavoritesScreen(
                        viewModel = viewModel,
                        onNavigateBack = { currentScreen = DrawerDestination.MAP },
                        onSelectLocation = { lat, lng, name ->
                            viewModel.updateTargetLocation(lat, lng, name)
                            currentScreen = DrawerDestination.MAP
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                DrawerDestination.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = { currentScreen = DrawerDestination.MAP },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
