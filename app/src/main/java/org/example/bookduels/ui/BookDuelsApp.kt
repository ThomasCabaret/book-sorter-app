package org.example.bookduels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import org.example.bookduels.core.Book
import org.example.bookduels.core.Duel
import org.example.bookduels.core.EventKind
import org.example.bookduels.core.HistoryEntry
import org.example.bookduels.core.RankingReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Tab(val label: String) { DUELS("Duels"), RANKING("Classement"), LIBRARY("Livres"), HISTORY("Historique") }

@Composable
internal fun BookDuelsApp(
    state: AppUiState,
    onDuelAction: (Duel, String?) -> Unit,
    onUndoLatestDecision: () -> Unit,
    onProtectedAction: (ProtectedAction) -> Unit,
    onExport: () -> Unit,
    onImportCsv: () -> Unit,
    onAddBook: (String, String, String?) -> Unit,
    onToggleBook: (String, Boolean) -> Unit,
    onMessageDismissed: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.DUELS) }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbarHost.showSnackbar(it); onMessageDismissed() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(when (item) { Tab.DUELS -> "↔"; Tab.RANKING -> "≡"; Tab.LIBRARY -> "+"; Tab.HISTORY -> "◷" }) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text("Erreur de catalogue : ${state.error}", Modifier.padding(24.dp))
                tab == Tab.DUELS -> DuelScreen(state, onDuelAction, onUndoLatestDecision, onToggleBook)
                tab == Tab.RANKING -> RankingScreen(state.report!!)
                tab == Tab.LIBRARY -> LibraryScreen(state, onImportCsv, onAddBook, onToggleBook)
                tab == Tab.HISTORY -> HistoryScreen(state, onProtectedAction, onExport)
            }
        }
    }
}

@Composable
private fun DuelScreen(
    state: AppUiState,
    onDuelAction: (Duel, String?) -> Unit,
    onUndoLatestDecision: () -> Unit,
    onToggleBook: (String, Boolean) -> Unit,
) {
    val report = state.report ?: return
    val duel = report.duel
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Quel livre préfères-tu ?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Glisse n'importe quelle jaquette à gauche OU à droite pour choisir ce livre.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (report.isConsistencyCheck) {
            Text(
                "Vérification de cohérence · paire déjà vue, choisie car elle reste incertaine ou en tension.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${report.votes} votes · ${report.jokers} jokers", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(
                onClick = onUndoLatestDecision,
                enabled = !state.busy && state.quickUndoCount > 0,
            ) { Text("↶ Annuler${if (state.quickUndoCount > 0) " (${state.quickUndoCount})" else ""}") }
        }
        if (duel == null) {
            Text("Au moins deux livres actifs sont nécessaires.")
        } else {
            DuelCard(
                book = duel.first,
                revision = state.revision,
                enabled = !state.busy,
                selected = state.selectionFeedback?.chosenBookId == duel.first.id,
                modifier = Modifier.weight(1f),
                onChosen = { onDuelAction(duel, duel.first.id) },
                onPutAside = { onToggleBook(duel.first.id, false) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { onDuelAction(duel, null) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) { Text("Joker / impossible de choisir") }
                HorizontalDivider(Modifier.weight(1f))
            }
            DuelCard(
                book = duel.second,
                revision = state.revision,
                enabled = !state.busy,
                selected = state.selectionFeedback?.chosenBookId == duel.second.id,
                modifier = Modifier.weight(1f),
                onChosen = { onDuelAction(duel, duel.second.id) },
                onPutAside = { onToggleBook(duel.second.id, false) },
            )
        }
    }
}

@Composable
private fun DuelCard(
    book: Book,
    revision: Long,
    enabled: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onChosen: () -> Unit,
    onPutAside: () -> Unit,
) {
    var horizontalDrag by remember(book.id, revision) { mutableFloatStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { 90.dp.toPx() }
    val highlighted = selected || abs(horizontalDrag) >= thresholdPx
    Card(
        modifier = modifier.fillMaxWidth()
            .offset { IntOffset(horizontalDrag.roundToInt(), 0) }
            .pointerInput(book.id, revision, enabled) {
                if (enabled) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            horizontalDrag += dragAmount
                        },
                        onDragEnd = {
                            val choose = abs(horizontalDrag) >= thresholdPx
                            horizontalDrag = 0f
                            if (choose) onChosen()
                        },
                        onDragCancel = { horizontalDrag = 0f },
                    )
                }
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (highlighted) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val cover = book.thumbnailUrl()
                if (cover != null) {
                    SubcomposeAsyncImage(
                        model = cover,
                        contentDescription = "Couverture de ${book.title}",
                        modifier = Modifier.width(108.dp).height(152.dp)
                            .clip(RoundedCornerShape(7.dp)),
                        contentScale = ContentScale.Fit,
                        loading = { CoverPlaceholder("Chargement…", Modifier.fillMaxSize()) },
                        error = { CoverPlaceholder("Jaquette\nindisponible", Modifier.fillMaxSize()) },
                        success = { SubcomposeAsyncImageContent() },
                    )
                } else {
                    CoverPlaceholder("Sans\njaquette", Modifier.size(width = 108.dp, height = 152.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onPutAside, enabled = enabled) {
                        Text("Mettre de côté")
                    }
                    if (selected) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "✓ Sélectionné",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Button(onClick = onChosen, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Je préfère ce livre")
            }
        }
    }
}

