package fr.thesdupalais.presentation.catalogue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

@Composable
fun CatalogueScreen(viewModel: CatalogueViewModel, onTeaSelected: (UUID) -> Unit, onAddTea: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CatalogueContent(
        state = state,
        onTeaSelected = onTeaSelected,
        onAddTea = onAddTea,
        onQueryChange = viewModel::setQuery,
        onSubmitQuery = viewModel::submitQuery,
        onDismissMadjo = viewModel::dismissMadjo,
        onCategoriesChange = viewModel::setCategories,
        onCountriesChange = viewModel::setCountries,
        onStatusesChange = viewModel::setStatuses,
        onProfilesChange = viewModel::setAromaticProfiles,
        onBadgesChange = viewModel::setBadges,
        onContainersChange = viewModel::setContainers,
        onMomentsChange = viewModel::setMoments,
        onTasteRatingChange = viewModel::setTasteRating,
        onResetFilters = viewModel::resetFilters,
        onResetAll = viewModel::resetAll,
    )
}

@Composable
internal fun CatalogueContent(
    state: CatalogueUiState,
    onTeaSelected: (UUID) -> Unit = {},
    onAddTea: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onSubmitQuery: () -> Unit = {},
    onDismissMadjo: () -> Unit = {},
    onCategoriesChange: (Set<UUID>) -> Unit = {},
    onCountriesChange: (Set<UUID>) -> Unit = {},
    onStatusesChange: (Set<UUID>, Boolean) -> Unit = { _, _ -> },
    onProfilesChange: (Set<UUID>) -> Unit = {},
    onBadgesChange: (Set<UUID>) -> Unit = {},
    onContainersChange: (Set<UUID>) -> Unit = {},
    onMomentsChange: (Set<UUID>) -> Unit = {},
    onTasteRatingChange: (TasteRatingFilter) -> Unit = {},
    onResetFilters: () -> Unit = {},
    onResetAll: () -> Unit = {},
) {
    val criteria = when (state) { is CatalogueUiState.Content -> state.criteria; is CatalogueUiState.Empty -> state.criteria; else -> CatalogueCriteria() }
    val options = when (state) { is CatalogueUiState.Content -> state.options; is CatalogueUiState.Empty -> state.options; else -> CatalogueFilterOptions() }
    var showFilters by remember { mutableStateOf(false) }
    val madjoRequest = when (state) { is CatalogueUiState.Content -> state.madjoRequest; is CatalogueUiState.Empty -> state.madjoRequest; else -> false }
    val madjoDetected = containsMadjoInvocation(criteria.query)
    val madjoRequestText = when (state) { is CatalogueUiState.Content -> state.madjoRequestText; is CatalogueUiState.Empty -> state.madjoRequestText; else -> null }
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
        CatalogueSearchControls(criteria, onQueryChange, onSubmitQuery, { showFilters = true }, onResetAll, madjoRequest)
        if (criteria.hasFilters) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("catalogue-active-filters"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                criteria.activeFilterLabels(options).forEach { label ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when (state) {
            CatalogueUiState.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("catalogue-loading"))
            is CatalogueUiState.Empty -> Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!state.madjoRequest) CatalogueEmptyState(Modifier.align(androidx.compose.ui.Alignment.Center))
                AddTeaButton(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp), onAddTea)
            }
            is CatalogueUiState.Content -> Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.madjoRequest -> Unit
                    state.noResults && !madjoDetected -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Aucun thé ne correspond à ces critères.", modifier = Modifier.testTag("catalogue-no-results"), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onResetAll) { Text("Réinitialiser") }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("catalogue-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.teas, key = { it.id.toString() }) { tea -> CatalogueTeaRow(tea) { onTeaSelected(tea.id) } }
                    }
                }
                AddTeaButton(Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp), onAddTea)
            }
        }
    }
    if (showFilters) CatalogueFilterDialog(
        options, criteria, { showFilters = false }, onCategoriesChange, onCountriesChange, onStatusesChange,
        onProfilesChange, onBadgesChange, onContainersChange, onMomentsChange, onTasteRatingChange, onResetFilters,
    )
    if (madjoRequest && madjoRequestText != null) {
        MadjoDialog(
            initialRequest = madjoRequestText,
            onDismiss = onDismissMadjo,
        )
    }
}

@Composable
private fun MadjoDialog(initialRequest: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("M", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Madjo")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(initialRequest, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Text("Madjo prépare ses questions…", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        modifier = Modifier.testTag("madjo-dialog"),
    )
}

