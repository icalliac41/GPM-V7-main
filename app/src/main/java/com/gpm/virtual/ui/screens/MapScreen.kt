package com.gpm.virtual.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpm.virtual.ui.components.FavoritesBottomSheet
import com.gpm.virtual.ui.components.JoystickOverlay
import com.gpm.virtual.ui.components.NearbyBottomSheet
import com.gpm.virtual.ui.theme.AccentGreen
import com.gpm.virtual.ui.theme.AccentOrange
import com.gpm.virtual.ui.theme.AccentRed
import com.gpm.virtual.viewmodel.MainViewModel
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

private fun getGoogleMapsBlueDotDrawable(context: android.content.Context): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (44 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer soft pulse / accuracy aura (translucent Google blue)
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.parseColor("#334285F4")
    canvas.drawCircle(center, center, 20 * density, paint)

    // Subtle aura border line
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.2f * density
    paint.color = android.graphics.Color.parseColor("#664285F4")
    canvas.drawCircle(center, center, 20 * density, paint)

    // Crisp white border
    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, 9.5f * density, paint)

    // Vibrant Google blue center dot
    paint.color = android.graphics.Color.parseColor("#1A73E8")
    canvas.drawCircle(center, center, 7f * density, paint)

    return BitmapDrawable(context.resources, bitmap)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MainViewModel,
    onOpenDrawer: () -> Unit,
    onNavigateFavorites: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsStateWithLifecycle()
    val activeCoordinates by viewModel.activeCoordinates.collectAsStateWithLifecycle()
    val realDeviceLocation by viewModel.realDeviceLocation.collectAsStateWithLifecycle()
    var blueDotMarkerRef by remember { mutableStateOf<Marker?>(null) }
    var hasAutoCenteredOnBlueDot by remember { mutableStateOf(false) }
    val isLsposedActive by viewModel.isLsposedActive.collectAsStateWithLifecycle()
    val lsposedStatusDetail by viewModel.lsposedStatusDetail.collectAsStateWithLifecycle()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsStateWithLifecycle()
    val hasBackgroundPermission by viewModel.hasBackgroundPermission.collectAsStateWithLifecycle()

    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()

    val nearbyPlaces by viewModel.nearbyPlaces.collectAsStateWithLifecycle()
    val isNearbyLoading by viewModel.isNearbyLoading.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedNearbyCategory.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var favoriteNameInput by remember { mutableStateOf("") }
    var showFavoritesSheet by remember { mutableStateOf(false) }
    val favoritesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showManualCoordinateDialog by remember { mutableStateOf(false) }
    var manualCoordLatInput by remember { mutableStateOf("") }
    var manualCoordLngInput by remember { mutableStateOf("") }
    var manualCoordNameInput by remember { mutableStateOf("") }
    var manualCoordError by remember { mutableStateOf<String?>(null) }
    var manualCoordSaveToFav by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    var isJoystickVisible by remember { mutableStateOf(false) }
    var showLsposedHelpDialog by remember { mutableStateOf(false) }
    var showBackgroundPermissionDialog by remember { mutableStateOf(false) }
    var showNearbySheet by remember { mutableStateOf(false) }
    var showMapLayerDialog by remember { mutableStateOf(false) }
    var isToolsMenuOpen by remember { mutableStateOf(false) }
    val nearbySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ============ State Injeksi & Spoof ============
    var showGojekReHookDialog by remember { mutableStateOf(false) }
    var showGrabReHookDialog by remember { mutableStateOf(false) }
    var showDualTargetDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val statusSnackbar by viewModel.statusSnackbar.collectAsStateWithLifecycle()
    val injectionScope by viewModel.injectionScope.collectAsStateWithLifecycle()
    val gojekSpoofActive by viewModel.gojekSpoofActive.collectAsStateWithLifecycle()
    val grabSpoofActive by viewModel.grabSpoofActive.collectAsStateWithLifecycle()
    val gojekTarget by viewModel.gojekTarget.collectAsStateWithLifecycle()
    val grabTarget by viewModel.grabTarget.collectAsStateWithLifecycle()
    val gojekDraftTarget by viewModel.gojekDraftTarget.collectAsStateWithLifecycle()
