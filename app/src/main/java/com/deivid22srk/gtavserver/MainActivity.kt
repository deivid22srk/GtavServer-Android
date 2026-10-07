package com.deivid22srk.gtavserver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.gtavserver.data.Prefs
import com.deivid22srk.gtavserver.server.ServerService
import com.deivid22srk.gtavserver.ui.FolderPickerScreen
import com.deivid22srk.gtavserver.ui.StatusScreen
import com.deivid22srk.gtavserver.ui.theme.GtavServerTheme

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    // Android <= 10: READ_EXTERNAL_STORAGE em tempo de execução; 13+: notificações
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // Android 11+: tela de "Todos os arquivos" (sem SAF, como especificado)
    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        requestLegacyNotificationsIfNeeded()
        setContent {
            GtavServerTheme {
                val running by ServerBus.running.collectAsStateWithLifecycle()
                val starting by ServerBus.starting.collectAsStateWithLifecycle()
                val url by ServerBus.url.collectAsStateWithLifecycle()
                val error by ServerBus.error.collectAsStateWithLifecycle()
                val logs by ServerBus.logs.collectAsStateWithLifecycle()
                var screen by remember { mutableStateOf("status") }
                var folder by remember { mutableStateOf(prefs.folder) }
                var port by remember { mutableStateOf(prefs.port) }
                var hasStorage by remember { mutableStateOf(hasFullStorage()) }

                if (screen == "picker") {
                    FolderPickerScreen(
                        initialPath = folder,
                        onPick = { picked ->
                            folder = picked
                            prefs.folder = picked
                            screen = "status"
                        },
                        onBack = { screen = "status" },
                    )
                } else {
                    StatusScreen(
                        running = running,
                        starting = starting,
                        url = url,
                        error = error,
                        logs = logs,
                        folder = folder,
                        port = port,
                        hasStorage = hasStorage,
                        onRequestStorage = {
                            requestStorageAccess()
                            hasStorage = hasFullStorage()
                        },
                        onPortChange = { p ->
                            port = p
                            prefs.port = p
                        },
                        onStart = {
                            if (!hasFullStorage()) {
                                requestStorageAccess()
                                hasStorage = hasFullStorage()
                            } else {
                                folder = prefs.folder
                                ServerService.start(this, folder, port)
                            }
                        },
                        onStop = { ServerService.stop(this) },
                        onPickFolder = { screen = "picker" },
                        onIgnoreBattery = { requestIgnoreBattery() },
                        batteryIgnored = isIgnoringBatteryOptimizations(),
                    )
                }
            }
        }
    }

    private fun hasFullStorage(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLegacyNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(perm))
            }
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
            ).setData(Uri.parse("package:$packageName"))
            try {
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                manageStorageLauncher.launch(
                    Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBattery() {
        @Suppress("BatteryLife")
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            batteryLauncher.launch(intent)
        } catch (e: Exception) {
            batteryLauncher.launch(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }
}
