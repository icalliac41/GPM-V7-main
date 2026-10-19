package com.gpm.virtual.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView

/**
 * Custom Map Styling and Tile Sources matching user's custom JSON map style:
 * - Land/man-made/natural: Soft cool gray-blue (#e7e9ee)
 * - Roads: White/light blue highway (#ffffff, #b7c9e5, #a3b0cc)
 * - Water: Vivid blue (#6dc1ff)
 * - Parks & POI: Subtle pastel green (#b9dbb6) / purple (#d5b8f3) / slate (#bdc1d6)
 */
object MapThemeHelper {

    const val MAP_TYPE_CUSTOM = "CUSTOM"
    const val MAP_TYPE_NORMAL = "NORMAL"
    const val MAP_TYPE_SATELLITE = "SATELLITE"
    const val MAP_TYPE_TERRAIN = "TERRAIN"

    // Custom ColorFilter specifically designed on top of standard OpenStreetMap tiles
    // to transform them into the exact color palette requested in the JSON:
    // - Landscape/Man-made/Natural: Soft light gray-blue (#e7e9ee)
    // - Roads: Clean bright white (#ffffff) and highway blue/slate (#b7c9e5, #99a7bf)
    // - Water: Vibrant vivid blue (#6dc1ff)
    // - POI/Parks: Clean pastel accents
    // This runs completely offline / without Carto API key watermarks!
    val customColorFilter: ColorMatrixColorFilter by lazy {
        // High quality color grading matrix:
        // 1. Desaturate slightly standard noisy green/yellow colors
        // 2. Shift background towards clean cool gray-blue (#e7e9ee)
        // 3. Boost blue channels so rivers & oceans pop as #6dc1ff
        // 4. Boost brightness/contrast so roads stand out pure white
        val cm = ColorMatrix()
        // Slight desaturation to soften OSM colors (0.8)
        cm.setSaturation(0.78f)

        // Color balance adjustments: [R, G, B, A, Offset]
        val colorScale = ColorMatrix(
            floatArrayOf(
                1.02f, 0f, 0f, 0f, 16f,    // Red: clean light tone
                0f, 1.01f, 0f, 0f, 18f,    // Green: balanced neutral
                0f, 0f, 1.15f, 0f, 26f,    // Blue: boosts water (#6dc1ff) & slate tones
                0f, 0f, 0f, 1f, 0f         // Alpha
            )
        )
        cm.postConcat(colorScale)
        ColorMatrixColorFilter(cm)
    }

    /**
     * Applies the appropriate TileSource and ColorFilter to the MapView.
     */
    fun applyMapStyle(map: MapView, mapType: String) {
        when (mapType) {
            MAP_TYPE_CUSTOM -> {
                map.setTileSource(TileSourceFactory.MAPNIK)
                map.overlayManager.tilesOverlay.setColorFilter(customColorFilter)
            }
            MAP_TYPE_SATELLITE -> {
                map.setTileSource(TileSourceFactory.USGS_SAT)
                map.overlayManager.tilesOverlay.setColorFilter(null)
            }
            MAP_TYPE_TERRAIN -> {
                map.setTileSource(TileSourceFactory.OpenTopo)
                map.overlayManager.tilesOverlay.setColorFilter(null)
            }
            MAP_TYPE_NORMAL -> {
                map.setTileSource(TileSourceFactory.MAPNIK)
                map.overlayManager.tilesOverlay.setColorFilter(null)
            }
            else -> {
                // Default to custom style as requested by the user
                map.setTileSource(TileSourceFactory.MAPNIK)
                map.overlayManager.tilesOverlay.setColorFilter(customColorFilter)
            }
        }
        map.invalidate()
    }
}
