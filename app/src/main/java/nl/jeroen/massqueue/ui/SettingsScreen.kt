package nl.jeroen.massqueue.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nl.jeroen.massqueue.MassLocation
import nl.jeroen.massqueue.MassPlayer

/**
 * Simpel opstartscherm: vraagt het adres van je Music Assistant server.
 * Voorbeeld: http://192.168.x.x:8095
 * (lokaal IP, of jouw eigen VPN/tunnel-adres voor onderweg)
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
    onSetPlayerAlias: (String, String) -> Unit
) {
    var url by remember(initialUrl) { 
        mutableStateOf(initialUrl) 
    }
    var token by remember(initialToken) {
        mutableStateOf(initialToken)
    }
    
    var showVolumePlayers by remember { mutableStateOf(false) }
    var showLocalPlayers by remember { mutableStateOf(false) }
    var showHiddenPlayers by remember { mutableStateOf(false) }
    var showAliases by remember { mutableStateOf(false) }
    var showAddLocation by remember { mutableStateOf(false) }
    var newLocationName by remember { mutableStateOf("") }

    // We gebruiken een vaste lichte achtergrond voor dit scherm zodat zwarte tekst altijd leesbaar is
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFFAF3E0) // CassetteCream
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Music Assistant verbinden", 
                style = MaterialTheme.typography.titleLarge,
                color = Color.Black
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pas deze gegevens alleen aan als je weet wat je doet.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server-adres") },
                placeholder = { Text("http://192.168.x.x:8095") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.Black),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedLabelColor = Color.Black,
                    unfocusedLabelColor = Color.Black,
                    focusedBorderColor = Color.Black,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = Color.Black
                )
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API-token (optioneel)") },
                placeholder = { Text("Plak hier je long-lived token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.Black),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedLabelColor = Color.Black,
                    unfocusedLabelColor = Color.Black,
                    focusedBorderColor = Color.Black,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = Color.Black
                )
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onSave(url.trim(), token.trim()) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F5E56), // CassetteTeal
                    contentColor = Color.White
                )
            ) {
                Text("Verbinden")
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(Modifier.height(24.dp))
            
            Text(
                "Hardware Volumeknoppen", 
                style = MaterialTheme.typography.titleMedium,
                color = Color.Black
            )
            Text(
                "Kies welke spelers je via je telefoon wilt bedienen.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(12.dp))
            val selectedCount = players.count { player ->
                volumeControlPlayerIds.contains(player.id) || 
                volumeControlPlayerIds.contains(player.name.lowercase().trim())
            }

            Button(
                onClick = { showVolumePlayers = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F5E56), // CassetteTeal
                    contentColor = Color.White
                )
            ) {
                Text("Selecteer spelers ($selectedCount)")
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(Modifier.height(24.dp))
            
            Text(
                "Locaties", 
                style = MaterialTheme.typography.titleMedium,
                color = Color.Black
            )
            Text(
                "Kies een actieve locatie voor geofencing.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(8.dp))
            
            locations.forEach { loc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = loc.id == activeLocationId,
                        onClick = { onSelectLocation(loc.id) },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0F5E56))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(loc.name, style = MaterialTheme.typography.bodyLarge, color = Color.Black)
                        val lat = loc.lat
                        val lon = loc.lon
                        val coordsText = if (lat != null && lon != null)
                            "${"%.4f".format(lat)}, ${"%.4f".format(lon)}"
                        else
                            "Geen GPS-pin ingesteld"
                        Text(coordsText, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (locations.size > 1) {
                        IconButton(onClick = { onDeleteLocation(loc.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Verwijderen", tint = Color.Gray)
                        }
                    }
                }
            }
            
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showAddLocation = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F5E56))
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nieuwe toevoegen")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onPinLocation,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F5E56))
                ) {
                    Icon(Icons.Default.Place, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("GPS Pin")
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(Modifier.height(24.dp))
            
            Text(
                "Thuislocatie Filter", 
                style = MaterialTheme.typography.titleMedium,
                color = Color.Black
            )
            Text(
                "De app verbergt lokale speakers als je niet in de buurt bent.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(12.dp))
            
            val localCount = players.count { player ->
                localPlayerIds.contains(player.id) || 
                localPlayerIds.contains(player.name.lowercase().trim())
            }
            
            Button(
                onClick = { showLocalPlayers = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F5E56), // CassetteTeal
                    contentColor = Color.White
                )
            ) {
                Text("Selecteer lokale spelers ($localCount)")
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(Modifier.height(24.dp))

            Text(
                "Spelers in keuzelijst",
                style = MaterialTheme.typography.titleMedium,
                color = Color.Black
            )
            Text(
                "Kies welke spelers zichtbaar zijn in de speler-keuzelijst op het hoofdscherm.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(12.dp))

            val visibleCount = players.count { player ->
                !(hiddenPlayerIds.contains(player.id) ||
                  hiddenPlayerIds.contains(player.name.lowercase().trim()))
            }

            Button(
                onClick = { showHiddenPlayers = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F5E56), // CassetteTeal
                    contentColor = Color.White
                )
            ) {
                Text("Selecteer zichtbare spelers ($visibleCount/${players.size})")
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = Color.LightGray)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { showAliases = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F5E56), // CassetteTeal
                    contentColor = Color.White
                )
            ) {
                Text("Speler roepnamen instellen")
            }

            Spacer(Modifier.height(32.dp))

            Spacer(Modifier.weight(1f))
            Text(
                "Gemaakt door Jeroen van Sonsbeek",
                style = MaterialTheme.typography.labelSmall,
                color = Color.DarkGray
            )
            Text(
                "jeroenvansonsbeek@gmail.com",
                style = MaterialTheme.typography.labelSmall,
                color = Color.DarkGray
            )
        }
    }

    if (showAddLocation) {
        AlertDialog(
            onDismissRequest = { showAddLocation = false },
            title = { Text("Nieuwe locatie") },
            text = {
                Column {
                    Text("Geef deze locatie een naam (bijv. 'Kantoor' of 'Vakantiehuis')")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newLocationName,
                        onValueChange = { newLocationName = it },
                        placeholder = { Text("Naam") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newLocationName.isNotBlank()) {
                            onAddLocation(newLocationName)
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

    if (showVolumePlayers) {
        ModalBottomSheet(
            onDismissRequest = { showVolumePlayers = false },
            containerColor = Color(0xFFFAF3E0) // CassetteCream
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "VOLUME BEDIENING", 
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showVolumePlayers = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Sluiten", tint = Color.Black)
                    }
                }
                Text(
                    "Vink de spelers aan waarvoor de hardware volumeknoppen van je telefoon moeten werken.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
                Spacer(Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(players) { player ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = volumeControlPlayerIds.contains(player.id),
                                onCheckedChange = { onToggleVolumePlayer(player.id) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF0F5E56),
                                    uncheckedColor = Color.Gray,
                                    checkmarkColor = Color.White
                                )
                            )
                            Text(
                                player.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLocalPlayers) {
        ModalBottomSheet(
            onDismissRequest = { showLocalPlayers = false },
            containerColor = Color(0xFFFAF3E0) // CassetteCream
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LOKALE SPELERS", 
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showLocalPlayers = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Sluiten", tint = Color.Black)
                    }
                }
                Text(
                    "Vink de spelers aan die verborgen moeten worden als je verder dan 150 meter van huis bent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
                Spacer(Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(players) { player ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = localPlayerIds.contains(player.id),
                                onCheckedChange = { onToggleLocalPlayer(player.id) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF0F5E56),
                                    uncheckedColor = Color.Gray,
                                    checkmarkColor = Color.White
                                )
                            )
                            Text(
                                player.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHiddenPlayers) {
        ModalBottomSheet(
            onDismissRequest = { showHiddenPlayers = false },
            containerColor = Color(0xFFFAF3E0) // CassetteCream
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SPELERS IN KEUZELIJST",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showHiddenPlayers = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Sluiten", tint = Color.Black)
                    }
                }
                Text(
                    "Vink de spelers aan die je wilt zien in de keuzelijst op het hoofdscherm. Uitgevinkte spelers worden verborgen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(players) { player ->
                        val isVisible = !(hiddenPlayerIds.contains(player.id) ||
                            hiddenPlayerIds.contains(player.name.lowercase().trim()))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isVisible,
                                onCheckedChange = { onToggleHiddenPlayer(player.id) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF0F5E56),
                                    uncheckedColor = Color.Gray,
                                    checkmarkColor = Color.White
                                )
                            )
                            Text(
                                player.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Black,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAliases) {
        ModalBottomSheet(
            onDismissRequest = { showAliases = false },
            containerColor = Color(0xFFFAF3E0) // CassetteCream
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SPELER ROEPNAMEN", 
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showAliases = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Sluiten", tint = Color.Black)
                    }
                }
                Text(
                    "Geef je spelers een eigen naam voor in de app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
                Spacer(Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(players) { player ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                player.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            OutlinedTextField(
                                value = playerAliases[player.id] ?: "",
                                onValueChange = { onSetPlayerAlias(player.id, it) },
                                placeholder = { Text(player.name) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(color = Color.Black),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black,
                                    focusedBorderColor = Color(0xFF0F5E56),
                                    cursorColor = Color.Black
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
