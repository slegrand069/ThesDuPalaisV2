package fr.thesdupalais.presentation.catalogue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

@Composable
fun CatalogueScreen(viewModel: CatalogueViewModel, onTeaSelected: (UUID) -> Unit, onAddTea: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CatalogueContent(state, onTeaSelected, onAddTea)
}

@Composable
internal fun CatalogueContent(state: CatalogueUiState, onTeaSelected: (UUID) -> Unit = {}, onAddTea: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = "ThésDuPalais",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text("Ma collection de thés", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        CatalogueSearchControls()
        Spacer(Modifier.height(16.dp))

        when (state) {
            CatalogueUiState.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("catalogue-loading"))
            CatalogueUiState.Empty -> Box(Modifier.weight(1f).fillMaxWidth()) {
                CatalogueEmptyState(Modifier.align(androidx.compose.ui.Alignment.Center))
                AddTeaButton(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp), onAddTea)
            }
            is CatalogueUiState.Content -> Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("catalogue-list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.teas, key = { it.id.toString() }) { tea ->
                        CatalogueTeaRow(tea) { onTeaSelected(tea.id) }
                    }
                }
                AddTeaButton(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp), onAddTea)
            }
        }
    }
}

@Composable
private fun CatalogueSearchControls() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(40.dp)
            .semantics { disabled() }
            .testTag("catalogue-search-disabled"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            SearchGlyph(Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Demander à Madjo de vous aider à choisir un thé",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            FilterGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
        }
    }
}

@Composable
private fun SearchGlyph(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        drawCircle(tint, size.minDimension * .3f, Offset(size.width * .42f, size.height * .42f), style = Stroke(1.8.dp.toPx()))
        drawLine(tint, Offset(size.width * .64f, size.height * .64f), Offset(size.width * .9f, size.height * .9f), 1.8.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun FilterGlyph(modifier: Modifier, tint: Color) {
    val background = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val ys = listOf(size.height * .25f, size.height * .5f, size.height * .75f)
        ys.forEachIndexed { index, y ->
            drawLine(tint, Offset(size.width * .08f, y), Offset(size.width * .92f, y), stroke, cap = StrokeCap.Round)
            val knobX = when (index) { 0 -> .38f; 1 -> .68f; else -> .48f }
            drawCircle(background, size.height * .13f, Offset(size.width * knobX, y))
            drawCircle(tint, size.height * .13f, Offset(size.width * knobX, y), style = Stroke(stroke))
        }
    }
}

@Composable
private fun AddTeaButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp).testTag("catalogue-add"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
private fun CatalogueEmptyState(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("catalogue-empty-state"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Votre collection est vide", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Aucun thé n'est enregistré dans votre collection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CatalogueTeaRow(tea: CatalogueTea, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("catalogue-tea-${tea.id}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 88.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TeaCategoryPlaceholder(tea.categoryKey)
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = tea.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(tea.category)
                        if (tea.origins.isNotEmpty()) append(" - ${tea.origins.joinToString(" · ")}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tea.tasteRating?.let { rating ->
                    Text(
                        text = "★ ${formatTasteRating(rating)}/10",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

private fun formatTasteRating(rating: Double): String =
    if (rating % 1.0 == 0.0) rating.toInt().toString() else rating.toString().replace('.', ',')

@Composable
private fun TeaCategoryPlaceholder(categoryKey: String?) {
    val (background, illustration) = when (categoryKey?.uppercase()) {
        "GREEN" -> Color(0xFFD1E5C9) to Color(0xFF405245)
        "BLACK" -> Color(0xFF405245) to Color(0xFFE5EDE5)
        "WHITE" -> Color(0xFFF5EDD4) to Color(0xFFC7A166)
        "OOLONG" -> Color(0xFFC7A166) to Color(0xFFF5EDD4)
        "YELLOW" -> Color(0xFFE8C96B) to Color(0xFF6B5730)
        "PU_ERH", "PU-ERH", "PU’ERH", "PU'ERH" -> Color(0xFF574033) to Color(0xFFF5EDD4)
        else -> Color(0xFFD6DBC7) to Color(0xFF66715C)
    }
    Surface(
        modifier = Modifier.size(68.dp).testTag("catalogue-placeholder-${categoryKey ?: "generic"}"),
        shape = RoundedCornerShape(8.dp),
        color = background,
    ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            Canvas(Modifier.size(36.dp)) {
                val cupWidth = size.width * .68f
                val cupHeight = size.height * .38f
                val cupLeft = size.width * .12f
                val cupTop = size.height * .42f
                drawRoundRect(
                    color = illustration,
                    topLeft = Offset(cupLeft, cupTop),
                    size = Size(cupWidth, cupHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawArc(
                    color = illustration,
                    startAngle = -70f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(size.width * .68f, cupTop + cupHeight * .12f),
                    size = Size(size.width * .28f, cupHeight * .7f),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawLine(
                    color = illustration,
                    start = Offset(size.width * .16f, size.height * .88f),
                    end = Offset(size.width * .82f, size.height * .88f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawOval(
                    color = illustration,
                    topLeft = Offset(size.width * .4f, size.height * .06f),
                    size = Size(size.width * .2f, size.height * .28f),
                )
            }
        }
    }
}
