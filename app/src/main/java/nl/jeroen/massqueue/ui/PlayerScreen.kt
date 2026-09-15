package nl.jeroen.massqueue.ui

/**
 * Main UI components for the music player screen.
 * This file contains the primary [PlayerScreen] and all its supporting UI elements
 * like the transport controls, queue list, and various selection sheets.
 */

import android.content.Intent
import android.os.Build.VERSION.SDK_INT
import androidx.core.net.toUri
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import nl.jeroen.massqueue.MassPlayer
import nl.jeroen.massqueue.MassPlaylist
import nl.jeroen.massqueue.MassRadio
import nl.jeroen.massqueue.MassViewModel
import nl.jeroen.massqueue.QueueTrack
import nl.jeroen.massqueue.R
import nl.jeroen.massqueue.RadioHistoryEntry
import nl.jeroen.massqueue.UiState
import nl.jeroen.massqueue.AiRadioStation
import nl.jeroen.massqueue.AiRadioHost
import nl.jeroen.massqueue.AiRadioSection
import nl.jeroen.massqueue.AiRadioOptions
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyColumnState

/** Onthoudt iTunes-artwork per zoekterm (proceslevensduur) zodat we niet elke
 *  recompositie/queue-poll opnieuw dezelfde zoekopdracht doen. "" = niets gevonden. */
private val itunesArtCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Onthoudt het releasejaar per zoekterm (artiest + titel), voor tracks waar
 *  Music Assistant zelf geen bruikbaar jaartal levert. 0 = niets gevonden. */
private val itunesYearCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

/** Titelwoorden die op een cover, remix of andere afwijkende heruitgave wijzen —
 *  die leveren vrijwel nooit het oorspronkelijke releasejaar. Een "remaster" laten we
 *  bewust wél mee, want in de praktijk staat daar meestal gewoon het originele jaar bij. */
private val itunesYearSkipWords = listOf(
    "remix", "mix", "live", "karaoke", "tribute", "cover", "tabata", "workout", "reworked"
)

private fun itunesSearchTerm(artist: String?, title: String?): String? =
    listOfNotNull(artist?.takeIf { it.isNotBlank() }, title?.takeIf { it.isNotBlank() })
        .joinToString(" ")
        .takeIf { it.isNotBlank() }

/**
 * Zoekt het releasejaar van een track op via de iTunes Search API, met cache per term.
 * We vragen meerdere resultaten op, filteren covers/remixes en artiestmismatches eruit,
 * en kiezen in drie stappen:
 *  1. Een resultaat waarvan zowel de tracktitel als het albumnaam exact overeenkomen met
 *     de songtitel (dus het studioalbum dat naar de single is vernoemd) — de sterkste
 *     aanwijzing voor de originele uitgave (bv. Commodores' album "Nightshift" uit 1985,
 *     in plaats van de vele fout gedateerde "Anthology"/"Gold"-verzamelalbums uit 1977).
 *  2. Anders: het vaakst voorkomende jaar onder resultaten met een exact overeenkomende
 *     titel — dat is meestal de originele uitgave, en een los fout gedateerd exemplaar in
 *     iTunes' eigen catalogus (bv. "We Didn't Start the Fire" staat één keer als 1966 i.p.v.
 *     1989) verliest het dan van de meerderheid.
 *  3. Anders: het vaakst voorkomende jaar onder alle overgebleven resultaten.
 */
private suspend fun lookupItunesYear(artist: String?, title: String?): Int? {
    val searchTerm = itunesSearchTerm(artist, title) ?: return null
    itunesYearCache[searchTerm]?.let { return it.takeIf { y -> y != 0 } }
    return try {
        val encodedTerm = java.net.URLEncoder.encode(searchTerm, "UTF-8")
        // country=NL: zonder landcode mist de (Amerikaanse) standaardcatalogus regelmatig
        // de originele Europese uitgave van een track en levert iTunes alleen latere
        // remixes/compilaties op, met een verkeerd releasejaar tot gevolg.
        val searchUrl = "https://itunes.apple.com/search?term=$encodedTerm&entity=song&limit=25&country=NL"
        val year = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val response = okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(searchUrl).build()
            ).execute().body?.string() ?: return@withContext null
            val results = org.json.JSONObject(response).optJSONArray("results") ?: return@withContext null

            data class Candidate(val year: Int, val exactTitle: Boolean, val selfTitledAlbum: Boolean)

            val candidates = (0 until results.length()).mapNotNull { i ->
                val r = results.getJSONObject(i)
                val trackName = r.optString("trackName")
                val artistName = r.optString("artistName")
                val collectionName = r.optString("collectionName")
                val yr = r.optString("releaseDate").takeIf { it.isNotBlank() }?.take(4)?.toIntOrNull()
                    ?: return@mapNotNull null
                if (itunesYearSkipWords.any { trackName.contains(it, ignoreCase = true) }) return@mapNotNull null
                if (artist != null &&
                    !artistName.equals(artist, ignoreCase = true) &&
                    !artistName.contains(artist, ignoreCase = true) &&
                    !artist.contains(artistName, ignoreCase = true)
                ) {
                    return@mapNotNull null
                }
                val exactTitle = trackName.equals(title, ignoreCase = true)
                Candidate(yr, exactTitle, exactTitle && collectionName.equals(title, ignoreCase = true))
            }

            // Meest voorkomende jaar binnen de sterkste beschikbare groep wint;
            // bij gelijkstand het vroegste.
            fun majorityYear(years: List<Int>) = years.groupingBy { it }.eachCount().entries
                .maxWithOrNull(compareBy({ it.value }, { -it.key }))
                ?.key

            val selfTitledYears = candidates.filter { it.selfTitledAlbum }.map { it.year }
            val exactYears = candidates.filter { it.exactTitle }.map { it.year }
            majorityYear(selfTitledYears)
                ?: majorityYear(exactYears)
                ?: majorityYear(candidates.map { it.year })
        }
        itunesYearCache[searchTerm] = year ?: 0
        year
    } catch (_: Exception) {
        null
    }
}

/** Stabiele sleutel voor een "komt hierna"-item bij het slepen/herordenen. */
private fun upcomingKey(t: QueueTrack): String = t.queueItemId ?: "next-${t.absoluteIndex}"

/**
 * Bepaalt uit de server-volgorde en de gesleepte volgorde welk item hoeveel
 * plaatsen verschoven is (het item met de grootste verschuiving), of null als
 * er niets veranderd is.
 */
private fun computeSingleMove(
    server: List<QueueTrack>,
    reordered: List<QueueTrack>
): Pair<QueueTrack, Int>? {
    if (server.size != reordered.size || server.isEmpty()) return null
    val serverKeys = server.map { upcomingKey(it) }
    val reKeys = reordered.map { upcomingKey(it) }
    if (serverKeys == reKeys) return null

    var bestKey: String? = null
    var bestDelta = 0
    reKeys.forEachIndexed { newIdx, k ->
        val oldIdx = serverKeys.indexOf(k)
        if (oldIdx >= 0) {
            val d = newIdx - oldIdx
            if (kotlin.math.abs(d) > kotlin.math.abs(bestDelta)) {
                bestDelta = d
                bestKey = k
            }
        }
    }
    val key = bestKey ?: return null
    if (bestDelta == 0) return null
    val item = server.firstOrNull { upcomingKey(it) == key } ?: return null
    return item to bestDelta
}

