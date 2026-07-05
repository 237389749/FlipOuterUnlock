@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.flipunlock.ui

import android.content.Context
import android.content.pm.PackageManager
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
import com.example.flipunlock.module

data class AppInfo(
    val packageName: String,
    val label: String,
)

@Composable
fun FullscreenAppListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { module?.getRemotePreferences(Prefs.NAME) }

    val apps = remember { loadInstalledApps(context) }

    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val appStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            apps.forEach { put(it.packageName, prefs?.getBoolean(Prefs.fullscreenKey(it.packageName), false) ?: false) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("应用全屏设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "返回")
                }
            }
        )

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
                            onCheckedChange = { checked: Boolean ->
                                appStates[app.packageName] = checked
                                prefs?.edit()?.putBoolean(Prefs.fullscreenKey(app.packageName), checked)?.apply()
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