@Composable
private fun CatalogueSearchControls(criteria: CatalogueCriteria, onQueryChange: (String) -> Unit, onSubmitQuery: () -> Unit, showFilters: () -> Unit, resetAll: () -> Unit, madjoRequest: Boolean) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SearchGlyph(Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = criteria.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f).testTag("catalogue-search"),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmitQuery() }),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { inner ->
                        Box {
                            if (criteria.query.isEmpty()) Text("Rechercher un thé ou demander à Madjo…", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                            inner()
                        }
                    },
                )
            }
        }
        Surface(onClick = showFilters, enabled = !madjoRequest, modifier = Modifier.size(46.dp).testTag("catalogue-filter-button"), shape = RoundedCornerShape(12.dp),
            color = if (criteria.hasFilters) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                FilterGlyph(Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                if (criteria.hasFilters) Text("•", modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(end = 5.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        if (criteria.hasAnyCriteria) TextButton(onClick = resetAll, modifier = Modifier.testTag("catalogue-reset")) { Text("Effacer") }
    }
}

@Composable
private fun CatalogueFilterDialog(options: CatalogueFilterOptions, criteria: CatalogueCriteria, onDismiss: () -> Unit,
    onCategories: (Set<UUID>) -> Unit,
    onCountries: (Set<UUID>) -> Unit,
    onStatuses: (Set<UUID>, Boolean) -> Unit,
    onProfiles: (Set<UUID>) -> Unit,
    onBadges: (Set<UUID>) -> Unit,
    onContainers: (Set<UUID>) -> Unit,
    onMoments: (Set<UUID>) -> Unit,
    onTasteRating: (TasteRatingFilter) -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Filtres du catalogue") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            FilterSection("Catégorie (couleur)", options.categories, criteria.categoryIds, onCategories)
            FilterSection("Pays", options.countries, criteria.countryIds, onCountries)
            Text("Statut", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (options.hasUnspecifiedStatus) FilterChoice("Non renseigné", criteria.includeUnspecifiedStatus) { onStatuses(criteria.statusIds, !criteria.includeUnspecifiedStatus) }
            options.statuses.forEach { ref -> FilterChoice(ref.label, ref.id in criteria.statusIds) { onStatuses(criteria.statusIds.toggle(ref.id), criteria.includeUnspecifiedStatus) } }
            FilterSection("Profil aromatique", options.aromaticProfiles, criteria.aromaticProfileIds, onProfiles)
            FilterSection("Badge", options.badges, criteria.badgeIds, onBadges)
            FilterSection("Contenant", options.containers, criteria.containerIds, onContainers)
            FilterSection("Moments", options.moments, criteria.momentIds, onMoments)
            Text("Note gustative", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            TasteRatingFilter.entries.forEach { filter ->
                Row(Modifier.fillMaxWidth().height(44.dp).selectable(
                    selected = criteria.tasteRating == filter,
                    role = Role.RadioButton,
                    onClick = { onTasteRating(filter) },
                ).testTag("catalogue-note-option-${filter.name}"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = criteria.tasteRating == filter, onClick = null)
                    Text(filter.label, modifier = Modifier.weight(1f))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } }, dismissButton = { TextButton(onClick = onReset) { Text("Réinitialiser") } })
}

private fun CatalogueCriteria.activeFilterLabels(options: CatalogueFilterOptions): List<String> = buildList {
    fun addSelected(prefix: String, values: List<fr.thesdupalais.data.database.ReferenceEntity>, selected: Set<UUID>) {
        if (selected.isNotEmpty()) add("$prefix : ${values.filter { it.id in selected }.joinToString(", ") { it.label }}")
    }
    addSelected("Catégorie", options.categories, categoryIds)
    addSelected("Pays", options.countries, countryIds)
    addSelected("Statut", options.statuses, statusIds)
    if (includeUnspecifiedStatus) add("Statut : Non renseigné")
    addSelected("Profil", options.aromaticProfiles, aromaticProfileIds)
    addSelected("Badge", options.badges, badgeIds)
    addSelected("Contenant", options.containers, containerIds)
    addSelected("Moment", options.moments, momentIds)
    if (tasteRating != TasteRatingFilter.ALL) add("Note : ${tasteRating.label}")
}

@Composable
private fun FilterSection(title: String, values: List<fr.thesdupalais.data.database.ReferenceEntity>, selected: Set<UUID>, onChange: (Set<UUID>) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    values.forEach { ref -> FilterChoice(ref.label, ref.id in selected) { onChange(selected.toggle(ref.id)) } }
}

@Composable
private fun FilterChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() }).testTag("catalogue-filter-option-$label"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun Set<UUID>.toggle(id: UUID): Set<UUID> = if (id in this) this - id else this + id

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
