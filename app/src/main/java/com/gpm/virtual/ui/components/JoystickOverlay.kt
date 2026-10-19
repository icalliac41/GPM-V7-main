package com.gpm.virtual.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class TravelMode(val speedMps: Float, val label: String) {
    WALK(2.0f, "Walk"),
    BIKE(6.0f, "Bike"),
    CAR(16.0f, "Car")
}

@Composable
fun JoystickOverlay(
    isVisible: Boolean,
    onMove: (deltaLatMeters: Double, deltaLngMeters: Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        var travelMode by remember { mutableStateOf(TravelMode.WALK) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        val maxRadius = 48f

        // Continuous movement loop while dragging joystick thumb
        LaunchedEffect(dragOffset, travelMode) {
            val dist = sqrt(dragOffset.x * dragOffset.x + dragOffset.y * dragOffset.y)
            if (dist > 5f) {
                while (true) {
                    val normalizedX = (dragOffset.x / maxRadius).coerceIn(-1f, 1f)
                    val normalizedY = (dragOffset.y / maxRadius).coerceIn(-1f, 1f)
                    val step = travelMode.speedMps.toDouble() * 0.25
                    onMove(-normalizedY * step, normalizedX * step)
                    delay(120)
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(16.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(24.dp)
                )
                .testTag("joystick_overlay")
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(12.dp)
            ) {
                // Header with close button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.width(170.dp)
                ) {
                    Text(
                        text = "Virtual Joystick",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp).testTag("joystick_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Joystick",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Virtual Joystick Base & Thumb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), CircleShape)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { dragOffset = Offset.Zero },
                                onDragCancel = { dragOffset = Offset.Zero },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val newOffset = dragOffset + dragAmount
                                    val distance = sqrt(newOffset.x * newOffset.x + newOffset.y * newOffset.y)
                                    dragOffset = if (distance > maxRadius) {
                                        val factor = maxRadius / distance
                                        Offset(newOffset.x * factor, newOffset.y * factor)
                                    } else {
                                        newOffset
                                    }
                                }
                            )
                        }
                ) {
                    // Direction indicators
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "North",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.TopCenter).size(20.dp)
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "South",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.BottomCenter).size(20.dp)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "West",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.CenterStart).size(20.dp)
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "East",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.CenterEnd).size(20.dp)
                    )

                    // Joystick Thumb
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                            .size(44.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Speed Selector Pill (Walk / Bike / Car)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .padding(2.dp)
                ) {
                    SpeedOptionItem(
                        selected = travelMode == TravelMode.WALK,
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        label = "Walk",
                        onClick = { travelMode = TravelMode.WALK }
                    )
                    SpeedOptionItem(
                        selected = travelMode == TravelMode.BIKE,
                        icon = Icons.AutoMirrored.Filled.DirectionsBike,
                        label = "Bike",
                        onClick = { travelMode = TravelMode.BIKE }
                    )
                    SpeedOptionItem(
                        selected = travelMode == TravelMode.CAR,
                        icon = Icons.Default.DirectionsCar,
                        label = "Car",
                        onClick = { travelMode = TravelMode.CAR }
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedOptionItem(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val bgColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
