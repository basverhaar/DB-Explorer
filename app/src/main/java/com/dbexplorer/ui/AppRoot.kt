package com.dbexplorer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dbexplorer.DbExplorerApp
import com.dbexplorer.data.MediaType

@Composable
fun app(): DbExplorerApp = LocalContext.current.applicationContext as DbExplorerApp

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpen = { id -> nav.navigate("detail/${Uri.encode(id)}") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("detail/{id}") { entry ->
            DetailScreen(id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val type = MediaType.entries[tab]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DB Explorer") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                MediaType.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = i == tab,
                        onClick = { tab = i },
                        icon = { Icon(t.icon(), null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            RefreshBanner()
            BrowseScreen(type = type, onOpen = onOpen)
        }
    }
}
