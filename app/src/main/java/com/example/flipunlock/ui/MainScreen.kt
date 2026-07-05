package com.example.flipunlock.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.flipunlock.Prefs

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }

    var showTip by remember { mutableStateOf(true) }
    var globalFullscreen by remember {
        mutableStateOf(prefs.getBoolean(Prefs.GLOBAL_FULLSCREEN, false))
    }
    var showAppList by remember { mutableStateOf(false) }

    // Tip dialog
    if (showTip) {
        AlertDialog(
            onDismissRequest = { showTip = false },
            title = { Text("使用提示") },
            text = {
                Column {
                    Text("· 修改设置后需重启对应应用生效")
                    Text("· 应用全屏需在 LSPosed 中勾选该应用的作用域")
                    Text("· 全局全屏开启后，下方应用开关失效")
                }
            },
            confirmButton = {
                TextButton(onClick = { showTip = false }) {
                    Text("知道了")
                }
            }
        )
    }

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
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            "v2.6-dev",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Help button
        TextButton(
            onClick = { showTip = true },
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("使用说明", fontSize = 13.sp)
        }

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
                        "切割挖孔 + 强制全屏布局（所有应用）",
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

        // Per-app settings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !globalFullscreen) { showAppList = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Apps,
                        contentDescription = null,
                        tint = if (globalFullscreen)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "应用全屏设置",
                            fontWeight = FontWeight.Medium,
                            color = if (globalFullscreen)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (globalFullscreen) "全局全屏已开启，无需单独设置"
                            else "选择哪些应用强制全屏",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // LSPosed reminder
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "请在 LSPosed 管理器中勾选需要全屏的应用作为作用域，否则下方设置不生效。",
                modifier = Modifier.padding(16.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
