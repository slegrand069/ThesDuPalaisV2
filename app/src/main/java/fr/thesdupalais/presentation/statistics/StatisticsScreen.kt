package fr.thesdupalais.presentation.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    onBackToSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBackToSettings, modifier = Modifier.size(56.dp).testTag("statistics-back")) {
                Text("‹", style = MaterialTheme.typography.headlineMedium)
            }
            Text("Statistiques", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        when (val current = state) {
            StatisticsUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Chargement des statistiques…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is StatisticsUiState.Content -> StatisticsContent(current.snapshot, viewModel::selectPeriod)
        }
    }
}

@Composable
private fun StatisticsContent(snapshot: StatisticsSnapshot, onPeriodSelected: (StatisticsPeriod) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ma collection en un coup d’œil", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text("ⓘ  Données calculées à partir de votre collection", Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        SummaryGrid(snapshot)
        TastingColorCard(snapshot, onPeriodSelected)
        StockByColorCard(snapshot.stockByColor)
        StockByCountryCard(snapshot.stockByCountry)
        StockByBadgeCard(snapshot.stockByBadge)
        PurchaseHelpCard(snapshot)
        TopFiveCard(snapshot)
        MonthlyTastingsCard(snapshot)
        Text("Statistiques issues des données enregistrées dans l’application.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun SummaryGrid(snapshot: StatisticsSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Références actives", snapshot.activeReferenceCount.toString(), "fiches de thé", Modifier.weight(1f))
            SummaryTile("Stock total", formatGrams(snapshot.knownStockMilliGrams), unknownStockCaption(snapshot.unknownStockReferenceCount), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("Dégustations", snapshot.tastingEventCount.toString(), "événements · ${snapshot.period.label}", Modifier.weight(1f))
            SummaryTile("Stocks faibles", snapshot.lowStockCount.toString(), "sous 30 g", Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryTile(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.height(95.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TastingColorCard(snapshot: StatisticsSnapshot, onPeriodSelected: (StatisticsPeriod) -> Unit) {
    StatisticsCard("Dégustations par couleur", "Nombre d’événements enregistrés · pas de tasses") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatisticsPeriod.entries.forEach { period ->
                val selected = period == snapshot.period
                Surface(
                    onClick = { onPeriodSelected(period) },
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.testTag("statistics-period-${period.name}"),
                ) {
                    Text(period.label, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Text("${snapshot.tastingEventCount} dégustations sur ${snapshot.period.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (snapshot.tastingsByColor.isEmpty()) EmptyChartMessage("Aucune dégustation sur cette période.") else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PieChart(snapshot.tastingsByColor, Modifier.size(124.dp))
                Legend(snapshot.tastingsByColor, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StockByColorCard(values: List<StatisticsBucket>) {
    StatisticsCard("Stock par couleur", "Répartition instantanée · en grammes") {
        if (values.isEmpty()) EmptyChartMessage("Aucun stock renseigné à répartir.") else HorizontalBars(values)
    }
}

@Composable
private fun StockByCountryCard(values: List<StatisticsBucket>) {
    StatisticsCard("Stock par pays d’origine", "Pays uniquement · une origine multiple compte dans chaque pays; la somme peut dépasser le stock total") {
        if (values.isEmpty()) EmptyChartMessage("Aucun stock renseigné à répartir.") else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PieChart(values, Modifier.size(124.dp))
                Legend(values, Modifier.weight(1f), valueFormatter = ::formatGrams)
            }
        }
    }
}

@Composable
private fun StockByBadgeCard(values: List<StatisticsBucket>) {
    StatisticsCard("Stock par badge", "Un thé peut être compté dans chacun de ses badges.") {
        Text("Les catégories se chevauchent : leur somme peut dépasser le stock total.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (values.isEmpty()) EmptyChartMessage("Aucun stock renseigné à répartir.") else HorizontalBars(values, labelWidth = 102.dp)
    }
}

@Composable
private fun PurchaseHelpCard(snapshot: StatisticsSnapshot) {
    StatisticsCard("Aide à l’achat", "Seuil indicatif : moins de 30 g · stock actuel") {
        Text("Pensez aussi aux thés dégustés pendant la période sélectionnée.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (snapshot.purchaseSuggestions.isEmpty()) EmptyChartMessage("Aucun thé actif avec un stock connu inférieur à 30 g.") else {
            snapshot.purchaseSuggestions.take(8).forEachIndexed { index, tea ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .55f))
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tea.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val eventLabel = if (tea.tastingCount == 1) "1 dégustation" else "${tea.tastingCount} dégustations"
                        val stock = "${formatGrams(tea.stockMilliGrams)} · $eventLabel sur la période"
                        Text(stock, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopFiveCard(snapshot: StatisticsSnapshot) {
    StatisticsCard("Les plus dégustés", "Top 5 · sur ${snapshot.period.label} · nombre d’événements") {
        if (snapshot.topFive.isEmpty()) EmptyChartMessage("Aucune dégustation sur cette période.") else snapshot.topFive.forEachIndexed { index, item ->
            if (index > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .55f))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                }
                Text(item.label, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.value.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MonthlyTastingsCard(snapshot: StatisticsSnapshot) {
    StatisticsCard("Évolution des dégustations", "Événements par mois · 6 derniers mois") {
        MonthlyBarChart(snapshot.monthlyTastings)
        Text("Total des 6 mois affichés : ${snapshot.monthlyTastings.sumOf { it.count }} dégustations", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatisticsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun PieChart(values: List<StatisticsBucket>, modifier: Modifier = Modifier) {
    val positive = values.filter { it.value > 0L }
    Canvas(modifier.aspectRatio(1f).testTag("statistics-pie-chart").semantics {
        contentDescription = "Diagramme circulaire : " + values.filter { it.value > 0L }.joinToString { "${it.label}, ${it.value}" }
    }) {
        val total = positive.sumOf { it.value }.toFloat()
        if (total > 0f) {
            var start = -90f
            positive.forEachIndexed { index, item ->
                val sweep = item.value / total * 360f
                drawArc(statisticsColor(item.colorKey, index), start, sweep, true)
                start += sweep
            }
        }
    }
}

@Composable
private fun Legend(values: List<StatisticsBucket>, modifier: Modifier = Modifier, valueFormatter: (Long) -> String = Long::toString) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.filter { it.value > 0 }.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).background(statisticsColor(item.colorKey, index), CircleShape))
                Text(item.label, Modifier.weight(1f).padding(start = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(valueFormatter(item.value), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun HorizontalBars(values: List<StatisticsBucket>, labelWidth: androidx.compose.ui.unit.Dp = 82.dp) {
    val maximum = max(1L, values.maxOfOrNull { it.value } ?: 0L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, item ->
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, Modifier.width(labelWidth), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
                    Box(Modifier.fillMaxWidth((item.value / maximum).coerceIn(0f, 1f)).fillMaxSize().background(statisticsColor(item.colorKey, index), RoundedCornerShape(8.dp)))
                }
                Text(formatGrams(item.value), Modifier.width(70.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MonthlyBarChart(months: List<MonthlyTastingCount>) {
    val maximum = max(1, months.maxOfOrNull { it.count } ?: 0)
    Row(Modifier.fillMaxWidth().height(126.dp).semantics {
        contentDescription = "Dégustations par mois : " + months.joinToString { "${it.label}, ${it.count}" }
    }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        months.forEach { item ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(item.count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                if (item.count > 0) {
                    Box(Modifier.width(30.dp).height((68f * item.count / maximum).coerceAtLeast(2f).dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                } else {
                    Spacer(Modifier.width(30.dp).height(1.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyChartMessage(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatGrams(milliGrams: Long): String {
    val grams = milliGrams / 1_000.0
    val number = NumberFormat.getNumberInstance(Locale.FRANCE).apply { maximumFractionDigits = if (grams % 1.0 == 0.0) 0 else 2 }
    return if (grams >= 1_000.0) "${number.format(grams / 1_000.0)} kg" else "${number.format(grams)} g"
}

private fun unknownStockCaption(count: Int): String = if (count == 0) "stock connu" else "$count stock(s) non renseigné(s)"

private fun statisticsColor(key: String?, index: Int): Color = when (key?.uppercase(Locale.ROOT)) {
    "GREEN" -> Color(0xFF315F49)
    "BLACK" -> Color(0xFF705038)
    "OOLONG" -> Color(0xFFC27B42)
    "WHITE" -> Color(0xFFD4C3A4)
    "PU_ERH", "PU-ERH" -> Color(0xFFA66F54)
    "YELLOW" -> Color(0xFFD9B640)
    else -> listOf(Color(0xFF8C9B82), Color(0xFF76918D), Color(0xFF937C9C), Color(0xFFBE8A91), Color(0xFF7A8EA8))[index % 5]
}
