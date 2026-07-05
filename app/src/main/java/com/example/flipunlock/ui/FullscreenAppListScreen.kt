package com.example.flipunlock.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flipunlock.Prefs

data class AppInfo(
    val packageName: String,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenAppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }

    val apps = remember {
        loadInstalledApps(context)
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    // Track per-app state
    val appStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            apps.forEach { put(it.packageName, prefs.getBoolean(Prefs.fullscreenKey(it.packageName), false)) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        TopAppBar(
            title = { Text("应用全屏设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "返回")
                }
            }
        )

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索应用...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true
        )

        // App list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(filteredApps) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(
                                app.packageName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        Switch(
                            checked = appStates[app.packageName] ?: false,
                            onCheckedChange = { checked ->
                                appStates[app.packageName] = checked
                                prefs.edit().putBoolean(Prefs.fullscreenKey(app.packageName), checked).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun loadInstalledApps(context: Context): List<AppInfo> {
    return runCatching {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .map { AppInfo(it.packageName, it.loadLabel(pm).toString()) }
    }.getOrDefault(emptyList())
}