/**
 * Primary entry point for the Player screen.
 * Displays the current playing track, transport controls, and the playback queue.
 *
 * @param viewModel The [MassViewModel] providing state and handling actions.
 * @param onOpenSettings Callback invoked when the settings icon is clicked.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(viewModel: MassViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val activePlaylistName = state.activePlaylistName
    var trackForOptions by remember { mutableStateOf<QueueTrack?>(null) }
    var showFavorites by remember { mutableStateOf(false) }
    var showRadios by remember { mutableStateOf(false) }
    var showAiDj by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var playlistForOptions by remember { mutableStateOf<MassPlaylist?>(null) }
    var radioForOptions by remember { mutableStateOf<MassRadio?>(null) }
    var stationToEdit by remember { mutableStateOf<AiRadioStation?>(null) }
    var hostToEdit by remember { mutableStateOf<AiRadioHost?>(null) }
    var sectionToEdit by remember { mutableStateOf<AiRadioSection?>(null) }
    var showLocationWarning by remember { mutableStateOf(true) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showSaveRadioHistory by remember { mutableStateOf(false) }
    val queueListState = rememberLazyListState()

    val selectedPlayer = state.players.find { it.id == state.selectedPlayerId }
    val isPlaying = selectedPlayer?.playbackState?.lowercase() == "playing"

    // Reset de waarschuwing als er weer spelers gevonden worden
    LaunchedEffect(state.players.isNotEmpty()) {
        if (state.players.isNotEmpty()) showLocationWarning = true
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(200.dp)
                    ) {
                        val context = LocalContext.current
                        
                        // We maken twee requests aan die we onthouden
                        val playRequest = remember {
                            ImageRequest.Builder(context)
                                .data(R.drawable.title_logo_play)
                                .decoderFactory(if (SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory())
                                .build()
                        }
                        val stopRequest = remember {
                            ImageRequest.Builder(context)
                                .data(R.drawable.title_logo_stop) // Dit is nu de JPG
                                .build()
                        }

                        // We gebruiken Crossfade voor een vloeiende overgang zonder wit scherm
                        androidx.compose.animation.Crossfade(
                            targetState = isPlaying,
                            animationSpec = tween(500),
                            label = "headerCrossfade"
                        ) { playing ->
                            AsyncImage(
                                model = if (playing) playRequest else stopRequest,
                                contentDescription = "DA_BOOZ_PLAYER",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillWidth
                            )
                        }

                        Text(
                            if (isPlaying && !activePlaylistName.isNullOrBlank()) {
                                activePlaylistName.uppercase()
                            } else {
                                "NU SPELEND"
                            },
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(start = 125.dp, top = 34.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = Color(0xFFFAF3E0), // CassetteCream kleur
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )

                        // Cast-knop rechtsboven in de hoek: stuurt de speler + muziek
                        // naar een ander (cast-)apparaat.
                        IconButton(
                            onClick = { showCast = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 22.dp, end = 6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Cast,
                                contentDescription = "Casten naar apparaat",
                                tint = Color(0xFFFAF3E0)
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        showTransfer = true
                    }) {
                        Icon(
                            Icons.Filled.SwapHoriz, 
                            contentDescription = "Muziek verhuizen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = {
                        showWizard = true
                        viewModel.loadFavoritePlaylists()
                        viewModel.loadAiRadioData()
                    }) {
                        Icon(
                            Icons.Filled.AutoFixHigh, 
                            contentDescription = "Muziek Wizard",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val context = LocalContext.current
                    
                    IconButton(
                        enabled = state.serverUrl.isNotBlank(),
                        onClick = {
                            val target = state.serverUrl.let { if (it.startsWith("http")) it else "http://$it" }
                            val intent = Intent(Intent.ACTION_VIEW, target.toUri())
                            context.startActivity(intent)
                        }
                    ) {
                        // Gebruikt het Music Assistant logo (teal)
                        Icon(
                            painter = painterResource(id = R.drawable.ic_mass_logo),
                            contentDescription = "Open Music Assistant",
                            tint = Color.Unspecified, 
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    IconButton(onClick = {
                        showRadios = true
                        viewModel.loadFavoriteRadios()
                    }) {
                        Icon(
                            Icons.Filled.Radio, 
                            contentDescription = "Favoriete radiozenders",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(onClick = {
                        viewModel.loadAiRadioData()
                        showAiDj = true
                    }) {
                        Icon(
                            Icons.Filled.Psychology, 
                            contentDescription = "AI Radio DJ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    IconButton(onClick = {
                        showFavorites = true
                        viewModel.loadFavoritePlaylists(forceRefresh = true)
                    }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "Favoriete playlists",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showSleepTimer = true }) {
                        Icon(
                            Icons.Filled.Bedtime,
                            contentDescription = "Slaaptimer",
                            tint = if (state.sleepTimerEndsAtMs != null)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings, 
                            contentDescription = "Instellingen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                PlayerDropdown(state, onSelect = viewModel::selectPlayer)

                state.activeDjStatus?.let { dj ->
                    if (dj.isDjActive) {
                        Row(
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Mic, 
                                contentDescription = null, 
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${dj.activeHostName ?: "AI DJ"} is live",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                state.sleepTimerEndsAtMs?.let { endsAt ->
                    var now by remember { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(endsAt) {
                        while (true) { now = System.currentTimeMillis(); delay(1000) }
                    }
                    val remainingSec = ((endsAt - now).coerceAtLeast(0L) / 1000).toInt()
                    Row(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(16.dp))
                            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Bedtime,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Slaaptimer ${formatDuration(remainingSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { viewModel.setSleepTimer(null) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Slaaptimer annuleren",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                state.errorMessage?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.infoMessage?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(16.dp))

                val queue = state.queue

                if (queue == null || queue.items.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.isLoading) "Laden..." else "Geen actieve wachtrij voor deze speler.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Lokale, sleepbare kopie van "komt hierna"; re-synct alleen als de
                    // echte wachtrijvolgorde verandert (niet bij elke poll).
                    val upcoming = queue.nextItems
                    val upcomingIdentity = upcoming.joinToString(",") { upcomingKey(it) }
                    var workingUpcoming by remember(upcomingIdentity) { mutableStateOf(upcoming) }

                    val reorderState = rememberReorderableLazyColumnState(queueListState) { from, to ->
                        val f = workingUpcoming.indexOfFirst { upcomingKey(it) == from.key }
                        val t = workingUpcoming.indexOfFirst { upcomingKey(it) == to.key }
                        if (f != -1 && t != -1 && f != t) {
                            workingUpcoming = workingUpcoming.toMutableList().apply { add(t, removeAt(f)) }
                        }
                    }

                    // Zodra het slepen stopt: stuur de netto-verschuiving naar de server.
                    // We lezen de lijsten via rememberUpdatedState, anders blijft dit
                    // effect (dat maar één keer start) naar de begin-waarden wijzen en
                    // wordt er na de eerste wachtrij-poll niets meer verstuurd.
                    val latestUpcoming by rememberUpdatedState(upcoming)
                    val latestWorking by rememberUpdatedState(workingUpcoming)
                    LaunchedEffect(reorderState) {
                        snapshotFlow { reorderState.isAnyItemDragging }.collect { dragging ->
                            if (!dragging) {
                                computeSingleMove(latestUpcoming, latestWorking)?.let { (item, delta) ->
                                    viewModel.moveUpcomingItem(item, delta)
                                }
                            }
                        }
                    }

                    LazyColumn(state = queueListState, modifier = Modifier.weight(1f)) {
                        if (queue.pastItems.isNotEmpty()) {
                            item { SectionLabel("Vorige") }
                            itemsIndexed(queue.pastItems) { index, track ->
                                QueueRow(
                                    track,
                                    faded = true,
                                    showDivider = index < queue.pastItems.size - 1,
                                    fallbackTerm = "${track.subtitle} - ${track.title}",
                                    onClick = { trackForOptions = track }
                                )
                            }
                        }

                        item {
                            Spacer(Modifier.height(12.dp))
                            NowPlayingHero(
                                track = queue.currentItem,
                                isPlaying = isPlaying,
                                fallbackTerm = queue.currentItem?.let { t ->
                                    if (t.hasStreamInfo) {
                                        listOfNotNull(
                                            t.streamArtist?.takeIf { s -> s.isNotBlank() },
                                            t.streamTrack?.takeIf { s -> s.isNotBlank() }
                                        ).joinToString(" - ")
                                    } else "${t.subtitle} - ${t.title}"
                                },
                                activePlaylistName = activePlaylistName,
                                elapsedTime = queue.elapsedTime
                            )
                            Spacer(Modifier.height(8.dp))
                            TransportRow(
                                isPlaying = isPlaying,
                                volumeLevel = selectedPlayer?.volumeLevel,
                                shuffleEnabled = queue.shuffleEnabled,
                                onPrevious = { viewModel.sendCommand("players/cmd/previous") },
                                onPlayPause = { viewModel.sendCommand("players/cmd/play_pause") },
                                onNext = { viewModel.sendCommand("players/cmd/next") },
                                onVolumeDown = { viewModel.setVolume("down") },
                                onVolumeUp = { viewModel.setVolume("up") },
                                onShuffle = { viewModel.shuffleQueue() },
                                onClear = { viewModel.clearQueue() }
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        val radioInvolved = queue.currentItem?.isRadio == true ||
                            queue.items.any { it.isRadio }
                        if (radioInvolved && state.radioHistory.isNotEmpty()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SectionLabel("Eerder op deze zender")
                                    IconButton(
                                        onClick = { showSaveRadioHistory = true },
                                        enabled = !state.savingRadioHistoryPlaylist,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.PlaylistAdd,
                                            contentDescription = "Opslaan als afspeellijst",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            itemsIndexed(state.radioHistory) { index, entry ->
                                RadioHistoryRow(
                                    entry,
                                    showDivider = index < state.radioHistory.size - 1,
                                    onClick = { viewModel.playHistoryTrack(entry) }
                                )
                            }
                            item {
                                Text(
                                    "Tik op een nummer om het nu te horen; daarna gaat de radio verder.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }

                        if (workingUpcoming.isNotEmpty()) {
                            item { SectionLabel("Komt hierna") }
                            itemsIndexed(
                                workingUpcoming,
                                key = { _, t -> upcomingKey(t) }
                            ) { index, track ->
                                ReorderableItem(reorderState, key = upcomingKey(track)) { isDragging ->
                                    QueueRow(
                                        track,
                                        faded = false,
                                        showDivider = index < workingUpcoming.size - 1,
                                        fallbackTerm = "${track.subtitle} - ${track.title}",
                                        onClick = { trackForOptions = track },
                                        rowBackground = if (isDragging)
                                            MaterialTheme.colorScheme.surfaceVariant
                                        else Color.Transparent,
                                        dragHandle = {
                                            Icon(
                                                Icons.Filled.DragHandle,
                                                contentDescription = "Sleep om te verplaatsen",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .draggableHandle()
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    trackForOptions?.let { track ->
        PlayOptionsSheet(
            title = track.title,
            onDismiss = { trackForOptions = null },
            onPlayNow = {
                trackForOptions = null
                viewModel.playIndex(track.absoluteIndex)
            },
            onPlayNext = {
                trackForOptions = null
                viewModel.playNext(track)
            }
        )
    }

    if (showAiDj) {
        AiRadioPanel(
            stations = state.aiRadioStations,
            hosts = state.aiRadioHosts,
            sections = state.aiRadioSections,
            isLoading = state.aiRadioLoading,
            activeDj = state.activeDjStatus,
            onDismiss = { showAiDj = false },
            onStartStation = {
                viewModel.startAiRadio(it)
                showAiDj = false
            },
            onStopDj = {
                viewModel.stopAiRadio()
                showAiDj = false
            },
            onCreateStation = { viewModel.createStationTemplate { station -> stationToEdit = station } },
            onEditStation = { station -> stationToEdit = station },
            onCreateHost = { viewModel.createHostTemplate { host -> hostToEdit = host } },
            onEditHost = { host -> hostToEdit = host },
            onCreateSection = { viewModel.createSectionTemplate { sec -> sectionToEdit = sec } },
            onEditSection = { sec -> sectionToEdit = sec }
        )
    }

    stationToEdit?.let { station ->
        StationEditorSheet(
            station = station,
            hosts = state.aiRadioHosts,
            playlists = state.favoritePlaylists,
            players = state.players,
            onDismiss = { stationToEdit = null },
            onSave = { updated -> viewModel.saveStation(updated) { stationToEdit = null } },
            onDelete = {
                viewModel.deleteStation(station.id)
                stationToEdit = null
            }
        )
    }

    hostToEdit?.let { host ->
        HostEditorSheet(
            host = host,
            sections = state.aiRadioSections,
            options = state.aiRadioOptions,
            onDismiss = { hostToEdit = null },
            onSave = { updated -> viewModel.saveHost(updated) { hostToEdit = null } },
            onDelete = {
                viewModel.deleteHost(host.id)
                hostToEdit = null
            }
        )
    }

    sectionToEdit?.let { section ->
        SectionEditorSheet(
            section = section,
            onDismiss = { sectionToEdit = null },
            onSave = { updated -> viewModel.saveSection(updated) { sectionToEdit = null } },
            onDelete = {
                viewModel.deleteSection(section.id)
                sectionToEdit = null
            }
        )
    }

    if (showFavorites && playlistForOptions == null) {
        FavoritesSheet(
            playlists = state.favoritePlaylists,
            isLoading = state.favoritesLoading,
            onDismiss = { showFavorites = false },
            onSelect = { playlistForOptions = it }
        )
    }

    playlistForOptions?.let { playlist ->
        PlayOptionsSheet(
            title = playlist.name,
            onDismiss = { playlistForOptions = null },
            onPlayNow = {
                playlistForOptions = null
                showFavorites = false
                viewModel.playPlaylistNow(playlist)
            },
            onPlayNext = {
                playlistForOptions = null
                showFavorites = false
                viewModel.playPlaylistNext(playlist)
            }
        )
    }

    if (showRadios && radioForOptions == null) {
        RadiosSheet(
            radios = state.favoriteRadios,
            isLoading = state.radiosLoading,
            onDismiss = { showRadios = false },
            onSelect = { radioForOptions = it }
        )
    }

    radioForOptions?.let { radio ->
        PlayOptionsSheet(
            title = radio.name,
            onDismiss = { radioForOptions = null },
            onPlayNow = {
                radioForOptions = null
                showRadios = false
                viewModel.playRadioNow(radio)
            },
            onPlayNext = {
                radioForOptions = null
                showRadios = false
                viewModel.playRadioNext(radio)
            }
        )
    }

    if (showWizard) {
        val filteredPlayers = state.players
            .sortedBy { player ->
                val alias = state.playerAliases[player.id] ?: player.name
                when (alias) {
                    "Woonkamer" -> 1
                    "Buiten" -> 2
                    "Binnen & Buiten" -> 3
                    else -> 4
                }
            }

        MusicWizard(
            players = filteredPlayers,
            playerAliases = state.playerAliases,
            playlists = state.favoritePlaylists,
            isPlaylistsLoading = state.favoritesLoading,
            hosts = state.aiRadioHosts,
            onDismiss = { showWizard = false },
            onConfirm = { player, playlist ->
                showWizard = false
                viewModel.selectPlayer(player.id)
                viewModel.playPlaylistNow(playlist)
            },
            onConfirmWithHost = { player, playlist, host ->
                showWizard = false
                viewModel.selectPlayer(player.id)
                viewModel.startWizardRadioWithHost(player.id, playlist, host)
            }
        )
    }

    if (showTransfer) {
        val transferPlayers = state.players
            .sortedBy { player ->
                val alias = state.playerAliases[player.id] ?: player.name
                when (alias) {
                    "Woonkamer" -> 1
                    "Buiten" -> 2
                    "Binnen & Buiten" -> 3
                    else -> 4
                }
            }

        TransferSheet(
            players = transferPlayers,
            playerAliases = state.playerAliases,
            onDismiss = { showTransfer = false },
            onSelect = { player ->
                showTransfer = false
                viewModel.transferQueue(player.id)
            }
        )
    }

    if (showCast) {
        CastSheet(
            players = state.players,
            playerAliases = state.playerAliases,
            currentPlayerId = state.selectedPlayerId,
            onDismiss = { showCast = false },
            onSelect = { player ->
                showCast = false
                viewModel.transferQueue(player.id)
            }
        )
    }

    if (showSleepTimer) {
        SleepTimerSheet(
            activeEndsAtMs = state.sleepTimerEndsAtMs,
            onDismiss = { showSleepTimer = false },
            onPick = { minutes ->
                viewModel.setSleepTimer(minutes)
                showSleepTimer = false
            }
        )
    }

    // Melding als er geen spelers beschikbaar zijn (specifiek door locatie-beperking)
    if (showLocationWarning && state.serverConfigured && !state.isNearLocation && state.players.isEmpty() && !state.isLoading) {
        AlertDialog(
            onDismissRequest = { showLocationWarning = false },
            title = { Text("Niet in de buurt") },
            text = { 
                val distanceText = state.distanceToHome?.let { 
                    "\n\n(Huidige afstand: ${(it / 1000).asTwoDecimals()} km)"
                } ?: ""
                Text("Je bent niet in de buurt van spelers en kunt daarom geen spelers bedienen. " +
                     "Klik op het Music Assistant icoon in het menu om eerst een speler te openen en in te loggen met je gebruikersnaam." +
                     distanceText)
            },
            confirmButton = {
                TextButton(onClick = { showLocationWarning = false }) {
                    Text("Begrepen")
                }
            }
        )
    }

    if (showSaveRadioHistory) {
        val currentTrack = state.queue?.currentItem
        // Zelfde bron als de titel bovenaan: bij een radiostream met live songinfo
        // staat de echte zendernaam in track.title, niet in activePlaylistName.
        val stationName = (currentTrack?.title?.takeIf { currentTrack.hasStreamInfo && it.isNotBlank() }
            ?: activePlaylistName?.takeIf { it.isNotBlank() }
            ?: "Radio")
        val dateLabel = remember {
            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("nl")).format(java.util.Date())
        }
        var playlistName by remember { mutableStateOf("$stationName – $dateLabel") }
        AlertDialog(
            onDismissRequest = { showSaveRadioHistory = false },
            title = { Text("Opslaan als afspeellijst") },
            text = {
                Column {
                    Text(
                        "Slaat \"Eerder op deze zender\" plus het nummer dat nu speelt " +
                            "(${state.radioHistory.size + 1} nummers) op als nieuwe favoriete playlist " +
                            "in Music Assistant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Naam") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveRadioHistoryAsPlaylist(playlistName)
                        showSaveRadioHistory = false
                    },
                    enabled = playlistName.isNotBlank()
                ) {
                    Text("Opslaan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveRadioHistory = false }) {
                    Text("Annuleren")
                }
            }
        )
    }
}

/**
 * Helper extension to format a Float as a string with two decimal places.
 */
