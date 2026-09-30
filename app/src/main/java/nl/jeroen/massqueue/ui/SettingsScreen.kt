package nl.jeroen.massqueue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.jeroen.massqueue.BuildConfig
import nl.jeroen.massqueue.DEFAULT_SENDSPIN_LOCAL_URL
import nl.jeroen.massqueue.MassLocation
import nl.jeroen.massqueue.MassPlayer
import nl.jeroen.massqueue.PhonePlayerStatus
import nl.jeroen.massqueue.ui.theme.AppTheme
import nl.jeroen.massqueue.ui.theme.colorSchemeFor

/**
 * Modern retro-futuristic Settings Screen for SpinFlow
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialUrl: String,
    initialToken: String,
    locations: List<MassLocation>,
    activeLocationId: String?,
    players: List<MassPlayer>,
    volumeControlPlayerIds: Set<String>,
    localPlayerIds: Set<String>,
    hiddenPlayerIds: Set<String>,
    playerAliases: Map<String, String>,
    onSave: (url: String, token: String) -> Unit,
    onAddLocation: (String) -> Unit,
    onDeleteLocation: (String) -> Unit,
    onSelectLocation: (String) -> Unit,
    onPinLocation: () -> Unit,
    onToggleVolumePlayer: (String) -> Unit,
    onToggleLocalPlayer: (String) -> Unit,
    onToggleHiddenPlayer: (String) -> Unit,
    onSetPlayerAlias: (String, String) -> Unit,
    selectedTheme: AppTheme = AppTheme.CASSETTE,
    onSelectTheme: ((AppTheme) -> Unit)? = null,
    showCompactHeader: Boolean = false,
    onToggleCompactHeader: ((Boolean) -> Unit)? = null,
    discoPlayerId: String? = null,
    onSelectDiscoPlayer: ((String) -> Unit)? = null,
    pinnedPlayerIds: Set<String> = emptySet(),
    onTogglePinnedPlayer: ((String) -> Unit)? = null,
    phonePlayerEnabled: Boolean = false,
    phonePlayerName: String = "",
    phonePlayerLocalUrl: String = DEFAULT_SENDSPIN_LOCAL_URL,
    phonePlayerStatus: PhonePlayerStatus = PhonePlayerStatus(),
    onTogglePhonePlayer: ((Boolean) -> Unit)? = null,
    onSavePhonePlayer: ((name: String, localUrl: String) -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }

    var showVolumePlayers by remember { mutableStateOf(false) }
    var showLocalPlayers by remember { mutableStateOf(false) }
    var showHiddenPlayers by remember { mutableStateOf(false) }
    var showAliases by remember { mutableStateOf(false) }
    var showDiscoPlayer by remember { mutableStateOf(false) }
    var showPinnedPlayers by remember { mutableStateOf(false) }
    var showAddLocation by remember { mutableStateOf(false) }
    var newLocationName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Instellingen",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "SpinFlow & Music Assistant",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                        }
                    } else {
                        IconButton(onClick = { if (url.isNotBlank()) onSave(url.trim(), token.trim()) }) {
                            Icon(Icons.Default.Tune, contentDescription = "Instellingen")
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = { onSave(url.trim(), token.trim()) },
                        enabled = url.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Opslaan", fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Server Configuration Card
            item {
                SettingsSectionCard(
                    title = "Music Assistant Server",
                    subtitle = "Samen met Home Assistant JSON-RPC & WebSocket",
                    icon = Icons.Outlined.Dns
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server Adres (URL)") },
                        placeholder = { Text("https://homeassistant.<tailnet>.ts.net") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(12.dp))

                    var showToken by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("API Access Token (Optioneel)") },
                        placeholder = { Text("Plak hier je long-lived access token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showToken) "Token verbergen" else "Token tonen"
                                )
                            }
                        },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Telefoon als speler (Sendspin)
            item {
                SettingsSectionCard(
                    title = "Telefoon als speler",
                    subtitle = "Speel muziek van Music Assistant af op deze telefoon, ook op de achtergrond",
                    icon = Icons.Outlined.PhoneAndroid
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTogglePhonePlayer?.invoke(!phonePlayerEnabled) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Telefoon als speler",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (phonePlayerEnabled) phonePlayerStatus.text
                                    else "Uit: deze telefoon verschijnt niet als speler in MA",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (phonePlayerEnabled && phonePlayerStatus.isError) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = phonePlayerEnabled,
                                onCheckedChange = { onTogglePhonePlayer?.invoke(it) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    var phoneName by remember(phonePlayerName) { mutableStateOf(phonePlayerName) }
                    var phoneUrl by remember(phonePlayerLocalUrl) { mutableStateOf(phonePlayerLocalUrl) }
                    OutlinedTextField(
                        value = phoneName,
                        onValueChange = { phoneName = it },
                        label = { Text("Naam in Music Assistant") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Badge, contentDescription = null) },
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = phoneUrl,
                        onValueChange = { phoneUrl = it },
                        label = { Text("Sendspin-adres thuis (terugval)") },
                        placeholder = { Text(DEFAULT_SENDSPIN_LOCAL_URL) },
                        supportingText = { Text("Eerst via het server-adres hierboven, met je API-token; dit adres alleen als dat niet lukt") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(12.dp)
                    )
                    val changed = phoneName.trim() != phonePlayerName || phoneUrl.trim() != phonePlayerLocalUrl
                    if (changed) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onSavePhonePlayer?.invoke(phoneName, phoneUrl) },
                            enabled = phoneName.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Toepassen", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 2. Color Theme Selection Card
            item {
                SettingsSectionCard(
                    title = "Kleurthema & Stijl",
                    subtitle = "Kies jouw favoriete kleurenpalet voor de app",
                    icon = Icons.Outlined.Palette
                ) {
                    var themesExpanded by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { themesExpanded = !themesExpanded }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ThemeSwatches(selectedTheme)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedTheme.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    selectedTheme.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                if (themesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (themesExpanded) "Thema's inklappen" else "Thema's uitklappen"
                            )
                        }
                    }
                    if (themesExpanded) AppTheme.entries.forEach { theme ->
                        val isSelected = theme == selectedTheme
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectTheme?.invoke(theme) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onSelectTheme?.invoke(theme) }
                                )
                                Spacer(Modifier.width(8.dp))
                                ThemeSwatches(theme)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        theme.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        theme.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Header Layout Card
            item {
                SettingsSectionCard(
                    title = "Header Lay-out",
                    subtitle = "Kies tussen de cassette-banner of een compacte minimale header",
                    icon = Icons.Outlined.ViewStream
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onToggleCompactHeader?.invoke(!showCompactHeader) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Compacte Minimalist Header",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (showCompactHeader) "Actief: verbergt de cassette-banner voor maximale schermruimte"
                                    else "Uitgeschakeld: toont de grote cassette-banner bovenin",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = showCompactHeader,
                                onCheckedChange = { onToggleCompactHeader?.invoke(it) }
                            )
                        }
                    }
                }
            }

            // 2. Hardware Volume Control Card
            item {
                val selectedVolumeCount = players.count { p ->
                    volumeControlPlayerIds.contains(p.id) ||
                            volumeControlPlayerIds.contains(p.name.lowercase().trim())
                }

                SettingsActionCard(
                    title = "Hardware Volumeknoppen",
                    subtitle = "Bedien de fysieke volumeknoppen van je telefoon ($selectedVolumeCount actief)",
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    badgeText = "$selectedVolumeCount / ${players.size}",
                    onClick = { showVolumePlayers = true }
                )
            }

            // 3. Geofencing & Locations Card
            item {
                SettingsSectionCard(
                    title = "Locaties & Geofencing",
                    subtitle = "Thuislocatie bepalen om slimme spelerfiltering in te schakelen",
                    icon = Icons.Outlined.PinDrop
                ) {
                    locations.forEach { loc ->
                        val isSelected = loc.id == activeLocationId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectLocation(loc.id) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onSelectLocation(loc.id) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        loc.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    val lat = loc.lat
                                    val lon = loc.lon
                                    val coordsText = if (lat != null && lon != null)
                                        "GPS: ${"%.4f".format(lat)}, ${"%.4f".format(lon)}"
                                    else
                                        "Geen GPS coördinaten ingesteld"
                                    Text(
                                        coordsText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (locations.size > 1) {
                                    IconButton(onClick = { onDeleteLocation(loc.id) }) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Verwijderen",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAddLocation = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Locatie")
                        }

                        Button(
                            onClick = onPinLocation,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GPS Pin")
                        }
                    }
                }
            }

            // 4. Local Players Filtering Card
            item {
                val localCount = players.count { p ->
                    localPlayerIds.contains(p.id) || localPlayerIds.contains(p.name.lowercase().trim())
                }

                SettingsActionCard(
                    title = "Thuislocatie Filter (150m)",
                    subtitle = "Lokale speakers verbergen wanneer je buitenshuis bent",
                    icon = Icons.Outlined.HomeWork,
                    badgeText = "$localCount lokaal",
                    onClick = { showLocalPlayers = true }
                )
            }

            // 5. Visible Players Card
            item {
                val visibleCount = players.count { p ->
                    !(hiddenPlayerIds.contains(p.id) || hiddenPlayerIds.contains(p.name.lowercase().trim()))
                }

                SettingsActionCard(
                    title = "Spelers in Keuzelijst",
                    subtitle = "Verberg of toon specifieke spelers in het hoofdmenu",
                    icon = Icons.Outlined.Visibility,
                    badgeText = "$visibleCount / ${players.size}",
                    onClick = { showHiddenPlayers = true }
                )
            }

            // 6. Player Aliases Card
            item {
                val aliasedCount = players.count { p -> !playerAliases[p.id].isNullOrBlank() }

                SettingsActionCard(
                    title = "Speler Roepnamen (Aliassen)",
                    subtitle = "Geef speakers een aangepaste weergavenaam",
                    icon = Icons.Outlined.Edit,
                    badgeText = if (aliasedCount > 0) "$aliasedCount actief" else "Instellen",
                    onClick = { showAliases = true }
                )
            }

            // Vaste spelers (nooit onder "Overige spelers")
            item {
                val pinnedCount = players.count { it.id in pinnedPlayerIds }
                SettingsActionCard(
                    title = "Vaste Spelers",
                    subtitle = "Altijd bovenin de keuzelijst, nooit onder \"Overige spelers\"",
                    icon = Icons.Outlined.PushPin,
                    badgeText = if (pinnedCount > 0) "$pinnedCount vast" else "Instellen",
                    onClick = { showPinnedPlayers = true }
                )
            }

            // 7. Disco Player Card
            item {
                val discoName = players.firstOrNull { it.id == discoPlayerId }?.name
                SettingsActionCard(
                    title = "Disco-speler",
                    subtitle = discoName ?: "Speler die de disco-schakelaar aan de groep toevoegt",
                    icon = Icons.Outlined.Lightbulb,
                    badgeText = if (discoName != null) "Gekozen" else "Instellen",
                    onClick = { showDiscoPlayer = true }
                )
            }

            // App Footer
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "SpinFlow v${BuildConfig.VERSION_NAME} — Music Assistant Client",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${BuildConfig.APPLICATION_ID} · build ${BuildConfig.BUILD_TIME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ontwikkeld door Jeroen van Sonsbeek",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    // Modal Dialog for Adding Location
    if (showAddLocation) {
        AlertDialog(
            onDismissRequest = { showAddLocation = false },
            title = { Text("Nieuwe Locatie Toevoegen") },
            text = {
                Column {
                    Text(
                        "Geef een naam op voor deze locatie (bijv. 'Thuis', 'Kantoor' of 'Vakantiehuis'):",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newLocationName,
                        onValueChange = { newLocationName = it },
                        placeholder = { Text("Locatienaam") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newLocationName.isNotBlank()) {
                            onAddLocation(newLocationName.trim())
                            newLocationName = ""
                            showAddLocation = false
                        }
                    }
                ) {
                    Text("Toevoegen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddLocation = false }) {
                    Text("Annuleren")
                }
            }
        )
    }

    // Modal Bottom Sheet helper functions
    if (showVolumePlayers) {
        PlayerChecklistSheet(
            title = "Hardware Volume Bediening",
            subtitle = "Kies voor welke spelers de volumeknoppen van je telefoon actief zijn:",
            players = players,
            isChecked = { p ->
                volumeControlPlayerIds.contains(p.id) ||
                        volumeControlPlayerIds.contains(p.name.lowercase().trim())
            },
            onToggle = { onToggleVolumePlayer(it.id) },
            onDismiss = { showVolumePlayers = false }
        )
    }

    if (showLocalPlayers) {
        PlayerChecklistSheet(
            title = "Lokale Thuis-Spelers",
            subtitle = "Kies welke spelers verborgen moeten worden als je meer dan 150m van huis bent:",
            players = players,
            isChecked = { p ->
                localPlayerIds.contains(p.id) ||
                        localPlayerIds.contains(p.name.lowercase().trim())
            },
            onToggle = { onToggleLocalPlayer(it.id) },
            onDismiss = { showLocalPlayers = false }
        )
    }

    if (showHiddenPlayers) {
        PlayerChecklistSheet(
            title = "Spelers in Keuzelijst",
            subtitle = "Vink aan welke spelers zichtbaar zijn in het speler-keuzemenu:",
            players = players,
            isChecked = { p ->
                !(hiddenPlayerIds.contains(p.id) ||
                        hiddenPlayerIds.contains(p.name.lowercase().trim()))
            },
            onToggle = { onToggleHiddenPlayer(it.id) },
            onDismiss = { showHiddenPlayers = false }
        )
    }

    if (showPinnedPlayers) {
        PlayerChecklistSheet(
            title = "Vaste Spelers",
            subtitle = "Groepsleden en lichtspelers staan in de keuzelijst onder \"Overige spelers\". Vink aan welke toch altijd bovenin moeten staan:",
            players = players,
            isChecked = { p -> p.id in pinnedPlayerIds },
            onToggle = { onTogglePinnedPlayer?.invoke(it.id) },
            onDismiss = { showPinnedPlayers = false }
        )
    }

    if (showDiscoPlayer) {
        PlayerChecklistSheet(
            title = "Disco-speler",
            subtitle = "Kies welke speler de disco-schakelaar aan de huidige groep toevoegt:",
            players = players,
            isChecked = { p -> p.id == discoPlayerId },
            onToggle = {
                onSelectDiscoPlayer?.invoke(it.id)
                showDiscoPlayer = false
            },
            onDismiss = { showDiscoPlayer = false }
        )
    }

    if (showAliases) {
        ModalBottomSheet(
            onDismissRequest = { showAliases = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Speler Roepnamen",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showAliases = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Sluiten")
                    }
                }
                Text(
                    "Stel een vriendelijke roepnaam in per speaker voor in de app:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(players) { player ->
                        Column {
                            Text(
                                "Originele naam: ${player.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = playerAliases[player.id] ?: "",
                                onValueChange = { onSetPlayerAlias(player.id, it) },
                                placeholder = { Text(player.name) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun SettingsActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badgeText: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Text(
                    badgeText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerChecklistSheet(
    title: String,
    subtitle: String,
    players: List<MassPlayer>,
    isChecked: (MassPlayer) -> Boolean,
    onToggle: (MassPlayer) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Sluiten")
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(players) { player ->
                    val checked = isChecked(player)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onToggle(player) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggle(player) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                player.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small overlapping color dots previewing a theme's background, primary and secondary colors. */
@Composable
private fun ThemeSwatches(theme: AppTheme) {
    val scheme = colorSchemeFor(theme)
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        listOf(scheme.background, scheme.primary, scheme.secondary).forEach { color ->
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    }
}
