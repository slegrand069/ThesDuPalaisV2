package fr.thesdupalais.presentation.articles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.thesdupalais.data.database.ArticleEntity
import fr.thesdupalais.data.importer.ArticleImportResult
import java.util.UUID

@Composable
fun ArticlesScreen(
    viewModel: ArticlesViewModel,
    onArticleSelected: (UUID) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deletionState by viewModel.deletion.state.collectAsStateWithLifecycle()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Savoirs", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("screen-title-savoirs"))
            Text("Conseils et notes autour du thé", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { filePicker.launch(arrayOf("application/json")) },
                enabled = !state.isImporting,
                modifier = Modifier.fillMaxWidth().testTag("articles-import"),
            ) {
                Text(if (state.isImporting) "Import en cours…" else "Importer des articles")
            }
        }
        state.importResult?.let { result ->
            item(key = "import-result") { ArticleImportReport(result, onDismiss = viewModel::dismissImportResult) }
        }
        deletionState.errorMessage?.let { message ->
            item(key = "delete-error") {
                Card(modifier = Modifier.fillMaxWidth().testTag("article-delete-error")) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel.deletion::clearError, modifier = Modifier.align(Alignment.End)) {
                            Text("Fermer")
                        }
                    }
                }
            }
        }
        if (state.isLoading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.articles.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.fillMaxWidth().testTag("articles-empty")) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Aucun article pour le moment", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Importez un fichier JSON pour consulter vos articles de savoirs.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(state.articles, key = { it.id }) { article ->
                ArticleListItem(
                    article = article,
                    deleting = deletionState.deletingId != null,
                    onClick = { onArticleSelected(article.id) },
                    onRequestDelete = viewModel.deletion::request,
                )
            }
        }
    }

    deletionState.pendingArticle?.let { article ->
        AlertDialog(
            onDismissRequest = { if (deletionState.deletingId == null) viewModel.deletion.cancel() },
            title = { Text("Supprimer l'article ?") },
            text = { Text("Supprimer définitivement « ${article.title} » ? Cette action est irréversible.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmArticleDeletion,
                    enabled = deletionState.deletingId == null,
                    modifier = Modifier.testTag("article-delete-confirm"),
                ) {
                    Text(if (deletionState.deletingId == article.id) "Suppression…" else "Supprimer",
                        color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel.deletion::cancel,
                    enabled = deletionState.deletingId == null,
                    modifier = Modifier.testTag("article-delete-cancel"),
                ) { Text("Annuler") }
            },
            modifier = Modifier.testTag("article-delete-dialog"),
        )
    }
}

@Composable
private fun ArticleListItem(
    article: ArticleEntity,
    deleting: Boolean,
    onClick: () -> Unit,
    onRequestDelete: (ArticleEntity) -> Unit,
) {
    var menuExpanded by remember(article.id) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().testTag("article-row-${article.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).clickable(role = Role.Button, onClick = onClick)
                    .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp)
                    .testTag("article-open-${article.id}"),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(article.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(article.category, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    enabled = !deleting,
                    modifier = Modifier.testTag("article-actions-${article.id}")
                        .semantics { contentDescription = "Actions pour ${article.title}" },
                ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Supprimer") },
                        onClick = {
                            menuExpanded = false
                            onRequestDelete(article)
                        },
                        enabled = !deleting,
                        modifier = Modifier.testTag("article-delete-${article.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleImportReport(result: ArticleImportResult, onDismiss: () -> Unit) {
    val outcome = result.outcome()
    val heading = when (outcome) {
        ArticleImportOutcome.COMPLETE -> "Import terminé"
        ArticleImportOutcome.WITH_REJECTIONS -> "Import terminé avec des rejets"
        ArticleImportOutcome.INTERRUPTED -> "Import interrompu"
        ArticleImportOutcome.PARTIAL -> "Import partiel — interrompu"
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("articles-import-report")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall,
                color = if (result.globalError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("Ajoutés : ${result.added} · Mis à jour : ${result.updated} · Rejetés : ${result.rejected}",
                style = MaterialTheme.typography.bodyMedium)
            result.globalError?.let { error ->
                Text("Erreur globale (${error.jsonPath}) : ${error.reason}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            result.rejections.forEach { rejection ->
                Text("Article ${rejection.index + 1} (${rejection.jsonPath}) : ${rejection.reason}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Fermer le bilan") }
        }
    }
}

@Composable
fun ArticleDetailScreen(
    state: ArticleDetailState,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("article-detail-back")) { Text("‹  Savoirs") }
        when (state) {
            ArticleDetailState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ArticleDetailState.Unavailable -> Text("Cet article n'est plus disponible.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ArticleDetailState.Content -> {
                Text(state.article.title, style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("article-detail-title"))
                Spacer(Modifier.height(4.dp))
                Text(state.article.category, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                ArticleMarkdownContent(state.article.markdown)
            }
        }
    }
}

@Composable
private fun ArticleMarkdownContent(markdown: String) {
    val blocks = remember(markdown) { ArticleMarkdownRenderer.render(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("article-markdown-content")) {
        blocks.forEachIndexed { index, block ->
            val textStyle = when (block.kind) {
                ArticleMarkdownBlockKind.HEADING_1 -> MaterialTheme.typography.headlineSmall
                ArticleMarkdownBlockKind.HEADING_2 -> MaterialTheme.typography.titleLarge
                ArticleMarkdownBlockKind.HEADING_3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyLarge
            }
            val weight = if (block.kind in setOf(
                    ArticleMarkdownBlockKind.HEADING_1,
                    ArticleMarkdownBlockKind.HEADING_2,
                    ArticleMarkdownBlockKind.HEADING_3,
                )) FontWeight.SemiBold else null
            if (block.marker == null) {
                Text(block.text, style = textStyle, fontWeight = weight, modifier = Modifier.testTag("article-markdown-block-$index"))
            } else {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().testTag("article-markdown-block-$index")) {
                    Text(block.marker, style = textStyle, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(30.dp))
                    Text(block.text, style = textStyle, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