private fun Float.asTwoDecimals(): String {
    return "%.2f".format(this)
}

/**
 * Eén regel in "Eerder op deze zender": een nummer dat net op de radio langskwam.
 */
@Composable
private fun RadioHistoryRow(entry: RadioHistoryEntry, showDivider: Boolean, onClick: () -> Unit) {
    // Radiostreams leveren zelf geen releasejaar; we vullen dat aan via een
    // best-effort iTunes-opzoeking op artiest + titel, met cache per term.
    val searchTerm = itunesSearchTerm(entry.artist, entry.track)
    var year by remember(searchTerm) { mutableStateOf(searchTerm?.let { itunesYearCache[it] }?.takeIf { it != 0 }) }
    LaunchedEffect(searchTerm) {
        if (year == null && searchTerm != null) {
            year = lookupItunesYear(entry.artist, entry.track)
        }
    }
    val titleText = if (year != null) "${entry.track ?: "-"} ($year)" else (entry.track ?: "-")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    titleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = listOfNotNull(
                    entry.artist?.takeIf { it.isNotBlank() },
                    entry.album?.takeIf { it.isNotBlank() }
                ).joinToString("  ·  ")
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.PlayCircleOutline,
                contentDescription = "Dit nummer nu afspelen",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (showDivider) {
            Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                val dashWidth = 6.dp.toPx()
                val gapWidth = 5.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Color(0xFF1C1B19).copy(alpha = 0.25f),
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x + dashWidth, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                    x += dashWidth + gapWidth
                }
            }
        }
    }
}

