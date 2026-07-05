package com.example.flipunlock.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flipunlock.module
import com.example.flipunlock.Prefs

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }

    var globalFullscreen by remember {
        mutableStateOf(prefs.getBoolean(Prefs.GLOBAL_FULLSCREEN, true))
    }

    var showAppList by remember { mutableStateOf(false) }

    if (showAppList) {
        FullscreenAppListScreen(onBack = { showAppList = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "FlipOuterUnlock",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            "v2.5",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Global fullscreen toggle
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("全局全屏", fontWeight = FontWeight.Medium)
                    Text(
                        "切割外屏挖孔并强制全屏",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Switch(
                    checked = globalFullscreen,
                    onCheckedChange = {
                        globalFullscreen = it
                        prefs.edit().putBoolean(Prefs.GLOBAL_FULLSCREEN, it).apply()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Per-app settings entry
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAppList = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Apps, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("应用全屏设置", fontWeight = FontWeight.Medium)
                        Text(
                            "选择哪些应用强制全屏",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Icon(Icons.Filled.Settings, contentDescription = null)
            }
        }
    }
}
