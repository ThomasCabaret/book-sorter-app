package org.example.bookduels.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class LibraryFilter { ACTIVE, SET_ASIDE, ALL }

@Composable
internal fun LibraryScreen(
    state: AppUiState,
    onImportCsv: () -> Unit,
    onAddBook: (String, String, String?) -> Unit,
    onToggleBook: (String, Boolean) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.ACTIVE) }
    val filtered = remember(state.catalog, query, filter) {
        state.catalog.filter { book ->
            val visible = when (filter) {
                LibraryFilter.ACTIVE -> book.enabled
                LibraryFilter.SET_ASIDE -> !book.enabled
                LibraryFilter.ALL -> true
            }
            visible && (book.title.contains(query.trim(), ignoreCase = true) ||
                book.author.contains(query.trim(), ignoreCase = true))
        }
    }
    if (showAdd) {
        var title by remember { mutableStateOf("") }
        var author by remember { mutableStateOf("") }
        var isbn by remember { mutableStateOf("") }
        val validIsbn = isbn.isBlank() || org.example.bookduels.core.BabelioCsv.validIsbn13(isbn.trim())
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Ajouter un livre lu") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Titre") }, singleLine = true)
                    OutlinedTextField(author, { author = it }, label = { Text("Auteur") }, singleLine = true)
                    OutlinedTextField(isbn, { isbn = it }, label = { Text("ISBN-13 (facultatif)") }, singleLine = true,
                        isError = !validIsbn)
                    Text("Si une œuvre existe déjà, elle n'est pas dupliquée. L'ISBN sert à retrouver sa jaquette.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.busy && title.isNotBlank() && author.isNotBlank() && validIsbn,
                    onClick = { onAddBook(title, author, isbn.trim().ifBlank { null }); showAdd = false }
                ) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${state.catalog.count { it.enabled }} actifs · ${state.catalog.count { !it.enabled }} mis de côté · ${state.catalog.size} conservés",
                style = MaterialTheme.typography.bodySmall)
            Text("Un import ajoute les livres « Lu » sans enlever les autres ni toucher aux votes. Les notes sont ignorées.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onImportCsv, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Importer un export Babelio CSV")
            }
            OutlinedButton(onClick = { showAdd = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Ajouter un livre manuellement")
            }
            OutlinedTextField(query, { query = it }, label = { Text("Filtrer titre ou auteur") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(filter == LibraryFilter.ACTIVE, { filter = LibraryFilter.ACTIVE }, { Text("Actifs") })
                FilterChip(filter == LibraryFilter.SET_ASIDE, { filter = LibraryFilter.SET_ASIDE }, { Text("Mis de côté") })
                FilterChip(filter == LibraryFilter.ALL, { filter = LibraryFilter.ALL }, { Text("Tous") })
            }
            Text("${filtered.size} livres affichés. Une mise de côté conserve tous les duels.",
                style = MaterialTheme.typography.labelSmall)
        }
        items(filtered, key = { it.id }) { book ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(book.author, style = MaterialTheme.typography.bodySmall)
                    book.isbn13?.let { Text("ISBN $it", style = MaterialTheme.typography.labelSmall) }
                }
                TextButton(onClick = { onToggleBook(book.id, !book.enabled) }, enabled = !state.busy) {
                    Text(if (book.enabled) "Mettre de côté" else "Réintégrer")
                }
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
