package nl.jeroen.massqueue.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme(val displayName: String, val description: String) {
    ORIGINAL("Original", "Oorspronkelijke crème & lichtblauw stijl"),
    CASSETTE("Cassette Futurism", "Warm crème, mosterdgeel & petrol teal"),
    CYBERPUNK("Midnight Synthwave", "Elektrisch cyan, neon paars & hot pink"),
    OCEAN("Deep Emerald", "Rijk emerald groen, goud & diepblauw"),
    NORDIC("OLED Minimalist", "Puur OLED zwart & ijsblauw accent")
}

// 0. Original Palette (Classic Cream & Light MA Blue Accent)
private val OriginalColors = lightColorScheme(
    primary = Color(0xFF8DB4BA), // SpinFlow grijsblauw
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8DB4BA),
    onPrimaryContainer = Color(0xFFFAF3E0),
    secondary = Color(0xFFE3A008), // CassetteMustard
    onSecondary = Color(0xFF1C1B19),
    background = Color(0xFFFAF3E0), // CassetteCream
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEFE7D0),
    onSurfaceVariant = Color(0xFF5F5E58),
    outline = Color(0xFFCDC6B2),
    error = Color(0xFFD94D43)
)

// 1. Cassette Futurism Palette
val CassetteCream = Color(0xFFFAF3E0)
val CassetteInk = Color(0xFF161513)
val CassetteDarkSurface = Color(0xFF22201C)
val CassetteCardSurface = Color(0xFF2B2823)
val CassetteMustard = Color(0xFFE5A812)
val CassetteTeal = Color(0xFF0F6D64)
val CassetteTealLight = Color(0xFF179286)
val CassetteMuted = Color(0xFF8A867C)
val CassetteBorder = Color(0xFF3D3A34)
val CassetteRed = Color(0xFFD94D43)

private val CassetteDarkColors = darkColorScheme(
    primary = CassetteMustard,
    onPrimary = CassetteInk,
    primaryContainer = CassetteTeal,
    onPrimaryContainer = CassetteCream,
    secondary = CassetteTealLight,
    onSecondary = CassetteCream,
    background = CassetteInk,
    onBackground = CassetteCream,
    surface = CassetteDarkSurface,
    onSurface = CassetteCream,
    surfaceVariant = CassetteCardSurface,
    onSurfaceVariant = Color(0xFFD5D0C3),
    outline = CassetteBorder,
    error = CassetteRed
)

// 2. Midnight Cyberpunk Palette
private val CyberpunkDarkColors = darkColorScheme(
    primary = Color(0xFF00E5FF), // Electric Cyan
    onPrimary = Color(0xFF0D0B18),
    primaryContainer = Color(0xFF5E35B1), // Neon Purple Container
    onPrimaryContainer = Color(0xFFF3E5F5),
    secondary = Color(0xFFFF4081), // Hot Pink
    onSecondary = Color.White,
    background = Color(0xFF0D0B18), // Deep Space Void
    onBackground = Color(0xFFF3F0FF),
    surface = Color(0xFF15102A),
    onSurface = Color(0xFFF3F0FF),
    surfaceVariant = Color(0xFF1F183D),
    onSurfaceVariant = Color(0xFFC4B8E5),
    outline = Color(0xFF3A2D5C),
    error = Color(0xFFFF1744)
)

// 3. Deep Emerald Palette
private val OceanDarkColors = darkColorScheme(
    primary = Color(0xFF10B981), // Bright Emerald
    onPrimary = Color(0xFF062E1E),
    primaryContainer = Color(0xFF0369A1), // Deep Ocean Blue
    onPrimaryContainer = Color(0xFFECFDF5),
    secondary = Color(0xFFF59E0B), // Warm Gold
    onSecondary = Color(0xFF1F1501),
    background = Color(0xFF0A131F), // Deep Abyssal Navy
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF101E2E),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1A2A3E),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2E4057),
    error = Color(0xFFEF4444)
)

// 4. OLED Minimalist Palette
private val NordicDarkColors = darkColorScheme(
    primary = Color(0xFF38BDF8), // Ice Sky Blue
    onPrimary = Color(0xFF08090A),
    primaryContainer = Color(0xFF1E293B), // Dark Slate Container
    onPrimaryContainer = Color(0xFFF8FAFC),
    secondary = Color(0xFFE2E8F0),
    onSecondary = Color(0xFF0F172A),
    background = Color(0xFF08090A), // Pure OLED Black
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1A1D24),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2D333F),
    error = Color(0xFFF87171)
)

@Composable
fun MassQueueTheme(
    appTheme: AppTheme = AppTheme.CASSETTE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = when (appTheme) {
        AppTheme.ORIGINAL -> OriginalColors
        AppTheme.CASSETTE -> CassetteDarkColors
        AppTheme.CYBERPUNK -> CyberpunkDarkColors
        AppTheme.OCEAN -> OceanDarkColors
        AppTheme.NORDIC -> NordicDarkColors
    }
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
