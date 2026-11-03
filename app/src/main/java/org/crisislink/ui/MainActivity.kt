package org.crisislink.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.crisislink.CrisisLinkApp
import org.crisislink.service.MeshForegroundService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = (application as CrisisLinkApp).runtime
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2C3E50), secondary = Color(0xFF1E8449))) {
                var granted by remember { mutableStateOf(hasPermissions()) }
                if (granted) {
                    LaunchedStart(runtime)
                    AppRoot(runtime)
                } else {
                    PermissionGate { granted = hasPermissions() }
                }
            }
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
        } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermissions() = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @Composable
    private fun LaunchedStart(runtime: org.crisislink.mesh.MeshRuntime) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            getSharedPreferences("crisislink", MODE_PRIVATE).edit().putBoolean("onboarded", true).apply()
            if (runtime.backgroundEnabled) MeshForegroundService.start(this@MainActivity) else runtime.start()
        }
    }

    @Composable
    private fun PermissionGate(onResult: () -> Unit) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("CrisisLink", style = MaterialTheme.typography.headlineMedium)
            Text(
                "CrisisLink passes messages phone to phone over Bluetooth, with no internet. It needs Bluetooth access to find " +
                    "nearby phones, and notifications to keep running in the background.",
            )
            Button(onClick = { launcher.launch(requiredPermissions().toTypedArray()) }) { Text("Allow Bluetooth and notifications") }
        }
    }

    companion object {
        /** Opens the system prompt that exempts the app from battery optimisation, so the mesh survives doze. */
        fun requestBatteryExemption(activity: ComponentActivity) {
            val pm = activity.getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                activity.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
                )
            }
        }
    }
}