/**
 * Bottom sheet om een slaaptimer te kiezen (of een lopende timer uit te zetten).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    activeEndsAtMs: Long?,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "SLAAPTIMER",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }
            Text(
                "De muziek pauzeert automatisch na de gekozen tijd.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
            listOf(15, 30, 45, 60, 90).forEach { min ->
                ListItem(
                    headlineContent = { Text("$min minuten") },
                    leadingContent = { Icon(Icons.Filled.Timer, contentDescription = null) },
                    modifier = Modifier.clickable { onPick(min) }
                )
            }
            if (activeEndsAtMs != null) {
                ListItem(
                    headlineContent = { Text("Slaaptimer uitzetten") },
                    leadingContent = { Icon(Icons.Filled.Close, contentDescription = null) },
                    modifier = Modifier.clickable { onPick(null) }
                )
            }
        }
    }
}

/**
 * Bottom sheet to select a player for transferring the current playback queue.
 *
 * @param players List of available players to transfer to.
 * @param onDismiss Callback when the sheet is dismissed.
 * @param onSelect Callback when a player is selected for transfer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(
    players: List<MassPlayer>,
    playerAliases: Map<String, String>,
    onDismiss: () -> Unit,
    onSelect: (MassPlayer) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SyncAlt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "MUZIEK VERHUIZEN",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }
            
            Spacer(Modifier.height(16.dp))
            Text("Waar wil je verder luisteren?", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))

            players.forEach { player ->
                ListItem(
                    headlineContent = { Text(playerAliases[player.id] ?: player.name) },
                    leadingContent = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable { onSelect(player) }
                )
            }
            
            if (players.isEmpty()) {
                Text(
                    "Geen beschikbare spelers gevonden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

/**
 * Bottom sheet om de huidige speler + muziek naar een ander (cast-)apparaat te
 * sturen. Cast-apparaten staan bovenaan met een cast-icoon; de rest eronder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastSheet(
    players: List<MassPlayer>,
    playerAliases: Map<String, String>,
    currentPlayerId: String?,
    onDismiss: () -> Unit,
    onSelect: (MassPlayer) -> Unit
) {
    fun label(p: MassPlayer) = playerAliases[p.id] ?: p.name
    val targets = players
        .filter { it.id != currentPlayerId }
        .sortedWith(compareByDescending<MassPlayer> { it.isCast }.thenBy { label(it).lowercase() })

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "CASTEN",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Speler + muziek overzetten naar:", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))

            targets.forEach { player ->
                ListItem(
                    headlineContent = { Text(label(player)) },
                    supportingContent = if (player.isCast) {
                        { Text("Cast-apparaat", style = MaterialTheme.typography.bodySmall) }
                    } else null,
                    leadingContent = {
                        Icon(
                            if (player.isCast) Icons.Filled.Cast else Icons.Filled.Speaker,
                            contentDescription = null,
                            tint = if (player.isCast) MaterialTheme.colorScheme.primary
                            else LocalContentColor.current
                        )
                    },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable { onSelect(player) }
                )
            }

            if (targets.isEmpty()) {
                Text(
                    "Geen ander apparaat om naar te casten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

/**
 * A multi-step wizard to guide the user through selecting a player and starting a playlist.
 *
 * @param players List of available players.
 * @param playerAliases Map of player IDs to their aliases.
 * @param playlists List of favorite playlists to choose from.
 * @param isPlaylistsLoading Whether the playlists are currently being loaded.
 * @param hosts Existing AI Radio presenters the user can optionally add.
 * @param onDismiss Callback when the wizard is dismissed.
 * @param onConfirm Callback when a player and playlist have been selected (plain playback).
 * @param onConfirmWithHost Callback when a presenter was added: start the playlist as AI Radio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicWizard(
    players: List<MassPlayer>,
    playerAliases: Map<String, String>,
    playlists: List<MassPlaylist>,
    isPlaylistsLoading: Boolean,
    hosts: List<AiRadioHost>,
    onDismiss: () -> Unit,
    onConfirm: (MassPlayer, MassPlaylist) -> Unit,
    onConfirmWithHost: (MassPlayer, MassPlaylist, AiRadioHost) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var selectedPlayer by remember { mutableStateOf<MassPlayer?>(null) }
    var selectedPlaylist by remember { mutableStateOf<MassPlaylist?>(null) }
    var selectedHost by remember { mutableStateOf<AiRadioHost?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when(step) {
                        1 -> "KIES EEN SPELER"
                        2 -> "KIES MUZIEK"
                        3 -> "PRESENTATOR?"
                        4 -> "KIES PRESENTATOR"
                        else -> "BEVESTIGEN"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }
            
            Spacer(Modifier.height(24.dp))

            when (step) {
                1 -> {
                    Text("Op welk apparaat wil je luisteren?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(players) { player ->
                            ListItem(
                                headlineContent = { Text(playerAliases[player.id] ?: player.name) },
                                leadingContent = { Icon(Icons.Filled.Speaker, contentDescription = null) },
                                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    selectedPlayer = player
                                    step = 2
                                }
                            )
                        }
                    }
                }
                2 -> {
                    Text("Wat wil je afspelen?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    if (isPlaylistsLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(playlists) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedPlaylist = playlist
                                            step = 3
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MassImage(
                                        model = playlist.imagePath,
                                        fallbackTerm = playlist.name,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { step = 1 }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Terug naar spelers")
                    }
                }
                3 -> {
                    Text(
                        "Wil je er een presentator bij? Die praat je playlist aan elkaar als AI Radio.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { step = 4 },
                        enabled = hosts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("JA, PRESENTATOR KIEZEN")
                    }
                    OutlinedButton(
                        onClick = {
                            selectedHost = null
                            step = 5
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("NEE, GEWOON DE PLAYLIST")
                    }
                    if (hosts.isEmpty()) {
                        Text(
                            "Geen presentatoren gevonden. Maak er eerst een aan bij AI Radio DJ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    TextButton(onClick = { step = 2 }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Terug naar muziek")
                    }
                }
                4 -> {
                    Text("Welke presentator?", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(hosts) { host ->
                            ListItem(
                                headlineContent = { Text(host.name) },
                                leadingContent = { Icon(Icons.Filled.Mic, contentDescription = null) },
                                trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                                modifier = Modifier.clickable {
                                    selectedHost = host
                                    step = 5
                                }
                            )
                        }
                    }
                    TextButton(onClick = { step = 3 }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Terug")
                    }
                }
                5 -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Samenvatting:", style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.height(8.dp))
                            Text("Muziek: ${selectedPlaylist?.name}", fontWeight = FontWeight.Bold)
                            val playerDisplayName = selectedPlayer?.let { playerAliases[it.id] ?: it.name }
                            Text("Speler: $playerDisplayName", fontWeight = FontWeight.Bold)
                            selectedHost?.let {
                                Text("Presentator: ${it.name}  ·  AI Radio", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            val player = selectedPlayer ?: return@Button
                            val playlist = selectedPlaylist ?: return@Button
                            val host = selectedHost
                            if (host != null) onConfirmWithHost(player, playlist, host)
                            else onConfirm(player, playlist)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedHost != null) "START MET PRESENTATOR" else "START MUZIEK")
                    }

                    OutlinedButton(
                        onClick = {
                            selectedHost = null
                            step = 1
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("Opnieuw beginnen")
                    }
                }
            }
        }
    }
}

/**
 * Dropdown menu for selecting the active player.
 *
 * @param state Current UI state containing players and selection info.
 * @param onSelect Callback when a player ID is selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerDropdown(state: UiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.players.find { it.id == state.selectedPlayerId }
    
    fun formatPlayerName(player: MassPlayer?): String {
        if (player == null) return ""
        return state.playerAliases[player.id] ?: player.name
    }

    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val placeholder = when {
            state.isLoading -> "Spelers laden..."
            !state.isNearLocation && state.players.isEmpty() -> "Geen spelers in de buurt"
            state.players.isEmpty() -> "Geen spelers gevonden"
            else -> "Kies speler"
        }

        // Een plattere variant van de dropdown
        Surface(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp), // Ca 60% van de standaard 56-64dp hoogte
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected?.let { formatPlayerName(it) } ?: placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Filled.ArrowDropDown, 
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Spelers die in de instellingen zijn verborgen, laten we uit de keuzelijst weg
        // (de speler die nu geselecteerd is blijft zichtbaar zodat je kunt wisselen).
        // Volgorde: de speler waarop het meest actueel iets is gestart staat bovenaan; speelt
        // er nergens iets, dan staat de speler met een geladen afspeellijst/wachtrij bovenaan.
        val visiblePlayers = state.players.filter { player ->
            player.id == state.selectedPlayerId ||
            !(state.hiddenPlayerIds.contains(player.id) ||
              state.hiddenPlayerIds.contains(player.name.lowercase().trim()))
        }.sortedWith(
            compareByDescending<MassPlayer> {
                it.playbackState?.lowercase() == "playing" || state.queueSummaries[it.id]?.isPlaying == true
            }
                .thenByDescending { state.queueSummaries[it.id]?.hasItems == true }
                .thenByDescending { state.playerLastPlayingAtMs[it.id] ?: 0L }
                .thenBy { formatPlayerName(it).lowercase() }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            visiblePlayers.forEach { player ->
                DropdownMenuItem(
                    text = { Text(formatPlayerName(player)) },
                    onClick = {
                        expanded = false
                        onSelect(player.id)
                    }
                )
            }
        }
    }
}

/**
 * A simple labeled header for sections in the queue.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

/**
 * A wrapper around Coil's AsyncImage that includes fallback logic to iTunes for missing artwork.
 *
 * @param model The image path or URL to load.
 * @param modifier Modifier for the image container.
 * @param contentScale How to scale the image content.
 * @param fallbackTerm Search term to use for iTunes fallback if [model] is null/empty.
 */