val grabDraftTarget by viewModel.grabDraftTarget.collectAsStateWithLifecycle()
val hasPendingDraft by viewModel.hasPendingDraft.collectAsStateWithLifecycle()
val activeEditTarget by viewModel.activeEditTarget.collectAsStateWithLifecycle()

    LaunchedEffect(statusSnackbar) {
        statusSnackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        viewModel.onPermissionsResult(fineGranted, coarseGranted)
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { viewModel.checkPermissions() }

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

    LaunchedEffect(Unit) {
        viewModel.checkPermissions()
        viewModel.checkLsposedStatus()
        if (!hasLocationPermission) {
            val permsToRequest = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            locationPermissionLauncher.launch(permsToRequest.toTypedArray())
        } else {
            // Langsung sinkronkan GPS perangkat asli saat buka aplikasi
            viewModel.syncWithDeviceGps()
        }
    }

    DisposableEffect(Unit) {
        viewModel.checkLsposedStatus()
        viewModel.checkPermissions()
        onDispose {}
    }

    // ============================================================
    // [MARKER UPDATE] Tugas marker HANYA menandai lokasi yang diinginkan.
    // Tidak merebut kamera peta secara terus-menerus agar user tetap
    // terfokus pada titik biru saat aplikasi dibuka.
    // ============================================================
    LaunchedEffect(
        activeCoordinates,
        config.latitude, config.longitude,
        gojekTarget, grabTarget,
        gojekDraftTarget, grabDraftTarget,
        injectionScope, activeEditTarget,
        hasPendingDraft
    ) {
        val targetPair = if (hasPendingDraft) {
            activeCoordinates
        } else {
            val displayTarget = when (injectionScope) {
                "GOJEK" -> "gojek"
                "GRAB"  -> "grab"
                else    -> activeEditTarget
            }
            when (displayTarget) {
                "gojek" -> gojekDraftTarget ?: gojekTarget ?: activeCoordinates
                else    -> grabDraftTarget ?: grabTarget ?: activeCoordinates
            }
        }
        val targetGeo = GeoPoint(targetPair.first, targetPair.second)
        val label = when (injectionScope) {
            "GOJEK" -> "Gojek: ${config.locationName.ifBlank { "Titik Gojek" }}"
            "GRAB"  -> "Grab: ${config.locationName.ifBlank { "Titik Grab" }}"
            else    -> config.locationName.ifBlank { "Titik Lokasi" }
        }
        markerRef?.position = targetGeo
        markerRef?.title = label
        mapViewRef?.invalidate()
    }

    LaunchedEffect(realDeviceLocation, mapViewRef) {
        val loc = realDeviceLocation ?: return@LaunchedEffect
        val map = mapViewRef ?: return@LaunchedEffect
        val geo = GeoPoint(loc.first, loc.second)
        if (blueDotMarkerRef == null) {
            val dot = Marker(map).apply {
                position = geo
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = getGoogleMapsBlueDotDrawable(context)
                title = "Lokasi Asli Perangkat"
                snippet = "GPS Perangkat Nyata (Google Maps Blue Dot)"
                isDraggable = false
            }
            map.overlays.add(dot)
            blueDotMarkerRef = dot
        } else {
            blueDotMarkerRef?.position = geo
            if (!map.overlays.contains(blueDotMarkerRef)) {
                map.overlays.add(blueDotMarkerRef)
            }
        }

        // OTOMATIS TERPUSATKAN DARI TITIK BIRU TERLEBIH DAHULU:
        // Saat aplikasi dibuka / lokasi asli GPS didapatkan pertama kali,
        // pusatkan tampilan peta langsung ke titik biru (GPS perangkat asli).
        if (!hasAutoCenteredOnBlueDot) {
            map.controller.setCenter(geo)
            map.controller.setZoom(17.0)
            hasAutoCenteredOnBlueDot = true
        }

        map.invalidate()
    }

    LaunchedEffect(mapViewRef) {
        val map = mapViewRef ?: return@LaunchedEffect
        map.overlays.removeAll { it is Marker && it != markerRef && it != blueDotMarkerRef }
        map.invalidate()
    }

    LaunchedEffect(config.mapType) {
        mapViewRef?.let { map ->
            com.gpm.virtual.ui.map.MapThemeHelper.applyMapStyle(map, config.mapType)
        }
    }

    // [DRAFT PATTERN] Marker SELALU draggable.
    // Menggeser marker hanya mengubah DRAFT — posisi spoof aktif
    // Gojek/Grab tidak berubah sampai user tekan "Terapkan".
    LaunchedEffect(markerRef) {
        markerRef?.isDraggable = true
        mapViewRef?.invalidate()
    }

    LaunchedEffect(config.isJoystickEnabled) {
        isJoystickVisible = config.isJoystickEnabled
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. MapView
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    com.gpm.virtual.ui.map.MapThemeHelper.applyMapStyle(this, config.mapType)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    minZoomLevel = 3.0
                    maxZoomLevel = 20.0
                    setDestroyMode(false)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(17.0)
                    val initialCenter = realDeviceLocation?.let { 
                        hasAutoCenteredOnBlueDot = true
                        GeoPoint(it.first, it.second) 
                    } ?: GeoPoint(config.latitude, config.longitude)
                    controller.setCenter(initialCenter)
                    val initialPoint = GeoPoint(config.latitude, config.longitude)
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    val mapEventsReceiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (p != null) {
                                viewModel.updateTargetLocation(p.latitude, p.longitude)
                                return true
                            }
                            return false
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            if (p != null) {
                                viewModel.updateTargetLocation(p.latitude, p.longitude)
                                return true
                            }
                            return false
                        }
                    }
                    overlays.add(MapEventsOverlay(mapEventsReceiver))
                    val marker = Marker(this).apply {
                        position = initialPoint
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = config.locationName
                        isDraggable = true
                        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                            override fun onMarkerDrag(m: Marker?) {}
                            override fun onMarkerDragStart(m: Marker?) {}
                            override fun onMarkerDragEnd(m: Marker?) {
                                m?.position?.let { pt ->
                                    viewModel.updateTargetLocation(pt.latitude, pt.longitude)
                                }
                            }
                        })
                    }
                    overlays.add(marker)
                    markerRef = marker
                    mapViewRef = this
                }
            },
            update = { map ->
                mapViewRef = map
                val targetPair = if (hasPendingDraft) {
                    activeCoordinates
                } else {
                    val displayTarget = when (injectionScope) {
                        "GOJEK" -> "gojek"
                        "GRAB"  -> "grab"
                        else    -> activeEditTarget
                    }
                    when (displayTarget) {
                        "gojek" -> gojekDraftTarget ?: gojekTarget ?: activeCoordinates
                        else    -> grabDraftTarget ?: grabTarget ?: activeCoordinates
                    }
                }
                val pt = GeoPoint(targetPair.first, targetPair.second)
                markerRef?.position = pt
                map.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 110.dp)
        )

        // Search Bar + Alerts
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 44.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("nav_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Buka Menu",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { query ->
                            searchQuery = query
                            isSearchFocused = query.isNotBlank()
                            viewModel.searchLocations(query)
                        },
                        placeholder = {
                            Text(
                                text = if (config.locationName.isNotBlank() && config.locationName != "Unknown Location")
                                    config.locationName
                                else "Cari di sini (alamat, gedung, kota)...",
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_location_input")
                    )

                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = {
                                searchQuery = ""
                                viewModel.clearSearch()
                                isSearchFocused = false
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Hapus",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isLsposedActive) AccentGreen.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                )
                                .clickable { showLsposedHelpDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isLsposedActive) AccentGreen
                                            else MaterialTheme.colorScheme.primary
                                        )
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isLsposedActive) "LSPosed" else "Scope",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLsposedActive) AccentGreen else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Location permission alert - compact minimalist chip
            if (!hasLocationPermission) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    onClick = {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Aktifkan izin lokasi untuk sambungkan GPS perangkat",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Izinkan",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Search results
            if (isSearchFocused && (searchResults.isNotEmpty() || isSearching)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    if (isSearching) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.height(minOf(220.dp, (searchResults.size * 56).dp))) {
                            items(searchResults) { item ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            // [FIX Bug #2] Jangan set marker manual.
                                            // ViewModel update → recomposition →
                                            // LaunchedEffect di atas akan set marker.
                                            viewModel.updateTargetLocation(
                                                item.latitude, item.longitude, item.title
                                            )
                                            mapViewRef?.controller?.animateTo(
                                                GeoPoint(item.latitude, item.longitude)
                                            )
                                            searchQuery = item.title
                                            isSearchFocused = false
                                            viewModel.clearSearch()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationSearching,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = item.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


        // Layer & Floating overlay (Top Right)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 4.dp,
            tonalElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 104.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(4.dp)
            ) {
                IconButton(
                    onClick = { showMapLayerDialog = true },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("map_layers_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Lapisan Peta",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
                IconButton(
                    onClick = {
                        val willEnable = !config.isFloatingOverlayEnabled
                        if (willEnable) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
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
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("map_floating_overlay_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = "Floating Overlay",
                        tint = if (config.isFloatingOverlayEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Zoom controls (Left)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            shadowElevation = 3.dp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        ) {
            Column {
                IconButton(
                    onClick = { mapViewRef?.controller?.zoomIn() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Zoom In",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
                IconButton(
                    onClick = { mapViewRef?.controller?.zoomOut() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Zoom Out",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Speed Dial / Tools FAB (Bottom Right)
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 155.dp)
        ) {
            AnimatedVisibility(visible = isToolsMenuOpen) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    // Quick Action: Injeksi Shortcuts
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InjectPill(
                            icon = Icons.Default.Sync,
                            label = "Dual",
                            tint = Color(0xFF1A73E8),
                            onClick = { showDualTargetDialog = true; isToolsMenuOpen = false },
                            tag = "fab_inject_dual"
                        )
                        if (injectionScope == "GOJEK" || injectionScope == "ALL") {
                            InjectPill(
                                icon = Icons.Default.LocalShipping,
                                label = "Gojek",
                                tint = AccentGreen,
                                onClick = { showGojekReHookDialog = true; isToolsMenuOpen = false },
                                tag = "fab_inject_gojek"
                            )
                        }
                        if (injectionScope == "GRAB" || injectionScope == "ALL") {
                            InjectPill(
                                icon = Icons.Default.DirectionsCar,
                                label = "Grab",
                                tint = AccentOrange,
                                onClick = { showGrabReHookDialog = true; isToolsMenuOpen = false },
                                tag = "fab_inject_grab"
                            )
                        }
                        InjectPill(
                            icon = Icons.Default.Share,
                            label = if (config.isLocationSharingEnabled) "Share ON" else "Share OFF",
                            tint = if (config.isLocationSharingEnabled) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                viewModel.setLocationSharing(!config.isLocationSharingEnabled)
                            },
                            tag = "fab_inject_sharing"
                        )
                    }

                    // Action Item: Manual Coord
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                manualCoordLatInput = com.gpm.virtual.xposed.RealisticGpsEngine.formatGoogleMapsCoord(activeCoordinates.first)
                                manualCoordLngInput = com.gpm.virtual.xposed.RealisticGpsEngine.formatGoogleMapsCoord(activeCoordinates.second)
                                manualCoordNameInput = config.locationName
                                manualCoordError = null
                                showManualCoordinateDialog = true
                                isToolsMenuOpen = false
                            }
                            .testTag("fab_manual_coordinate")
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                "Input Koordinat",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Action Item: Favorit
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                showFavoritesSheet = true
                                isToolsMenuOpen = false
                            }
                            .testTag("fab_favorite")
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                "Lokasi Favorit",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Star, null, tint = AccentRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Action Item: Sekitar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                viewModel.fetchNearbyPlaces()
                                showNearbySheet = true
                                isToolsMenuOpen = false
                            }
                            .testTag("fab_nearby_places")
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                "Tempat Sekitar",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.NearMe, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Action Item: Joystick Toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                val nextState = !isJoystickVisible
                                isJoystickVisible = nextState
                                viewModel.setJoystick(nextState)
                                isToolsMenuOpen = false
                            }
                            .testTag("fab_joystick")
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                if (isJoystickVisible) "Tutup Joystick" else "Buka Joystick",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = if (isJoystickVisible) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Gamepad, null,
                                    tint = if (isJoystickVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Action Item: Refresh Overlay
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                when (injectionScope) {
                                    "GOJEK" -> viewModel.refreshOverlayForTarget("gojek")
                                    "GRAB"  -> viewModel.refreshOverlayForTarget("grab")
                                    else    -> {
                                        viewModel.refreshOverlayForTarget("gojek")
                                        viewModel.refreshOverlayForTarget("grab")
                                    }
                                }
                                isToolsMenuOpen = false
                            }
                            .testTag("btn_refresh_overlay")
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shadowElevation = 2.dp
                        ) {
                            Text(
                                "Sinkron Overlay",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 3.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Widgets, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // My Location FAB
            FloatingActionButton(
                onClick = {
                    if (!hasLocationPermission) {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else {
                        viewModel.syncWithDeviceGps { lat, lng ->
                            mapViewRef?.controller?.animateTo(GeoPoint(lat, lng))
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                elevation = FloatingActionButtonDefaults.elevation(3.dp),
                shape = CircleShape,
                modifier = Modifier
                    .size(42.dp)
                    .testTag("fab_my_location")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Lokasi Saya",
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Expandable Tools Toggle FAB
            FloatingActionButton(
                onClick = { isToolsMenuOpen = !isToolsMenuOpen },
                containerColor = if (isToolsMenuOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (isToolsMenuOpen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                elevation = FloatingActionButtonDefaults.elevation(4.dp),
                shape = CircleShape,
                modifier = Modifier
                    .size(46.dp)
                    .testTag("fab_tools_menu")
            ) {
                Icon(
                    imageVector = if (isToolsMenuOpen) Icons.Default.Close else Icons.Default.Widgets,
                    contentDescription = "Menu Alat",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Joystick overlay (hovering safely above bottom card)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 170.dp, start = 16.dp)
        ) {
            JoystickOverlay(
                isVisible = isJoystickVisible,
                onMove = { deltaLat, deltaLng ->
                    viewModel.moveLocation(deltaLat, deltaLng)
                },
                onClose = {
                    isJoystickVisible = false
                    viewModel.setJoystick(false)
                }
            )
        }

        // Minimalist Bottom Control Card
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Header row: Status Dot + Coordinate/Location + Edit chip (if ALL)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    hasPendingDraft -> AccentOrange
                                    gojekSpoofActive || grabSpoofActive -> AccentGreen
                                    config.isHookEnabled -> Color(0xFF1A73E8)
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                hasPendingDraft -> "Draft Posisi Baru"
                                gojekSpoofActive && grabSpoofActive -> "Gojek + Grab Aktif"
                                gojekSpoofActive -> "Gojek Aktif"
                                grabSpoofActive -> "Grab Aktif"
                                config.isHookEnabled -> "Hook Siap"
                                else -> "Standby"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                hasPendingDraft -> AccentOrange
                                gojekSpoofActive || grabSpoofActive -> AccentGreen
                                config.isHookEnabled -> Color(0xFF1A73E8)
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.5f", activeCoordinates.first)}, ${String.format(Locale.US, "%.5f", activeCoordinates.second)} (±${config.accuracy.toInt()}m)",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Target Edit Chips (if injectionScope == "ALL")
                    if (injectionScope == "ALL") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { viewModel.setActiveEditTarget("gojek") },
                                shape = RoundedCornerShape(12.dp),
                                color = if (activeEditTarget == "gojek") AccentGreen else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.testTag("chip_edit_gojek")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.LocalShipping, null,
                                        tint = if (activeEditTarget == "gojek") Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        "Gojek", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        color = if (activeEditTarget == "gojek") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Surface(
                                onClick = { viewModel.setActiveEditTarget("grab") },
                                shape = RoundedCornerShape(12.dp),
                                color = if (activeEditTarget == "grab") AccentOrange else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.testTag("chip_edit_grab")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsCar, null,
                                        tint = if (activeEditTarget == "grab") Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        "Grab", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                        color = if (activeEditTarget == "grab") Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Middle: Draft Bar (if marker dragged/changed)
                AnimatedVisibility(visible = hasPendingDraft) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.discardDraftForCurrentScope() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("btn_draft_cancel")
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Batal", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { viewModel.applyDraftForCurrentScope() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("btn_draft_apply")
                        ) {
                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Terapkan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom row: 3 Toggle Buttons
                val playActive = when (injectionScope) {
                    "GOJEK" -> gojekSpoofActive
                    "GRAB" -> grabSpoofActive
                    else -> gojekSpoofActive || grabSpoofActive
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SpoofToggleButton(
                        label = "Gojek",
                        icon = Icons.Default.LocalShipping,
                        active = gojekSpoofActive,
                        onClick = { viewModel.toggleGojekSpoof() },
                        modifier = Modifier.weight(1f),
                        tag = "spoof_toggle_gojek"
                    )
                    SpoofToggleButton(
                        label = "Grab",
                        icon = Icons.Default.DirectionsCar,
                        active = grabSpoofActive,
                        onClick = { viewModel.toggleGrabSpoof() },
                        modifier = Modifier.weight(1f),
                        tag = "spoof_toggle_grab"
                    )
                    SpoofToggleButton(
                        label = if (playActive) "Stop" else "Play",
                        icon = if (playActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        active = playActive,
                        onClick = { viewModel.toggleAllSpoof() },
                        isLarge = true,
                        modifier = Modifier.weight(1.2f),
                        tag = "spoof_toggle_all"
                    )
                }
            }
        }

        // Favorites Bottom Sheet
        if (showFavoritesSheet) {
            FavoritesBottomSheet(
                sheetState = favoritesSheetState,
                favorites = favorites,
                onSelectLocation = { fav ->
                    // [FIX Bug #2] Jangan set marker manual — biar
                    // LaunchedEffect yang urus, agar konsisten dengan draft.
                    viewModel.updateTargetLocation(fav.latitude, fav.longitude, fav.name)
                    mapViewRef?.controller?.animateTo(
                        GeoPoint(fav.latitude, fav.longitude)
                    )
                    mapViewRef?.controller?.setZoom(17.5)
                    showFavoritesSheet = false
                },
                onDeleteFavorite = { fav -> viewModel.deleteFavorite(fav) },
                onAddNewFavorite = {
                    favoriteNameInput = config.locationName
                    showFavoriteDialog = true
                },
                onOpenFullScreen = onNavigateFavorites?.let { nav ->
                    {
                        showFavoritesSheet = false
                        nav()
                    }
                },
                onDismiss = { showFavoritesSheet = false }
            )
        }

        // Nearby Bottom Sheet
        if (showNearbySheet) {
            NearbyBottomSheet(
                sheetState = nearbySheetState,
                places = nearbyPlaces,
                isLoading = isNearbyLoading,
                selectedCategory = selectedCategory,
                onCategorySelected = { cat -> viewModel.fetchNearbyPlaces(cat) },
                onPlaceSelected = { place ->
                    // [FIX Bug #2] Jangan set marker manual.
                    viewModel.updateTargetLocation(place.latitude, place.longitude, place.name)
                    mapViewRef?.controller?.animateTo(
                        GeoPoint(place.latitude, place.longitude)
                    )
                    mapViewRef?.controller?.setZoom(17.5)
                    showNearbySheet = false
                },
                onDismiss = { showNearbySheet = false }
            )
        }

        // Manual Coordinate Dialog
        if (showManualCoordinateDialog) {
            AlertDialog(
                onDismissRequest = { showManualCoordinateDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        "Input & Simpan Koordinat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Masukkan atau tempel koordinat langsung.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                val clipText = clipboardManager.getText()?.text?.trim() ?: ""
                                if (clipText.isNotBlank()) {
                                    val parsed = parseCoordinates(clipText)
                                    if (parsed != null) {
                                        manualCoordLatInput = com.gpm.virtual.xposed.RealisticGpsEngine.formatGoogleMapsCoord(parsed.first)
                                        manualCoordLngInput = com.gpm.virtual.xposed.RealisticGpsEngine.formatGoogleMapsCoord(parsed.second)
                                        manualCoordError = null
                                    } else {
                                        manualCoordError = "Format clipboard tidak valid: $clipText"
                                    }
                                } else {
                                    manualCoordError = "Clipboard kosong."
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_paste_from_clipboard")
                        ) {
                            Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Tempel dari Clipboard", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = manualCoordLatInput,
                            onValueChange = { input ->
                                val parsed = parseCoordinates(input)
                                if (parsed != null) {
                                    manualCoordLatInput = com.gpm.virtual.xposed.RealisticGpsEngine.formatGoogleMapsCoord(parsed.first)
                                    manualCoordLngInput = com.gpm.virtual.xposed.RealisticGpsEngine.formatGoogleMapsCoord(parsed.second)
                                    manualCoordError = null
                                } else {
                                    manualCoordLatInput = input
                                }
                            },
                            label = { Text("Latitude") },
                            placeholder = { Text("-6.2087634") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_manual_latitude")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualCoordLngInput,
                            onValueChange = { manualCoordLngInput = it; manualCoordError = null },
                            label = { Text("Longitude") },
                            placeholder = { Text("106.8455990") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_manual_longitude")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualCoordNameInput,
                            onValueChange = { manualCoordNameInput = it },
                            label = { Text("Nama Lokasi") },
                            placeholder = { Text("Contoh: Basecamp") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_manual_name")
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { manualCoordSaveToFav = !manualCoordSaveToFav }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = manualCoordSaveToFav,
                                onCheckedChange = { manualCoordSaveToFav = it }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Terkoneksi ke Favorit", style = MaterialTheme.typography.bodySmall)
                        }
                        if (manualCoordError != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                manualCoordError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val lat = manualCoordLatInput.trim().toDoubleOrNull()
                                val lng = manualCoordLngInput.trim().toDoubleOrNull()
                                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                                    manualCoordError = "Koordinat tidak valid!"
                                    return@Button
                                }
                                viewModel.saveFavoriteLocation(manualCoordNameInput, lat, lng)
                                showManualCoordinateDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.testTag("btn_save_coordinate_favorite")
                        ) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SAVE")
                        }
                        Button(
                            onClick = {
                                val lat = manualCoordLatInput.trim().toDoubleOrNull()
                                val lng = manualCoordLngInput.trim().toDoubleOrNull()
                                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                                    manualCoordError = "Koordinat tidak valid!"
                                    return@Button
                                }
                                if (manualCoordSaveToFav) {
                                    viewModel.saveFavoriteLocation(manualCoordNameInput, lat, lng)
                                }
                                viewModel.updateTargetLocation(
                                    lat, lng, manualCoordNameInput.ifBlank { null }
                                )
                                mapViewRef?.controller?.animateTo(GeoPoint(lat, lng))
                                showManualCoordinateDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("btn_go_to_manual_location")
                        ) {
                            Icon(Icons.Default.Navigation, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("MENUJU")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showManualCoordinateDialog = false },
                        modifier = Modifier.testTag("btn_cancel_manual_coordinate")
                    ) {
                        Text("Batal")
                    }
                }
            )
        }

        // Favorite Dialog
        if (showFavoriteDialog) {
            AlertDialog(
                onDismissRequest = { showFavoriteDialog = false },
                title = { Text("Simpan ke Favorit", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column {
                        Text(
                            "Beri nama untuk (${String.format(Locale.US, "%.7f, %.7f", activeCoordinates.first, activeCoordinates.second)}):",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = favoriteNameInput,
                            onValueChange = { favoriteNameInput = it },
                            label = { Text("Nama Lokasi") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("favorite_name_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.saveCurrentFavorite(favoriteNameInput)
                            showFavoriteDialog = false
                        },
                        modifier = Modifier.testTag("confirm_save_favorite_button")
                    ) { Text("Simpan") }
                },
                dismissButton = {
                    TextButton(onClick = { showFavoriteDialog = false }) { Text("Batal") }
                }
            )
        }

        // Background permission dialog
        if (showBackgroundPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showBackgroundPermissionDialog = false },
                icon = {
                    Icon(
                        Icons.Default.MyLocation, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        "Izin Lokasi Sepanjang Waktu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "1. Pilih 'Izinkan Sepanjang Waktu'.\n" +
                        "2. Di Android 11+, pilih 'Buka Pengaturan' lalu ubah ke 'Izinkan terus-menerus'.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBackgroundPermissionDialog = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                    ) { Text("Buka Pengaturan") }
                },
                dismissButton = {
                    TextButton(onClick = { showBackgroundPermissionDialog = false }) { Text("Nanti") }
                }
            )
        }

        // LSPosed Help Dialog
        if (showLsposedHelpDialog) {
            AlertDialog(
                onDismissRequest = { showLsposedHelpDialog = false },
                icon = {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        tint = AccentGreen,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = if (isLsposedActive) "Modul LSPosed Terhubung" else "Petunjuk Scope LSPosed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        if (lsposedStatusDetail.isNotEmpty()) {
                            Text("Status: $lsposedStatusDetail", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
    "Scope aktif. Centang aplikasi target di Scope agar modul " +
    "berfungsi pada aplikasi tersebut.\n\n" +
    "• Google Maps\n• Gojek Partner\n• Grab Driver",
    style = MaterialTheme.typography.bodySmall,
    fontSize = 12.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val pm = context.packageManager
                            val lsposedIntent = pm.getLaunchIntentForPackage("org.lsposed.manager")
                            if (lsposedIntent != null) context.startActivity(lsposedIntent)
                            showLsposedHelpDialog = false
                        }
                    ) { Text(if (isLsposedActive) "Tutup" else "Buka LSPosed") }
                },
                dismissButton = {
                    TextButton(onClick = { showLsposedHelpDialog = false }) { Text("Tutup") }
                }
            )
        }

        // Dialog: Injeksi Gojek
        if (showGojekReHookDialog) {
            InjectConfirmDialog(
                title = "Injeksi Lokasi Gojek",
                body = "Sinkronisasi ulang koordinat ke aplikasi target.",
warning = "Pastikan aplikasi target sedang berjalan.",
                accentColor = AccentGreen,
                onConfirm = { viewModel.injectGojekHooks(); showGojekReHookDialog = false },
                onDismiss = { showGojekReHookDialog = false },
                tag = "dialog_inject_gojek"
            )
        }

        // Dialog: Injeksi Grab
        if (showGrabReHookDialog) {
            InjectConfirmDialog(
                title = "Injeksi Lokasi Grab",
                body = "Sinkronisasi ulang koordinat ke aplikasi target.",
warning = "Pastikan aplikasi target sedang berjalan.",
                accentColor = AccentOrange,
                onConfirm = { viewModel.injectGrabHooks(); showGrabReHookDialog = false },
                onDismiss = { showGrabReHookDialog = false },
                tag = "dialog_inject_grab"
            )
        }

        // Dialog: Dual Target
        if (showDualTargetDialog) {
            DualTargetDialog(
                defaultLat = config.latitude,
                defaultLng = config.longitude,
                gojekTarget = gojekTarget,
                grabTarget = grabTarget,
                onSaveAndInject = { gLat, gLng, rLat, rLng, saveGojek, saveGrab ->
                    if (saveGojek && gLat != null && gLng != null) {
                        viewModel.setGojekTarget(gLat, gLng)
                    }
                    if (saveGrab && rLat != null && rLng != null) {
                        viewModel.setGrabTarget(rLat, rLng)
                    }
                    viewModel.injectDualTargets()
                    showDualTargetDialog = false
                },
                onClear = {
                    viewModel.clearPerAppOverrides()
                    showDualTargetDialog = false
                },
                onDismiss = { showDualTargetDialog = false }
            )
        }

        // Map Layer Dialog
        if (showMapLayerDialog) {
            AlertDialog(
                onDismissRequest = { showMapLayerDialog = false },
                icon = {
                    Icon(
                        Icons.Default.Layers, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        "Pilih Tipe & Gaya Peta",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            "Pilih gaya tampilan peta:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        val mapStyles = listOf(
                            Triple("CUSTOM", "Kustom (JSON Styling)", "Abu-abu lembut, jalan putih, air biru"),
                            Triple("NORMAL", "Normal (OpenStreetMap)", "Tampilan standar OSM"),
                            Triple("SATELLITE", "Satelit (USGS)", "Foto satelit permukaan bumi"),
                            Triple("TERRAIN", "Topografi", "Garis kontur elevasi")
                        )
                        mapStyles.forEach { (typeKey, title, desc) ->
                            val isSelected = config.mapType == typeKey
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        viewModel.setMapType(typeKey)
                                        showMapLayerDialog = false
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        Text(
                                            desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMapLayerDialog = false }) { Text("Selesai") }
                }
            )
        }
    }
}

// ==================================================================
// Helper Composable: InjectPill
// ==================================================================
@Composable
private fun InjectPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .testTag(tag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ==================================================================
// Helper Composable: SpoofToggleButton (3 tombol Play/Stop)
// ==================================================================
@Composable
private fun SpoofToggleButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    isLarge: Boolean = false
) {
    val height = 48.dp
    val corner = 14.dp
    val iconSize = if (isLarge) 20.dp else 17.dp
    val labelSize = 11.sp

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = if (active) AccentRed else Color(0xFF1A73E8),
        contentColor = Color.White,
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
        modifier = modifier
            .height(height)
            .testTag(tag)
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = labelSize,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ==================================================================
// Helper Composable: InjectConfirmDialog
// ==================================================================
@Composable
private fun InjectConfirmDialog(
    title: String,
    body: String,
    warning: String,
    accentColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tag: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Refresh, null,
                tint = accentColor,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                modifier = Modifier.testTag(tag)
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Inject")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

// ==================================================================
// Helper Composable: DualTargetDialog
// ==================================================================
@Composable
private fun DualTargetDialog(
    defaultLat: Double,
    defaultLng: Double,
    gojekTarget: Pair<Double, Double>?,
    grabTarget: Pair<Double, Double>?,
    onSaveAndInject: (Double?, Double?, Double?, Double?, Boolean, Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var gojekLat by remember {
        mutableStateOf(gojekTarget?.first?.let { String.format(Locale.US, "%.6f", it) } ?: "")
    }
    var gojekLng by remember {
        mutableStateOf(gojekTarget?.second?.let { String.format(Locale.US, "%.6f", it) } ?: "")
    }
    var grabLat by remember {
        mutableStateOf(grabTarget?.first?.let { String.format(Locale.US, "%.6f", it) } ?: "")
    }
    var grabLng by remember {
        mutableStateOf(grabTarget?.second?.let { String.format(Locale.US, "%.6f", it) } ?: "")
    }
    var saveGojek by remember { mutableStateOf(gojekTarget != null) }
    var saveGrab by remember { mutableStateOf(grabTarget != null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Sync, null,
                tint = Color(0xFF1A73E8),
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                "Injeksi Dual Target",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Isi koordinat khusus untuk masing-masing aplikasi.",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Gojek
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalShipping, null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Gojek Partner", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Checkbox(checked = saveGojek, onCheckedChange = { saveGojek = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = gojekLat,
                        onValueChange = { gojekLat = it },
                        label = { Text("Lat", fontSize = 11.sp) },
                        singleLine = true,
                        enabled = saveGojek,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = gojekLng,
                        onValueChange = { gojekLng = it },
                        label = { Text("Lng", fontSize = 11.sp) },
                        singleLine = true,
                        enabled = saveGojek,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(
                        onClick = {
                            gojekLat = String.format(Locale.US, "%.6f", defaultLat)
                            gojekLng = String.format(Locale.US, "%.6f", defaultLng)
                            saveGojek = true
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                    ) { Text("Pakai Pin Peta", fontSize = 11.sp) }
                    if (gojekTarget != null) {
                        TextButton(
                            onClick = { gojekLat = ""; gojekLng = ""; saveGojek = false },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) { Text("Bersihkan", fontSize = 11.sp, color = AccentRed) }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(16.dp))

                // Grab
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsCar, null, tint = AccentOrange, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Grab Driver", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Checkbox(checked = saveGrab, onCheckedChange = { saveGrab = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = grabLat,
                        onValueChange = { grabLat = it },
                        label = { Text("Lat", fontSize = 11.sp) },
                        singleLine = true,
                        enabled = saveGrab,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = grabLng,
                        onValueChange = { grabLng = it },
                        label = { Text("Lng", fontSize = 11.sp) },
                        singleLine = true,
                        enabled = saveGrab,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(
                        onClick = {
                            grabLat = String.format(Locale.US, "%.6f", defaultLat)
                            grabLng = String.format(Locale.US, "%.6f", defaultLng)
                            saveGrab = true
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                    ) { Text("Pakai Pin Peta", fontSize = 11.sp) }
                    if (grabTarget != null) {
                        TextButton(
                            onClick = { grabLat = ""; grabLng = ""; saveGrab = false },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) { Text("Bersihkan", fontSize = 11.sp, color = AccentRed) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val gLat = if (saveGojek && gojekLat.isNotBlank()) gojekLat.trim().toDoubleOrNull() else null
                    val gLng = if (saveGojek && gojekLng.isNotBlank()) gojekLng.trim().toDoubleOrNull() else null
                    val rLat = if (saveGrab && grabLat.isNotBlank()) grabLat.trim().toDoubleOrNull() else null
                    val rLng = if (saveGrab && grabLng.isNotBlank()) grabLng.trim().toDoubleOrNull() else null
                    val gValid = gLat == null || (gLat in -90.0..90.0 && (gLng ?: 0.0) in -180.0..180.0)
                    val rValid = rLat == null || (rLat in -90.0..90.0 && (rLng ?: 0.0) in -180.0..180.0)
                    if (!gValid || !rValid) return@Button
                    onSaveAndInject(gLat, gLng, rLat, rLng, saveGojek, saveGrab)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8)),
                modifier = Modifier.testTag("btn_dual_inject_confirm")
            ) {
                Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Inject Keduanya")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.testTag("btn_dual_clear")
                ) { Text("Reset Override", color = AccentRed) }
                TextButton(onClick = onDismiss) { Text("Batal") }
            }
        }
    )
}

// ==================================================================
// Utility: parseCoordinates
// ==================================================================
private fun parseCoordinates(text: String): Pair<Double, Double>? {
    val clean = text.trim()
    val coordString = when {
        clean.contains("q=") -> clean.substringAfter("q=").substringBefore("&")
        clean.contains("/@") -> clean.substringAfter("/@").substringBefore("/")
        clean.startsWith("geo:", ignoreCase = true) ->
            clean.removePrefix("geo:").removePrefix("GEO:").substringBefore("?")
        else -> clean
    }
    val regex = Regex("""(-?\d{1,2}(?:\.\d+)?)[,\s;/|]+(-?\d{1,3}(?:\.\d+)?)""")
    val match = regex.find(coordString) ?: return null
    return try {
        val lat = match.groupValues[1].toDouble()
        val lng = match.groupValues[2].toDouble()
        if (lat in -90.0..90.0 && lng in -180.0..180.0) Pair(lat, lng) else null
    } catch (_: Exception) {
        null
    }
}