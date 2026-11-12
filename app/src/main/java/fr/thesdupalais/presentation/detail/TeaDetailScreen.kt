package fr.thesdupalais.presentation.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

@Composable
fun TeaDetailScreen(viewModel: TeaDetailViewModel, onBack: () -> Unit, onEdit: (UUID) -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TeaDetailContent(state, onBack, onEdit)
}

@Composable
internal fun TeaDetailContent(state: TeaDetailUiState, onBack: () -> Unit, onEdit: (UUID) -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DetailHeader(
            onBack = onBack,
            onEdit = { (state as? TeaDetailUiState.Content)?.let { onEdit(it.tea.id) } },
            enabled = state is TeaDetailUiState.Content,
        )
        when (state) {
            TeaDetailUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag("tea-detail-loading"))
            }
            TeaDetailUiState.Unavailable -> Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailCard(Modifier.testTag("tea-detail-unavailable")) {
                    Text("Ce thé n’est pas disponible dans le catalogue.", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            is TeaDetailUiState.Content -> TeaDetailBody(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DetailHeader(onBack: () -> Unit, onEdit: () -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            modifier = Modifier.size(36.dp).testTag("tea-detail-back"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("‹", color = MaterialTheme.colorScheme.primary, fontSize = 26.sp, lineHeight = 26.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Fiche du thé",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            onClick = onEdit,
            enabled = enabled,
            modifier = Modifier.width(76.dp).height(30.dp).testTag("tea-detail-edit"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "Modifier",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TeaDetailBody(state: TeaDetailUiState.Content, modifier: Modifier = Modifier) {
    val tea = state.tea
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TeaHeroPlaceholder(tea.category)
        DetailCard(Modifier.testTag("tea-detail-identity")) {
            SectionLabel("IDENTITÉ")
            Text(tea.name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                text = buildString {
                    append(tea.category)
                    if (tea.origins.isNotEmpty()) append("  ·  ${tea.origins.joinToString("  ·  ")}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val personalAttributes = listOfNotNull(
                tea.personalStatus?.let { "Statut : $it" },
                tea.container?.let { "Contenant : $it" },
            )
            if (personalAttributes.isNotEmpty()) {
                Text(
                    personalAttributes.joinToString("  ·  "),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        DetailCard(Modifier.testTag("tea-detail-stock")) {
            SectionLabel("STOCK")
            Text(
                text = state.tea.stockMilliGrams?.let(::formatStockQuantity) ?: "Non renseigné",
                color = if (state.tea.stockMilliGrams == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        DetailCard(Modifier.testTag("tea-detail-tasting-notes")) {
            SectionLabel("NOTES DE DÉGUSTATION")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RatingCard("Goût", tea.tasteRating, Modifier.weight(1f))
                RatingCard("Parfum", tea.olfactoryRating, Modifier.weight(1f))
            }
            tea.personalNotes?.let { note ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailSubsectionLabel("Notes personnelles")
                    Text(note, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (tea.brewTemperatureCelsius != null || tea.infusionDurationSeconds != null) {
            DetailCard(Modifier.testTag("tea-detail-preparation")) {
                SectionLabel("PRÉPARATION CONSEILLÉE")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tea.brewTemperatureCelsius?.let { value ->
                        PreparationValue("Température", "$value °C", Modifier.weight(1f))
                    }
                    tea.infusionDurationSeconds?.let { value ->
                        PreparationValue("Durée", formatInfusionDuration(value), Modifier.weight(1f))
                    }
                }
            }
        }

        if (tea.description != null || tea.technicalInformation != null) {
            DetailCard(Modifier.testTag("tea-detail-additional-information")) {
                SectionLabel("INFORMATIONS COMPLÉMENTAIRES")
                tea.description?.let { LabeledDetail("Description", it) }
                tea.technicalInformation?.let { LabeledDetail("Informations techniques", it) }
            }
        }

        if (tea.aromas.isNotEmpty() || tea.aromaticProfiles.isNotEmpty() || tea.moments.isNotEmpty() || tea.badges.isNotEmpty()) {
            DetailCard(Modifier.testTag("tea-detail-aromas-moments")) {
                SectionLabel("ARÔMES & MOMENTS")
                if (tea.aromas.isNotEmpty()) LabeledDetail("Arômes", tea.aromas.joinToString("  ·  "))
                if (tea.aromaticProfiles.isNotEmpty()) {
                    LabeledDetail("Profils aromatiques", tea.aromaticProfiles.joinToString("  ·  "))
                }
                if (tea.moments.isNotEmpty()) LabeledDetail("Moments", tea.moments.joinToString("  ·  "))
                if (tea.badges.isNotEmpty()) {
                    DetailSubsectionLabel("Badges")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tea.badges.forEach { BadgeLabel(it) }
                    }
                }
            }
        }

        DetailCard(Modifier.testTag("tea-detail-tastings")) {
            SectionLabel("DÉGUSTATIONS")
            if (state.tastings.isEmpty()) {
                Text("Aucune dégustation enregistrée.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                state.tastings.forEachIndexed { index, tasting ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            formatTastingDateTime(tasting.occurredAtEpochMillis),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        tasting.consumedMilliGrams?.let { amount ->
                            Text("Quantité consommée · ${formatConsumedQuantity(amount)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeaHeroPlaceholder(category: String) {
    val leaf = MaterialTheme.colorScheme.primary
    val tea = MaterialTheme.colorScheme.secondary
    Surface(
        modifier = Modifier.fillMaxWidth().height(168.dp).testTag("tea-detail-placeholder"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                rotate(28f, pivot = Offset(w * .21f, h * .56f)) {
                    drawOval(leaf.copy(alpha = .95f), Offset(w * .11f, h * .34f), Size(w * .22f, h * .18f))
                }
                rotate(-24f, pivot = Offset(w * .32f, h * .56f)) {
                    drawOval(leaf.copy(alpha = .68f), Offset(w * .23f, h * .34f), Size(w * .18f, h * .16f))
                }
                drawLine(leaf.copy(alpha = .7f), Offset(w * .14f, h * .62f), Offset(w * .40f, h * .40f), h * .012f, cap = StrokeCap.Round)
                drawOval(Color.White, Offset(w * .58f, h * .33f), Size(w * .30f, h * .45f))
                drawOval(tea, Offset(w * .63f, h * .36f), Size(w * .20f, h * .20f))
            }
            Text(
                "PLACEHOLDER · ${category.uppercase()}",
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 10.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DetailCard(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DetailSubsectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RatingCard(label: String, rating: Double?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                rating?.let { "${formatDetailRating(it)} / 10" } ?: "— / 10",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PreparationValue(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LabeledDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DetailSubsectionLabel(label)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BadgeLabel(label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFE8F0E0),
        modifier = Modifier.border(1.dp, Color(0xFFBACCAd), RoundedCornerShape(12.dp)),
    ) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFF38543B), style = MaterialTheme.typography.labelSmall)
    }
}