@Composable
private fun MassImage(
    model: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackTerm: String? = null
) {
    val context = LocalContext.current
    var currentModel by remember(model) { mutableStateOf<Any?>(model) }
    var hasAttemptedFallback by remember(model) { mutableStateOf(false) }

    // iTunes Fallback Logica (met cache per zoekterm)
    LaunchedEffect(model, fallbackTerm) {
        // We proberen de fallback alleen als het originele model leeg is
        if (model.isNullOrEmpty() && !fallbackTerm.isNullOrBlank() && !hasAttemptedFallback) {
            hasAttemptedFallback = true

            val cached = itunesArtCache[fallbackTerm]
            if (cached != null) {
                if (cached.isNotEmpty()) currentModel = cached
                return@LaunchedEffect
            }
            try {
                val encodedTerm = java.net.URLEncoder.encode(fallbackTerm, "UTF-8")
                val searchUrl = "https://itunes.apple.com/search?term=$encodedTerm&limit=1"

                val resolved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val response = okhttp3.OkHttpClient().newCall(
                        okhttp3.Request.Builder().url(searchUrl).build()
                    ).execute().body?.string() ?: return@withContext ""

                    val results = org.json.JSONObject(response).optJSONArray("results")
                    val artUrl = if (results != null && results.length() > 0) {
                        results.getJSONObject(0).optString("artworkUrl100")
                    } else ""
                    if (artUrl.isNotEmpty()) artUrl.replace("100x100bb", "600x600bb") else ""
                }

                itunesArtCache[fallbackTerm] = resolved
                if (resolved.isNotEmpty()) currentModel = resolved
            } catch (_: Exception) { }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val imageRequest = remember(currentModel) {
            ImageRequest.Builder(context)
                .data(currentModel)
                .setHeader("User-Agent", "Mozilla/5.0")
                .crossfade(true)
                .listener(
                    onError = { _, _ ->
                        if (!hasAttemptedFallback && !fallbackTerm.isNullOrBlank()) {
                            hasAttemptedFallback = true
                        }
                    }
                )
                .build()
        }

        SubcomposeAsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        ) {
            val state = painter.state
            when (state) {
                is coil.compose.AsyncImagePainter.State.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
                is coil.compose.AsyncImagePainter.State.Error -> {
                    // "YouTube" achtige placeholder met witte achtergrond
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayCircle,
                            contentDescription = null,
                            tint = Color(0xFFFF0000).copy(alpha = 0.8f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                else -> {
                    SubcomposeAsyncImageContent()
                }
            }
        }
    }
}

/**
 * Large display for the currently playing track, including progress bar.
 *
 * @param track The track currently playing.
 * @param isPlaying Current playback status.
 * @param fallbackTerm Fallback string for image search.
 * @param activePlaylistName Name of the active playlist, if any.
 * @param elapsedTime Current playback position in seconds.
 */
@Composable
private fun NowPlayingHero(
    track: QueueTrack?, 
    isPlaying: Boolean, 
    fallbackTerm: String?, 
    activePlaylistName: String?,
    elapsedTime: Int?
) {
    // Bij radio met live songinfo wisselen we de hoes af en toe voor het zenderlogo:
    // ~30 s de albumhoes van het nummer, dan ~5 s het logo van het radiostation.
    val songArt = track?.streamImage?.takeIf { it.isNotBlank() }
    val stationArt = track?.imagePath?.takeIf { it.isNotBlank() }
    val hasSongInfo = track?.hasStreamInfo == true
    // Alterneren zodra we songinfo hebben én een zenderlogo; de songhoes zelf mag
    // ontbreken (dan haalt MassImage 'm via de fallbackTerm bij iTunes op).
    val canAlternateArt = hasSongInfo && stationArt != null && stationArt != songArt

    var showStationArt by remember { mutableStateOf(false) }
    LaunchedEffect(canAlternateArt, track?.streamTrack, track?.streamArtist) {
        showStationArt = false
        if (!canAlternateArt) return@LaunchedEffect
        while (true) {
            delay(30_000)
            showStationArt = true
            delay(5_000)
            showStationArt = false
        }
    }

    val stationArtVisible = canAlternateArt && showStationArt
    // Tijdens het zenderlogo-venster: forceer het logo en zet de iTunes-fallback uit.
    // Anders: songhoes (of null -> MassImage lost 'm op via fallbackTerm).
    val heroArt: String? = when {
        stationArtVisible -> stationArt
        hasSongInfo -> songArt
        else -> songArt ?: stationArt
    }
    val heroFallbackTerm = if (stationArtVisible) null else fallbackTerm

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (track?.isAiRadio == true) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                androidx.compose.animation.Crossfade(
                    targetState = heroArt to heroFallbackTerm,
                    animationSpec = tween(400),
                    label = "heroArtCrossfade"
                ) { (artModel, artFallback) ->
                    MassImage(
                        model = artModel,
                        fallbackTerm = artFallback,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val isAiRadio = track?.isAiRadio == true
            // Radiostream met live metadata: toon de zendernaam als label en
            // artiest + songtitel eronder, net als in Music Assistant.
            val hasStreamInfo = track?.hasStreamInfo == true

            val displayLabel = when {
                isAiRadio -> "AI RADIO LIVE"
                hasStreamInfo -> (track?.title?.takeIf { it.isNotBlank() }
                    ?: activePlaylistName?.takeIf { it.isNotBlank() }
                    ?: "RADIO").uppercase()
                isPlaying && !activePlaylistName.isNullOrBlank() -> activePlaylistName.uppercase()
                else -> "NU SPELEND"
            }
            // Fallback via iTunes wanneer Music Assistant zelf geen jaartal levert:
            // altijd voor radiostreams (die hebben nooit trackmetadata), en voor
            // gewone tracks alleen als de eigen MA-metadata geen jaar bevat.
            val fallbackArtist = if (hasStreamInfo) track?.streamArtist else track?.artist
            val fallbackTitle = if (hasStreamInfo) track?.streamTrack else track?.title
            val fallbackSearchTerm = if (hasStreamInfo || track?.year == null) {
                itunesSearchTerm(fallbackArtist, fallbackTitle)
            } else null
            var fallbackYear by remember(fallbackSearchTerm) {
                mutableStateOf(fallbackSearchTerm?.let { itunesYearCache[it] }?.takeIf { it != 0 })
            }
            LaunchedEffect(fallbackSearchTerm) {
                if (fallbackYear == null && fallbackSearchTerm != null) {
                    fallbackYear = lookupItunesYear(fallbackArtist, fallbackTitle)
                }
            }
            val mainTitle = when {
                hasStreamInfo -> track?.streamTrack ?: track?.title ?: "-"
                else -> track?.title ?: "-"
            }.let { titleText ->
                val year = if (hasStreamInfo) fallbackYear else (track?.year ?: fallbackYear)
                if (year != null) "$titleText ($year)" else titleText
            }
            val secondaryText = when {
                hasStreamInfo -> track?.streamArtist ?: ""
                else -> track?.subtitle ?: ""
            }

            Text(
                text = displayLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (isAiRadio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                mainTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
            Text(
                secondaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Album van het radionummer, indien de stream dat meegeeft.
            val albumText = if (hasStreamInfo) track?.streamAlbum?.takeIf { it.isNotBlank() } else null
            if (albumText != null) {
                Text(
                    albumText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status balkje - verbergen voor AI Radio (duur is vaak 0)
            if (!isAiRadio && elapsedTime != null && track?.durationSeconds != null && track.durationSeconds > 0) {
                Spacer(Modifier.height(6.dp))

                val duration = track.durationSeconds
                // De server levert elapsed_time maar 1x per ~2,5 s. We ankeren op elke
                // nieuwe serverwaarde en tellen er lokaal seconden bij op zolang er speelt,
                // zodat de balk vloeiend loopt i.p.v. te verspringen.
                val anchorRealtimeMs = remember(elapsedTime, isPlaying) { System.currentTimeMillis() }
                var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(elapsedTime, isPlaying, duration) {
                    while (isPlaying) {
                        nowMs = System.currentTimeMillis()
                        delay(500)
                    }
                    nowMs = System.currentTimeMillis()
                }
                val shownElapsed = if (isPlaying) {
                    (elapsedTime + ((nowMs - anchorRealtimeMs) / 1000).toInt()).coerceIn(0, duration)
                } else {
                    elapsedTime.coerceIn(0, duration)
                }
                val animatedProgress by animateFloatAsState(
                    targetValue = (shownElapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f),
                    animationSpec = tween(500, easing = LinearEasing),
                    label = "nowPlayingProgress"
                )

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatDuration(shownElapsed),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                    Text(
                        formatDuration(duration),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * Row of transport controls (Prev, Play/Pause, Next, Shuffle, Volume, Clear).
 * Includes a fun spinning dice animation for the shuffle button.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    volumeLevel: Int?,
    shuffleEnabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onShuffle: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isSpinning by remember { mutableStateOf(false) }
    val diceFaces = listOf("⚀", "⚁", "⚂", "⚃", "⚄", "⚅")
    var currentDiceFace by remember { mutableStateOf(diceFaces.random()) }
    
    val infiniteTransition = rememberInfiniteTransition(label = "diceSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(250, easing = LinearEasing)
        ),
        label = "diceRotation"
    )

    LaunchedEffect(isSpinning) {
        if (isSpinning) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 1000) {
                currentDiceFace = diceFaces.random()
                delay(60.milliseconds)
            }
            isSpinning = false
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClear,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.DeleteSweep, 
                contentDescription = "Wachtrij wissen", 
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
        
        IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Vorige") }
        
        Surface(
            onClick = onPlayPause,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pauzeren" else "Afspelen",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        
        IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, contentDescription = "Volgende") }
        
        IconButton(
            onClick = {
                try {
                    val vibrator = context.getSystemService(android.os.Vibrator::class.java)
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (_: Exception) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                isSpinning = true
                onShuffle()
            },
            modifier = Modifier.size(40.dp)
        ) {
            Text(
                text = currentDiceFace,
                fontSize = 24.sp,
                modifier = Modifier.graphicsLayer(rotationZ = if (isSpinning) rotation else 0f),
                color = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onVolumeDown,
                modifier = Modifier.size(32.dp)
            ) { 
                Icon(
                    Icons.AutoMirrored.Filled.VolumeDown, 
                    contentDescription = "Volume omlaag",
                    modifier = Modifier.size(20.dp)
                ) 
            }
            Text(
                volumeLevel?.let { "$it%" } ?: "-",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            IconButton(
                onClick = onVolumeUp,
                modifier = Modifier.size(32.dp)
            ) { 
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp, 
                    contentDescription = "Volume omhoog",
                    modifier = Modifier.size(20.dp)
                ) 
            }
        }
    }
}

/**
 * A single row representing a track in the playback queue.
 *
 * @param track The track data.
 * @param faded If true, the row is rendered with reduced opacity (e.g. for past items).
 * @param showDivider Whether to show a dashed divider below the row.
 * @param fallbackTerm Fallback for image loading.
 * @param onClick Callback when the row is clicked.
 */
@Composable
private fun QueueRow(
    track: QueueTrack,
    faded: Boolean,
    showDivider: Boolean,
    fallbackTerm: String?,
    onClick: () -> Unit,
    rowBackground: Color = Color.Transparent,
    dragHandle: (@Composable () -> Unit)? = null
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "%02d".format(track.absoluteIndex + 1),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            if (track.isAiRadio) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Mic, 
                        contentDescription = null, 
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            } else {
                MassImage(
                    model = track.imagePath,
                    fallbackTerm = fallbackTerm,
                    modifier = Modifier
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
                        .alpha(if (faded) 0.55f else 1f)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (track.isAiRadio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (faded) 0.55f else 1f)
                )
                Text(
                    track.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (dragHandle != null) {
                dragHandle()
            } else {
                Text(
                    if (track.isAiRadio) "" else formatDuration(track.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDivider) {
            Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                val dashWidth = 6.dp.toPx()
                val gapWidth = 5.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Color(0xFF1C1B19).copy(alpha = 0.25f),
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x + dashWidth, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                    x += dashWidth + gapWidth
                }
            }
        }
    }
}

/**
 * Options sheet for a specific item (Track, Playlist, or Radio).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayOptionsSheet(
    title: String,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit,
    onPlayNext: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            ListItem(
                headlineContent = { Text("Nu afspelen") },
                leadingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPlayNow)
            )
            ListItem(
                headlineContent = { Text("Als volgende afspelen") },
                leadingContent = { Icon(Icons.Filled.QueuePlayNext, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPlayNext)
            )
        }
    }
}

/**
 * Sheet displaying the user's favorite playlists from Music Assistant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoritesSheet(
    playlists: List<MassPlaylist>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (MassPlaylist) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp).heightIn(max = 480.dp)) {
            Text(
                "FAVORIETE PLAYLISTS",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            when {
                isLoading -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                playlists.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Geen favoriete playlists gevonden in Music Assistant.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn {
                        items(playlists) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(playlist) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MassImage(
                                    model = playlist.imagePath,
                                    fallbackTerm = playlist.name,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    playlist.trackCount?.let {
                                        Text(
                                            "$it nummers",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sheet displaying the user's favorite radio stations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadiosSheet(
    radios: List<MassRadio>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (MassRadio) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp).heightIn(max = 480.dp)) {
            Text(
                "FAVORIETE RADIOZENDERS",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            when {
                isLoading -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                radios.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Geen favoriete radiozenders gevonden in Music Assistant.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn {
                        items(radios) { radio ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(radio) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MassImage(
                                    model = radio.imagePath,
                                    fallbackTerm = radio.name,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        radio.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    Icons.Filled.Radio,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiRadioPanel(
    stations: List<AiRadioStation>,
    hosts: List<AiRadioHost>,
    sections: List<AiRadioSection>,
    isLoading: Boolean,
    activeDj: nl.jeroen.massqueue.AiRadioQueueStatus?,
    onDismiss: () -> Unit,
    onStartStation: (AiRadioStation) -> Unit,
    onStopDj: () -> Unit,
    onCreateStation: () -> Unit,
    onEditStation: (AiRadioStation) -> Unit,
    onCreateHost: () -> Unit,
    onEditHost: (AiRadioHost) -> Unit,
    onCreateSection: () -> Unit,
    onEditSection: (AiRadioSection) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Stations", "Presentatoren", "Segmenten")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 32.dp).heightIn(max = 640.dp)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            if (isLoading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else when (selectedTab) {
                0 -> StationsTab(stations, hosts, activeDj, onStartStation, onStopDj, onCreateStation, onEditStation)
                1 -> HostsTab(hosts, sections, onCreateHost, onEditHost)
                2 -> SectionsTab(sections, onCreateSection, onEditSection)
            }
        }
    }
}

@Composable
private fun AiRadioListHeader(title: String, onCreate: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        extra()
        Button(onClick = onCreate) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(4.dp))
            Text("NIEUW")
        }
    }
}

@Composable
private fun StationsTab(
    stations: List<AiRadioStation>,
    hosts: List<AiRadioHost>,
    activeDj: nl.jeroen.massqueue.AiRadioQueueStatus?,
    onStart: (AiRadioStation) -> Unit,
    onStop: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (AiRadioStation) -> Unit
) {
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            AiRadioListHeader("Stations", onCreate) {
                if (activeDj?.isDjActive == true) {
                    OutlinedButton(
                        onClick = onStop,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(4.dp))
                        Text("STOP")
                    }
                }
            }
        }
        if (activeDj?.isDjActive == true) {
            item {
                Text(
                    "DJ actief${activeDj.activeHostName?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
        items(items = stations, key = { it.id }) { station ->
            val hostName = hosts.firstOrNull { it.id == station.hostId }?.name ?: station.hostId ?: "Geen presentator"
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                onClick = { onStart(station) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(station.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            hostName + (if (station.maxDurationMinutes > 0) " · max ${station.maxDurationMinutes} min" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onEdit(station) }) {
                        Icon(Icons.Default.Edit, "Aanpassen", tint = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (stations.isEmpty()) {
            item { Text("Nog geen stations. Maak er één met NIEUW.", Modifier.padding(vertical = 16.dp)) }
        }
    }
}

@Composable
private fun HostsTab(
    hosts: List<AiRadioHost>,
    sections: List<AiRadioSection>,
    onCreate: () -> Unit,
    onEdit: (AiRadioHost) -> Unit
) {
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item { AiRadioListHeader("Presentatoren", onCreate) }
        items(items = hosts, key = { it.id }) { host ->
            val subtitle = buildList {
                if (!host.language.isNullOrBlank()) add(host.language)
                if (!host.ttsEngine.isNullOrBlank()) add(host.ttsEngine)
                add("${host.sectionIds.size} segment${if (host.sectionIds.size == 1) "" else "en"}")
            }.joinToString(" · ")
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                onClick = { onEdit(host) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(host.name, style = MaterialTheme.typography.titleMedium)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (hosts.isEmpty()) {
            item { Text("Nog geen presentatoren. Maak er één met NIEUW.", Modifier.padding(vertical = 16.dp)) }
        }
        if (sections.isNotEmpty() && hosts.isNotEmpty()) {
            item {
                Text(
                    "Tip: segmenten koppel je binnen een presentator.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionsTab(
    sections: List<AiRadioSection>,
    onCreate: () -> Unit,
    onEdit: (AiRadioSection) -> Unit
) {
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
        item { AiRadioListHeader("Segmenten", onCreate) }
        items(items = sections, key = { it.id }) { section ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                onClick = { onEdit(section) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(section.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${section.type} · websearch: ${section.webSearch}" +
                                (if (section.maxChars > 0) " · ${section.maxChars} tekens" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (sections.isEmpty()) {
            item {
                Text(
                    "Geen segmenten gevonden. Als je MA-server 'ai_radio/sections/list' niet ondersteunt blijft dit leeg.",
                    Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Uitklapbare keuzelijst die niet afhangt van de Material3-versie (geen ExposedDropdownMenu). */
@Composable
private fun IdPickerField(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selectedId }?.second ?: "— kies —"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = { onSelect(value); open = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationEditorSheet(
    station: AiRadioStation,
    hosts: List<AiRadioHost>,
    playlists: List<MassPlaylist>,
    players: List<MassPlayer>,
    onSave: (AiRadioStation) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(station.name) }
    var hostId by remember { mutableStateOf(station.hostId ?: "") }
    // Match bestaande bron-playlist op item-id; anders leeg (= ongewijzigd laten).
    var playlistUri by remember {
        mutableStateOf(playlists.firstOrNull { it.itemIdFromUri == station.sourcePlaylistId }?.uri ?: "")
    }
    var defaultPlayerId by remember { mutableStateOf(station.defaultPlayerId ?: "") }
    var maxDuration by remember { mutableIntStateOf(station.maxDurationMinutes) }
    var shuffle by remember { mutableStateOf(station.shuffleSourceTracks) }

    val hostOptions = listOf("" to "Geen presentator") + hosts.map { it.id to it.name }
    val playerOptions = listOf("" to "Volg huidige speler") + players.map { it.id to it.name }
    val playlistOptions = buildList {
        if (station.sourcePlaylistId != null &&
            playlists.none { it.itemIdFromUri == station.sourcePlaylistId }
        ) {
            add("" to "Huidige bron behouden (id ${station.sourcePlaylistId})")
        } else {
            add("" to "— kies playlist —")
        }
        addAll(playlists.map { it.uri to it.name })
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                if (station.id.isBlank()) "NIEUW STATION" else "STATION AANPASSEN",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Naam") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            IdPickerField("Presentator", hostOptions, hostId) { hostId = it }
            IdPickerField("Bron-playlist", playlistOptions, playlistUri) { playlistUri = it }
            IdPickerField("Standaardspeler", playerOptions, defaultPlayerId) { defaultPlayerId = it }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max duur", Modifier.weight(1f))
                IconButton(onClick = { if (maxDuration >= 10) maxDuration -= 10 else maxDuration = 0 }) {
                    Icon(Icons.Default.Remove, null)
                }
                Text(
                    if (maxDuration == 0) "Onbeperkt" else "$maxDuration min",
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { maxDuration += 10 }) { Icon(Icons.Default.Add, null) }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bron-tracks husselen", Modifier.weight(1f))
                Switch(checked = shuffle, onCheckedChange = { shuffle = it })
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val pl = playlists.firstOrNull { it.uri == playlistUri }
                    onSave(
                        station.copy(
                            name = name.trim(),
                            hostId = hostId.ifBlank { null },
                            sourcePlaylistId = pl?.itemIdFromUri ?: station.sourcePlaylistId,
                            sourcePlaylistProvider = pl?.providerFromUri ?: station.sourcePlaylistProvider,
                            defaultPlayerId = defaultPlayerId.ifBlank { null },
                            maxDurationMinutes = maxDuration,
                            shuffleSourceTracks = shuffle
                        )
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("STATION OPSLAAN") }

            if (station.id.isNotBlank()) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("VERWIJDEREN")
                }
            }
        }
    }
}

/** Veelgebruikte taalcodes als de server geen lijst teruggeeft. */
private val FALLBACK_LANGUAGES = listOf(
    "nl", "nl-NL", "nl-BE", "en", "en-US", "en-GB", "de", "de-DE",
    "fr", "fr-FR", "es", "es-ES", "it", "it-IT", "pt", "pt-PT", "sv", "da", "no", "pl"
)

/**
 * Vaste TTS-engines (waarde = MA-provider-id `hass/<entity_id>`, label = weergavenaam).
 * Server-ontdekte engines en een "Aangepast…"-optie worden er in de UI bij gezet.
 */
private val KNOWN_TTS_ENGINES = listOf(
    "hass/tts.elevenlabs_tekst_naar_spraak" to "ElevenLabs Tekst-naar-spraak",
    "hass/tts.google_ai_tts" to "Google AI TTS",
    "hass/tts.home_assistant_cloud" to "Home Assistant Cloud",
    "hass/tts.piper" to "Piper"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditorSheet(
    host: AiRadioHost,
    sections: List<AiRadioSection>,
    options: AiRadioOptions,
    onSave: (AiRadioHost) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(host.name) }
    var instructions by remember { mutableStateOf(host.instructions ?: "") }
    var ttsEngine by remember { mutableStateOf(host.ttsEngine ?: "") }
    var language by remember { mutableStateOf(host.language ?: "") }
    val pickedSections = remember { mutableStateListOf<String>().apply { addAll(host.sectionIds) } }
    var mergeSectionId by remember { mutableStateOf(host.mergeSectionId ?: "") }
    var sectionOrderJson by remember { mutableStateOf(host.sectionOrderJson ?: "") }
    var showAdvanced by remember { mutableStateOf(false) }

    val mergeOptions = listOf("" to "Geen") + sections.map { it.id to it.name }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                if (host.id.isBlank()) "NIEUWE PRESENTATOR" else "PRESENTATOR AANPASSEN",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Naam") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = instructions, onValueChange = { instructions = it },
                label = { Text("Instructies / persoonlijkheid") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            // TTS: dropdown met bekende engines + "Aangepast…" voor een vrij in te typen HA-id.
            val customTts = "__custom"
            val ttsOptions = buildList {
                add("" to "Standaard")
                addAll(KNOWN_TTS_ENGINES)
                options.ttsEngines.forEach { v -> if (none { it.first == v }) add(v to v) }
                host.ttsEngine?.takeIf { it.isNotBlank() }?.let { c -> if (none { it.first == c }) add(c to c) }
                add(customTts to "Aangepast…")
            }
            var ttsIsCustom by remember {
                mutableStateOf(!host.ttsEngine.isNullOrBlank() && KNOWN_TTS_ENGINES.none { it.first == host.ttsEngine } && options.ttsEngines.none { it == host.ttsEngine })
            }
            IdPickerField("TTS-engine", ttsOptions, if (ttsIsCustom) customTts else ttsEngine) { sel ->
                if (sel == customTts) ttsIsCustom = true
                else { ttsIsCustom = false; ttsEngine = sel }
            }
            if (ttsIsCustom) {
                OutlinedTextField(
                    value = ttsEngine, onValueChange = { ttsEngine = it },
                    label = { Text("TTS-engine (Home Assistant entity-id)") },
                    placeholder = { Text("tts.google_translate_nl_nl") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }

            val langValues = (options.languages.ifEmpty { FALLBACK_LANGUAGES } + language)
                .filter { it.isNotBlank() }.distinct()
            val langOptions = listOf("" to "Standaard") + langValues.map { it to it }
            IdPickerField("Taal", langOptions, language) { language = it }

            Spacer(Modifier.height(12.dp))
            Text("Segmenten", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sections.isEmpty()) {
                Text("Nog geen segmenten aangemaakt.", style = MaterialTheme.typography.bodySmall)
            } else {
                sections.forEach { sec ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (pickedSections.contains(sec.id)) pickedSections.remove(sec.id)
                            else pickedSections.add(sec.id)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = pickedSections.contains(sec.id),
                            onCheckedChange = {
                                if (it) pickedSections.add(sec.id) else pickedSections.remove(sec.id)
                            }
                        )
                        Text(sec.name)
                    }
                }
            }

            IdPickerField("Samenvoeg-segment", mergeOptions, mergeSectionId) { mergeSectionId = it }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Geavanceerd: volgorde-regels (JSON)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = sectionOrderJson, onValueChange = { sectionOrderJson = it },
                    label = { Text("section_order") },
                    minLines = 4,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Ongewijzigd laten = precies terugsturen wat de server gaf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onSave(
                        host.copy(
                            name = name.trim(),
                            instructions = instructions.ifBlank { null },
                            ttsEngine = ttsEngine.ifBlank { null },
                            language = language.ifBlank { null },
                            sectionIds = pickedSections.toList(),
                            mergeSectionId = mergeSectionId.ifBlank { null },
                            sectionOrderJson = sectionOrderJson.ifBlank { null }
                        )
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("PRESENTATOR OPSLAAN") }

            if (host.id.isNotBlank()) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("VERWIJDEREN")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionEditorSheet(
    section: AiRadioSection,
    onSave: (AiRadioSection) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(section.name) }
    var type by remember { mutableStateOf(section.type) }
    var webSearch by remember { mutableStateOf(section.webSearch) }
    var prompt by remember { mutableStateOf(section.prompt ?: "") }
    var maxChars by remember { mutableStateOf(if (section.maxChars > 0) section.maxChars.toString() else "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                if (section.id.isBlank()) "NIEUW SEGMENT" else "SEGMENT AANPASSEN",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Naam") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            IdPickerField(
                "Type",
                listOf("ai_text" to "ai_text (tekst genereren)", "ai_meta" to "ai_meta (drafts samenvoegen)"),
                type
            ) { type = it }
            IdPickerField(
                "Web search",
                listOf("disabled" to "disabled", "allow" to "allow", "force" to "force"),
                webSearch
            ) { webSearch = it }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = prompt, onValueChange = { prompt = it },
                label = { Text("Prompt") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = maxChars,
                onValueChange = { new -> maxChars = new.filter { it.isDigit() } },
                label = { Text("Max tekens (leeg = geen limiet)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onSave(
                        section.copy(
                            name = name.trim(),
                            type = type.trim().ifBlank { "ai_text" },
                            webSearch = webSearch.trim().ifBlank { "disabled" },
                            prompt = prompt.ifBlank { null },
                            maxChars = maxChars.toIntOrNull() ?: 0
                        )
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("SEGMENT OPSLAAN") }

            if (section.id.isNotBlank()) {
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("VERWIJDEREN")
                }
            }
        }
    }
}

/**
 * Formats a duration in seconds to a "M:SS" string.
 */
private fun formatDuration(seconds: Int?): String {
    if (seconds == null) return ""
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