@Composable
private fun CoverPlaceholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▤", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun RankingScreen(report: RankingReport) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("Classement provisoire", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Ranked Pairs (Condorcet), avec Bradley–Terry pour départager les rangs non résolus.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            StatLine("Duels tranchés", report.votes.toString())
            StatLine("Jokers", report.jokers.toString())
            StatLine("Livres évalués", "${report.involvedBooks} / ${report.books.size}")
            StatLine("Paires comparées", "${report.observedPairs} / ${report.possiblePairs}")
            StatLine("Composantes du graphe", report.connectedComponents.toString())
            StatLine("Arcs écartés (cycles)", report.cycleEdgesSkipped.toString())
            Spacer(Modifier.height(6.dp))
            Text("Séparation des scores BT (heuristique)", style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(
                progress = { report.adjacentStability.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
            Text(
                "${String.format(Locale.FRANCE, "%.0f", report.adjacentStability * 100)} / 100. " +
                    "Estimation incertaine des séparations de scores ; ce n'est ni une garantie sur les rangs ni une probabilité calibrée.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                report.directCondorcetWinner?.let { "Vainqueur Condorcet observé : ${it.title}" }
                    ?: "Pas de vainqueur Condorcet strict démontré sur toutes les paires.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(report.books, key = { _, item -> item.book.id }) { index, item ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", modifier = Modifier.width(38.dp), style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f)) {
                        Text(item.book.title, fontWeight = FontWeight.SemiBold)
                        Text(item.book.author, style = MaterialTheme.typography.bodySmall)
                        Text("${item.votes} comparaisons · score ${String.format(Locale.US, "%.2f", item.score)}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HistoryScreen(
    state: AppUiState,
    onProtectedAction: (ProtectedAction) -> Unit,
    onExport: () -> Unit,
) {
    var selectedSeq by remember { mutableStateOf<Long?>(null) }
    var showRollback by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    val selection = selectedSeq?.let { selected -> state.history.firstOrNull { it.event.seq == selected } }
    val toInvalidate = selection?.let { entry ->
        state.history.count { it.active && it.event.seq >= entry.event.seq }
    } ?: 0
    val books = state.catalog.associateBy { it.id }

    if (showRollback && selection != null) {
        AlertDialog(
            onDismissRequest = { showRollback = false },
            title = { Text("Neutraliser $toInvalidate votes / jokers ?") },
            text = { Text("Depuis l'événement #${selection.event.seq}, inclus. Le journal original reste intact. Une validation par biométrie ou code du téléphone sera nécessaire.") },
            confirmButton = {
                Button(onClick = {
                    showRollback = false
                    onProtectedAction(ProtectedAction.Rollback(selection.event.seq, state.revision))
                }) { Text("Confirmer avec le téléphone") }
            },
            dismissButton = { TextButton(onClick = { showRollback = false }) { Text("Annuler") } },
        )
    }
    if (showRestore) {
        AlertDialog(
            onDismissRequest = { showRestore = false },
            title = { Text("Restaurer la dernière annulation ?") },
            text = { Text("${state.restorableCount} votes / jokers seront réactivés, sans supprimer le journal. Authentification requise.") },
            confirmButton = {
                Button(onClick = {
                    showRestore = false
                    onProtectedAction(ProtectedAction.Restore(state.revision))
                }) { Text("Confirmer avec le téléphone") }
            },
            dismissButton = { TextButton(onClick = { showRestore = false }) { Text("Annuler") } },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("Historique des décisions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Les événements sont horodatés, conservés dans leur ordre d'origine et jamais effacés par une annulation.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onExport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Exporter catalogue + historique (.tar.gz)")
            }
            if (state.restorableCount > 0) {
                OutlinedButton(onClick = { showRestore = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Restaurer une annulation (${state.restorableCount})")
                }
            }
            Button(
                onClick = { showRollback = true },
                enabled = !state.busy && selection != null && toInvalidate > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Neutraliser depuis la sélection ($toInvalidate)") }
            Spacer(Modifier.height(12.dp))
        }
        items(state.history, key = { it.event.seq }) { item ->
            val event = item.event
            val first = books[event.firstBookId]?.title ?: event.firstBookId ?: "?"
            val second = books[event.secondBookId]?.title ?: event.secondBookId ?: "?"
            val chosen = books[event.chosenBookId]?.title ?: event.chosenBookId
            val summary = when (event.kind) {
                EventKind.VOTE -> "Préférence : $chosen"
                EventKind.JOKER -> "Joker"
                else -> "Événement technique"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selectedSeq == event.seq,
                    onClick = { selectedSeq = event.seq },
                )
                Column(Modifier.weight(1f)) {
                    Text("#${event.seq}", style = MaterialTheme.typography.labelSmall)
                    Text("$first / $second", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary + if (!item.active) " (neutralisé)" else "", style = MaterialTheme.typography.bodySmall)
                    Text(formatDate(event.timestampMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun formatDate(timestamp: Long): String =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))
