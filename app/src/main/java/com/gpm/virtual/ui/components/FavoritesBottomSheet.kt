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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpm.virtual.data.model.FavoriteLocation
import com.gpm.virtual.ui.theme.AccentRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesBottomSheet(
    sheetState: SheetState,
    favorites: List<FavoriteLocation>,
    onSelectLocation: (FavoriteLocation) -> Unit,
    onDeleteFavorite: (FavoriteLocation) -> Unit,
    onAddNewFavorite: () -> Unit,
    onOpenFullScreen: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 36.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp)
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
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AccentRed.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = AccentRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Lokasi Favorit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${favorites.size} lokasi tersimpan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Button to save current location
                    OutlinedButton(
                        onClick = onAddNewFavorite,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("btn_sheet_add_favorite")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("+ Simpan", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (onOpenFullScreen != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onOpenFullScreen,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("btn_open_favorites_screen")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Buka Halaman Favorit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("btn_close_favorites_sheet")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Tutup",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (favorites.isEmpty()) {
                // Empty state
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp, horizontal = 24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.StarOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Belum Ada Lokasi Favorit",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Simpan titik lokasi saat ini untuk mengakses koordinat favorit dengan cepat kapan saja.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onAddNewFavorite,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("btn_empty_add_favorite")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Simpan Titik Saat Ini ke Favorit")
                        }
                    }
                }
            } else {
                // List of favorite items
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(favorites, key = { it.id }) { item ->
                        FavoriteSheetItemCard(
                            item = item,
                            onSelect = { onSelectLocation(item) },
                            onDelete = { onDeleteFavorite(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteSheetItemCard(
    item: FavoriteLocation,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("favorite_sheet_item_${item.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentRed.copy(alpha = 0.12f))
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = String.format(Locale.US, "%.6f, %.6f", item.latitude, item.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
                Text(
                    text = dateFormat.format(Date(item.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("btn_delete_fav_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Hapus Favorit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("btn_select_fav_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Pilih", fontSize = 12.sp)
            }
        }
    }
}
