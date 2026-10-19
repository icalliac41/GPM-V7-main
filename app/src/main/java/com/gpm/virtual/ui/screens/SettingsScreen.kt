package com.gpm.virtual.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Widgets
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpm.virtual.ui.theme.AccentGreen
import com.gpm.virtual.ui.theme.AccentOrange
import com.gpm.virtual.viewmodel.MainViewModel
import com.gpm.virtual.data.CancelCantikController
import com.gpm.virtual.data.SortirSettingsController
import java.util.Locale
import androidx.compose.material3.Checkbox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val injectionScope by viewModel.injectionScope.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var themeDropdownExpanded by remember { mutableStateOf(false) }
    var mapDropdownExpanded by remember { mutableStateOf(false) }

    BackHandler { onNavigateBack() }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            viewModel.setFloatingOverlay(true)
            com.gpm.virtual.service.FloatingOverlayService.start(context)
        } else {
            viewModel.setFloatingOverlay(false)
        }
    }

    var accuracyInput by remember(config.accuracy) {
        mutableStateOf(config.accuracy.toInt().toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ================= 1. KATEGORI: GENERAL =================
            Text("GENERAL", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().clickable { themeDropdownExpanded = true }.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DarkMode, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Dark theme", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    text = when (config.themeMode.uppercase()) {
                                        "DARK" -> "Selalu Gelap"; "LIGHT" -> "Selalu Terang"; else -> "Follow system"
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Box {
                            DropdownMenu(expanded = themeDropdownExpanded, onDismissRequest = { themeDropdownExpanded = false }) {
                                DropdownMenuItem(text = { Text("Follow system") }, onClick = { viewModel.setThemeMode("SYSTEM"); themeDropdownExpanded = false })
                                DropdownMenuItem(text = { Text("Light") }, onClick = { viewModel.setThemeMode("LIGHT"); themeDropdownExpanded = false })
                                DropdownMenuItem(text = { Text("Dark") }, onClick = { viewModel.setThemeMode("DARK"); themeDropdownExpanded = false })
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().clickable { mapDropdownExpanded = true }.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Map", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    text = when (config.mapType) {
                                        "CUSTOM" -> "Kustom (Sesuai Gaya JSON)"; "SATELLITE" -> "Satelit"; "TERRAIN" -> "Terrain"; else -> "Normal (OpenStreetMap)"
                                    },
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Box {
                            DropdownMenu(expanded = mapDropdownExpanded, onDismissRequest = { mapDropdownExpanded = false }) {
                                DropdownMenuItem(text = { Text("Kustom (Gaya JSON Abu-Abu/Biru)") }, onClick = { viewModel.setMapType("CUSTOM"); mapDropdownExpanded = false })
                                DropdownMenuItem(text = { Text("Normal (OpenStreetMap)") }, onClick = { viewModel.setMapType("NORMAL"); mapDropdownExpanded = false })
                                DropdownMenuItem(text = { Text("Satelit (USGS Satellite)") }, onClick = { viewModel.setMapType("SATELLITE"); mapDropdownExpanded = false })
                                DropdownMenuItem(text = { Text("Terrain (OpenTopo)") }, onClick = { viewModel.setMapType("TERRAIN"); mapDropdownExpanded = false })
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Navigation, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Use joystick", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AccentOrange.copy(alpha = 0.2f)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                        Text("Experimental", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AccentOrange)
                                    }
                                }
                                Text("Aktifkan kontrol overlay joystick di layar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.isJoystickEnabled, onCheckedChange = { viewModel.setJoystick(it) }, modifier = Modifier.testTag("toggle_joystick_setting"))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ================= KATEGORI: ARAH PUTARAN / HEADING =================
            Text("ORIENTASI ARAH HADAP (0° – 360°)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))

            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Navigation, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Kunci Arah Hadap (Manual 0° – 360°)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    text = if (config.isCustomBearingEnabled) "Terkunci pada sudut ${String.format(Locale.US, "%.0f", config.bearing)}°" else "Otomatis / Realistis (Berputar halus mengikuti simulasi)",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(checked = config.isCustomBearingEnabled, onCheckedChange = { viewModel.setCustomBearingEnabled(it) }, modifier = Modifier.testTag("toggle_custom_bearing"))
                    }
                    AnimatedVisibility(visible = config.isCustomBearingEnabled) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                            val normBearing = ((config.bearing % 360f) + 360f) % 360f
                            val dirName = when (normBearing) {
                                in 337.5f..360f, in 0f..22.5f -> "Utara (North)"; in 22.5f..67.5f -> "Timur Laut (Northeast)"
                                in 67.5f..112.5f -> "Timur (East)"; in 112.5f..157.5f -> "Tenggara (Southeast)"
                                in 157.5f..202.5f -> "Selatan (South)"; in 202.5f..247.5f -> "Barat Daya (Southwest)"
                                in 247.5f..292.5f -> "Barat (West)"; else -> "Barat Laut (Northwest)"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("Derajat Orientasi", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${String.format(Locale.US, "%.0f", normBearing)}° — $dirName", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(value = normBearing, onValueChange = { viewModel.setBearing(it) }, valueRange = 0f..360f, steps = 71, modifier = Modifier.fillMaxWidth().testTag("slider_bearing"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                listOf(0f to "0° (U)", 90f to "90° (T)", 180f to "180° (S)", 270f to "270° (B)").forEach { (deg, label) ->
                                    val isCur = kotlin.math.abs(normBearing - deg) < 2.5f
                                    androidx.compose.material3.Surface(shape = RoundedCornerShape(8.dp), color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.weight(1f).clickable { viewModel.setBearing(deg) }) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isCur) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ================= KATEGORI: BERBAGI LOKASI =================
            Text("BERBAGI LOKASI (LOCATION SHARING)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CloudSync, null, tint = if (config.isLocationSharingEnabled) AccentGreen else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Sinkronisasi Berbagi Lokasi", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (config.isLocationSharingEnabled) AccentGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                                        Text(text = if (config.isLocationSharingEnabled) "SIMULTAN" else "NONAKTIF", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (config.isLocationSharingEnabled) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("Menyinkronkan lokasi virtual agar selalu terbaca di Firebase (Realtime Database, Firestore, GeoFire) dan aplikasi target secara simultan tanpa jeda.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.isLocationSharingEnabled, onCheckedChange = { viewModel.setLocationSharing(it) }, modifier = Modifier.testTag("toggle_location_sharing"))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ================= KATEGORI: TARGET INJEKSI LOKASI =================
            Text("TARGET INJEKSI LOKASI", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                        Icon(Icons.Default.Security, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Anti-deteksi tetap dari LocationHook (otomatis). Pilihan ini hanya menentukan FAB target yang tampil.", style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val scopeOptions = listOf(
                        Triple("GOJEK", "Gojek Only", "Kontrol penuh ke Gojek. Grab pakai GPS real / state terpisah."),
                        Triple("GRAB", "Grab Only", "Kontrol penuh ke Grab. Gojek pakai GPS real / state terpisah."),
                        Triple("ALL", "All in One", "Kontrol Gojek + Grab dengan lokasi masing-masing.")
                    )
                    scopeOptions.forEach { (key, title, desc) ->
                        val isSelected = injectionScope == key
                        androidx.compose.material3.Surface(shape = RoundedCornerShape(12.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.setInjectionScope(key) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                RadioButton(selected = isSelected, onClick = { viewModel.setInjectionScope(key) })
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = when (key) { "GOJEK" -> Icons.Default.LocalShipping; "GRAB" -> Icons.Default.DirectionsCar; else -> Icons.Default.Public },
                                    contentDescription = null, tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                                    Text(text = desc, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ================= KATEGORI: STABILITAS KONEKSI =================
            Text("STABILITAS KONEKSI & RESPON KILAT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp).clip(CircleShape).background(if (config.isConnectionStabilityEnabled) AccentGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))) {
                                Icon(Icons.Default.Bolt, null, tint = if (config.isConnectionStabilityEnabled) AccentGreen else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Stabilitas Koneksi & Respon Kilat", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (config.isConnectionStabilityEnabled) AccentGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                                        Text(text = if (config.isConnectionStabilityEnabled) "AKTIF" else "NONAKTIF", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (config.isConnectionStabilityEnabled) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("Aktifkan optimasi jaringan socket, pooling OkHttp, dan respon kilat ke aplikasi target", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.isConnectionStabilityEnabled, onCheckedChange = { viewModel.setConnectionStability(it) }, modifier = Modifier.testTag("toggle_master_connection_stability"))
                    }
                    AnimatedVisibility(visible = config.isConnectionStabilityEnabled) {
                        Column {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.NetworkCheck, null, tint = if (config.isOkHttpStabilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text("Stabilitas Request OkHttp", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Pooling koneksi (keep-alive 5m), auto-retry otomatis, cegah request gantung, cepat tangkap order & ping", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                }
                                Switch(checked = config.isOkHttpStabilityEnabled, onCheckedChange = { viewModel.setOkHttpStability(it) }, modifier = Modifier.testTag("toggle_okhttp_stability"))
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Speed, null, tint = if (config.isFastTcpEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text("Fast TCP (TCP_NODELAY)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Bypass algoritma Nagle & buffer delay socket untuk pengiriman paket instan dengan latency terendah (IPTOS_LOWDELAY)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                }
                                Switch(checked = config.isFastTcpEnabled, onCheckedChange = { viewModel.setFastTcp(it) }, modifier = Modifier.testTag("toggle_fast_tcp"))
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.ElectricBolt, null, tint = if (config.isFastestCaptureModeEnabled) AccentOrange else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text("Fastest Capture Mode Module", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Respon kilat 50ms (interval 25-50ms) antar modul & aplikasi target (Maps, Gojek) dengan prioritas foreground", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                }
                                Switch(checked = config.isFastestCaptureModeEnabled, onCheckedChange = { viewModel.setFastestCaptureMode(it) }, modifier = Modifier.testTag("toggle_fastest_capture_mode"))
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.CloudSync, null, tint = if (config.isGcmSupportEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text("Dukungan Koneksi GCM & Push Ping", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text("Optimasi koneksi Google Cloud Messaging (GCM/FCM), port push 5228-5230, anti delay orderan masuk, dan GCM Heartbeat keep-alive", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                }
                                Switch(checked = config.isGcmSupportEnabled, onCheckedChange = { viewModel.setGcmSupport(it) }, modifier = Modifier.testTag("toggle_gcm_support"))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ================= KATEGORI: SIMULASI LOKASI & VT RACING =================
            Text("SIMULASI LOKASI (VT RACING)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Navigation, null, tint = if (config.useVirtual) AccentGreen else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Virtual Location (use_virtual)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Master switch untuk simulasi & injeksi lokasi uji QA", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.useVirtual, onCheckedChange = { viewModel.setUseVirtual(it) }, modifier = Modifier.testTag("toggle_use_virtual"))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Speed, null, tint = if (config.useVirtualRacing) AccentOrange else MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("VT Racing Engine (use_virtual_racing)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Simulasi gerak dinamis berkecepatan 0-5 m/s, bearing 0-360°, dan throttle 2000ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.useVirtualRacing, onCheckedChange = { viewModel.setUseVirtualRacing(it) }, modifier = Modifier.testTag("toggle_use_virtual_racing"))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Vibration, null, tint = if (config.useRandom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Random Movement (use_random)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Offset acak pergerakan mikro (skala 1.0E7) di sekitar titik pusat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.useRandom, onCheckedChange = { viewModel.setUseRandom(it) }, modifier = Modifier.testTag("toggle_use_random"))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
                            Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Titik Pusat Terkoneksi Otomatis", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Koordinat pusat (${String.format(Locale.US, "%.5f, %.5f", config.latitude, config.longitude)}) terhubung langsung dengan pin lokasi pada peta layar utama.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ================= KATEGORI: CANCEL BY DISTANCE =================
            Text("CANCEL BY DISTANCE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            CancelCantikCard()

            Spacer(modifier = Modifier.height(24.dp))

            // ================= KATEGORI: SORTIR ORDER (GRAB) =================
            Text("SORTIR ORDER (SHADOW MODE)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SortirSettingsCard()

            Spacer(modifier = Modifier.height(24.dp))

            // ================= 4. KATEGORI: XPOSED =================
            Text("XPOSED", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Hook system location", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Kontrol utama sinkronisasi spoofing & hook lokasi sistem", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.isHookEnabled, onCheckedChange = { viewModel.toggleHook(it) }, modifier = Modifier.testTag("toggle_hook_system_location"))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Accuracy", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Akurasi GPS palsu (default 10 m)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedTextField(
                            value = accuracyInput,
                            onValueChange = { newValue ->
                                accuracyInput = newValue
                                val parsed = newValue.toFloatOrNull()
                                if (parsed != null && parsed >= 1.0f) viewModel.setAccuracy(parsed)
                            },
                            suffix = { Text("m", fontSize = 12.sp) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                            modifier = Modifier.width(90.dp).testTag("accuracy_input_field")
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Vibration, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Random location", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        }
                        Switch(checked = config.isRandomLocationEnabled, onCheckedChange = { viewModel.setRandomLocation(it) }, modifier = Modifier.testTag("toggle_random_location"))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Vibration, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Random accuracy", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Fluktuasi akurasi alami (4 - 15m) sesuai standar sinyal Google Maps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.isRandomAccuracyEnabled, onCheckedChange = { viewModel.setRandomAccuracy(it) })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Anti-Mock Detection", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Sembunyikan isFromMockProvider, isMock, Settings.Secure & AppOps mock flag", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = config.isAntiMockEnabled, onCheckedChange = { viewModel.setAntiMock(it) })
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Widgets, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Floating Overlay (Widget Melayang)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("Tampilkan kontrol tombol melayang vertikal di atas aplikasi lain (tutup, mulai/refresh, pause spoof, mini-joystick)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(
                            checked = config.isFloatingOverlayEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                        overlayPermissionLauncher.launch(intent)
                                    } else {
                                        viewModel.setFloatingOverlay(true)
                                        com.gpm.virtual.service.FloatingOverlayService.start(context)
                                    }
                                } else {
                                    viewModel.setFloatingOverlay(false)
                                    com.gpm.virtual.service.FloatingOverlayService.stop(context)
                                }
                            },
                            modifier = Modifier.testTag("toggle_floating_overlay")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Tips LSPosed: Setelah mengubah scope atau mengaktifkan modul pertama kali, lakukan soft reboot atau restart aplikasi target.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

// ==================================================================
// Cancel Cantik control card
// ==================================================================
@Composable
private fun CancelCantikCard() {
    val context = LocalContext.current
    val state by CancelCantikController.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        CancelCantikController.register(context)
        onDispose { CancelCantikController.unregister(context) }
    }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(CircleShape).background(if (state.enabled) AccentGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))) {
                Icon(Icons.Default.AutoFixHigh, null, tint = if (state.enabled) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Cancel By Distance", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text("Tambahkan opsi alasan pada daftar aplikasi target", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.enabled, onCheckedChange = { CancelCantikController.setEnabled(context, it) }, modifier = Modifier.testTag("toggle_cancel_cantik"))
        }
    }
}

// ==================================================================
// Sortir Order control card
// ==================================================================
@Composable
private fun SortirSettingsCard() {
    val context = LocalContext.current
    val state by SortirSettingsController.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        SortirSettingsController.register(context)
        onDispose { SortirSettingsController.unregister(context) }
    }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clip(CircleShape).background(if (state.enabled) AccentGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))) {
                        Icon(Icons.Default.FilterList, null, tint = if (state.enabled) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sortir Order (Shadow Mode)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AccentOrange.copy(alpha = 0.2f)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                Text("GRAB ONLY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AccentOrange)
                            }
                        }
                        Text("Order tetap tampil di UI. Reject/timeout/ignore tidak dikirim ke server — dibiarkan sampai server reassign otomatis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = state.enabled, onCheckedChange = { SortirSettingsController.setEnabled(context, it) }, modifier = Modifier.testTag("toggle_sortir_master"))
            }

            AnimatedVisibility(visible = state.enabled) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Security, null, tint = if (state.filterEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("Filter Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Blok order sebelum tampil berdasarkan variant / harga", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                        Switch(checked = state.filterEnabled, onCheckedChange = { SortirSettingsController.setFilterEnabled(context, it) }, modifier = Modifier.testTag("toggle_sortir_filter"))
                    }

                    // ⭐⭐⭐ UI MIN/MAX HARGA DITAMBAHKAN DI SINI ⭐⭐⭐
                    if (state.filterEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("Min Harga (Rp)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            var minInput by remember(state.minPrice) {
                                mutableStateOf(if (state.minPrice > 0) state.minPrice.toInt().toString() else "")
                            }
                            OutlinedTextField(
                                value = minInput,
                                onValueChange = { v ->
                                    val clean = v.filter { it.isDigit() }
                                    minInput = clean
                                    clean.toFloatOrNull()?.let { SortirSettingsController.setMinPrice(context, it) }
                                },
                                placeholder = { Text("0 (Tanpa batas)") },
                                singleLine = true,
                                modifier = Modifier.width(140.dp)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("Max Harga (Rp)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            var maxInput by remember(state.maxPrice) {
                                mutableStateOf(if (state.maxPrice > 0) state.maxPrice.toInt().toString() else "")
                            }
                            OutlinedTextField(
                                value = maxInput,
                                onValueChange = { v ->
                                    val clean = v.filter { it.isDigit() }
                                    maxInput = clean
                                    clean.toFloatOrNull()?.let { SortirSettingsController.setMaxPrice(context, it) }
                                },
                                placeholder = { Text("0 (Tanpa batas)") },
                                singleLine = true,
                                modifier = Modifier.width(140.dp)
                            )
                        }
                    }
                    // ⭐⭐⭐ AKHIR UI MIN/MAX HARGA ⭐⭐⭐

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), thickness = 0.5.dp)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Info, null, tint = if (state.verbose) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text("Verbose Logging", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Log setiap event order (accept / reject / timeout / ignore) ke logcat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                        Switch(checked = state.verbose, onCheckedChange = { SortirSettingsController.setVerbose(context, it) }, modifier = Modifier.testTag("toggle_sortir_verbose"))
                    }

                    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Server akan reassign order otomatis setelah timeout (biasanya 20–30 detik). Cocok untuk skip order tanpa menurunkan accept-rate.", style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}