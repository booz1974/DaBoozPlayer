package nl.jeroen.massqueue.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cassette Futurism-palet: crème, mosterdgeel, petrolblauw, bijna-zwart
val CassetteCream = Color(0xFFFAF3E0)
val CassetteInk = Color(0xFF1C1B19)
val CassetteMustard = Color(0xFFE3A008)
val CassetteTeal = Color(0xFF0F5E56)
val CassetteMuted = Color(0xFF5F5E58)

private val DarkColors = darkColorScheme(
    primary = CassetteMustard,
    background = CassetteInk,
    surface = Color(0xFF262521),
    onSurface = CassetteCream,
    onBackground = CassetteCream,
    surfaceVariant = Color(0xFF35332D),
    onSurfaceVariant = Color(0xFFB8B3A6),
    primaryContainer = CassetteTeal,
    onPrimaryContainer = CassetteCream
)

private val LightColors = lightColorScheme(
    primary = CassetteTeal,
    background = CassetteCream,
    surface = Color(0xFFFFFFFF),
    onSurface = CassetteInk,
    onBackground = CassetteInk,
    surfaceVariant = Color(0xFFEFE7D0),
    onSurfaceVariant = CassetteMuted,
    primaryContainer = CassetteTeal,
    onPrimaryContainer = CassetteCream
)

@Composable
fun MassQueueTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
