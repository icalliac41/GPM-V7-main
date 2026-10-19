package com.gpm.virtual.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpm.virtual.data.model.NearbyPlace
import com.gpm.virtual.ui.theme.AccentGreen
import com.gpm.virtual.ui.theme.AccentOrange
import com.gpm.virtual.ui.theme.AccentRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyBottomSheet(
    sheetState: SheetState,
    places: List<NearbyPlace>,
    isLoading: Boolean,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onPlaceSelected: (NearbyPlace) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = listOf("Pickup Point", "Semua", "Transportasi", "Belanja", "Kuliner", "Fasilitas")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AccentGreen.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Titik Jemput & Tempat Sekitar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pickup point Google Maps yang diperbarui otomatis sesuai pin GPS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Tutup",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Category Filter Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { cat ->
                    val isSelected = cat == selectedCategory
                    FilterChip(
                        selected = isSelected,
                        onClick = { onCategorySelected(cat) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (cat == "Pickup Point") {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(cat, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (cat == "Pickup Point") AccentGreen else MaterialTheme.colorScheme.primary,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White
                        )
                    )
                }
            }

            // Places List
            if (isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = AccentGreen)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Mengambil titik jemput dari Google Maps...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (places.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Tidak ada titik jemput ditemukan di radius ini.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.height(360.dp)
                ) {
                    items(places, key = { it.id }) { place ->
                        NearbyPlaceItemCard(
                            place = place,
                            onClick = { onPlaceSelected(place) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyPlaceItemCard(
    place: NearbyPlace,
    onClick: () -> Unit
) {
    val isPickup = place.category == "Pickup Point"
    val cardBg = if (isPickup) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("nearby_place_${place.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            val icon = getCategoryIcon(place.category)
            val iconTint = when (place.category) {
                "Pickup Point" -> AccentGreen
                "Transportasi" -> MaterialTheme.colorScheme.primary
                "Belanja" -> AccentOrange
                "Kuliner" -> AccentRed
                "Fasilitas" -> MaterialTheme.colorScheme.secondary
                else -> AccentGreen
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = place.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(iconTint.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = place.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = iconTint,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${place.distanceMeters} meter",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPickup) AccentGreen else MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("Pilih", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun getCategoryIcon(category: String): ImageVector = when (category) {
    "Pickup Point" -> Icons.Default.DirectionsCar
    "Transportasi" -> Icons.Default.Train
    "Belanja" -> Icons.Default.ShoppingCart
    "Kuliner" -> Icons.Default.LocalDining
    "Fasilitas" -> Icons.Default.LocalGasStation
    else -> Icons.Default.Place
}
