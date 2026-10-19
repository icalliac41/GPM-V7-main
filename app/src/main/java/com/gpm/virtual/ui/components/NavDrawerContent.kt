package com.gpm.virtual.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpm.virtual.ui.theme.AccentGreen
import com.gpm.virtual.ui.theme.AccentOrange

enum class DrawerDestination(val title: String, val icon: ImageVector) {
    MAP("Peta Utama", Icons.Default.Map),
    FAVORITES("Favorites", Icons.Default.Star),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun NavDrawerContent(
    currentDestination: DrawerDestination,
    onDestinationSelected: (DrawerDestination) -> Unit,
    isLsposedActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(
        modifier = modifier
            .width(300.dp)
            .fillMaxHeight()
    ) {
        // Drawer Header: "GPM (virtual maps)"
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                .padding(24.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "GPM (virtual maps)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Virtual Maps & LSPosed Hook Module",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isLsposedActive) AccentGreen else AccentOrange)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isLsposedActive) "LSPosed Aktif" else "LSPosed Belum Aktif",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLsposedActive) AccentGreen else AccentOrange
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Drawer Menu Items
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            DrawerDestination.values().forEach { destination ->
                val selected = currentDestination == destination
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.title,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = destination.title,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    selected = selected,
                    onClick = {
                        onDestinationSelected(destination)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .testTag("drawer_item_${destination.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), thickness = 0.5.dp)

        Text(
            text = "GPM (virtual maps) • Virtual GPS Spoofing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )
    }
}
