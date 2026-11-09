package fr.thesdupalais.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.thesdupalais.AppContainer
import fr.thesdupalais.domain.model.ReferenceKind
import fr.thesdupalais.presentation.references.ReferenceFamily
import fr.thesdupalais.presentation.references.ReferenceListScreen
import fr.thesdupalais.presentation.references.ReferenceListViewModel
import fr.thesdupalais.presentation.references.ReferenceListViewModelFactory
import fr.thesdupalais.presentation.references.referenceFamilies
import fr.thesdupalais.presentation.settings.SettingsViewModel
import fr.thesdupalais.presentation.settings.SettingsViewModelFactory
import fr.thesdupalais.presentation.catalogue.CatalogueScreen
import fr.thesdupalais.presentation.catalogue.CatalogueViewModel
import fr.thesdupalais.presentation.catalogue.CatalogueViewModelFactory
import fr.thesdupalais.presentation.detail.TeaDetailContent
import fr.thesdupalais.presentation.detail.TeaDetailScreen
import fr.thesdupalais.presentation.detail.TeaDetailUiState
import fr.thesdupalais.presentation.detail.TeaDetailViewModel
import fr.thesdupalais.presentation.detail.TeaDetailViewModelFactory
import java.util.UUID

private const val TEA_DETAIL_ROUTE = "tea/{teaId}"
private const val REFERENCE_LIST_ROUTE = "references/{kind}"

private enum class MainDestination(val route: String, val label: String, val icon: String) {
    CATALOGUE("catalogue", "Catalogue", "catalogue"),
    KNOWLEDGE("savoirs", "Savoirs", "knowledge"),
    SETTINGS("parametres", "Paramètres", "settings"),
}

@Composable
fun AppNavigation(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentDestination?.route != TEA_DETAIL_ROUTE) {
            Surface(
                // This custom Figma bar has no built-in window insets unlike Material NavigationBar.
                // Include the system navigation area in its measured height so Scaffold places it above it.
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .testTag("main-bottom-bar"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(modifier = Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                    MainDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true ||
                            (destination == MainDestination.SETTINGS && currentDestination?.route?.startsWith("references/") == true)
                        Column(
                            modifier = Modifier.weight(1f).fillMaxSize()
                                .clickable {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                                .testTag("bottom-tab-${destination.route}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            BottomNavigationIcon(destination.icon, selected)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.CATALOGUE.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(MainDestination.CATALOGUE.route) {
                val catalogueViewModel: CatalogueViewModel = viewModel(
                    factory = CatalogueViewModelFactory(container.database.teaDao()),
                )
                CatalogueScreen(catalogueViewModel) { teaId ->
                    navController.navigate("tea/$teaId")
                }
            }
            composable(MainDestination.KNOWLEDGE.route) { KnowledgeScreen() }
            composable(MainDestination.SETTINGS.route) {
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModelFactory(container.preferences),
                )
                SettingsScreen(settingsViewModel) { kind ->
                    navController.navigate("references/${kind.name}")
                }
            }
            composable(
                route = REFERENCE_LIST_ROUTE,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) { referenceEntry ->
                val kind = referenceEntry.arguments?.getString("kind")
                    ?.let { value -> runCatching { ReferenceKind.valueOf(value) }.getOrNull() }
                if (kind == null) {
                    InvalidReferenceScreen(onBack = { navController.popBackStack() })
                } else {
                    val referenceViewModel: ReferenceListViewModel = viewModel(
                        key = "reference-list-${kind.name}",
                        factory = ReferenceListViewModelFactory(container.referenceRepository, kind),
                    )
                    ReferenceListScreen(referenceViewModel) { navController.popBackStack() }
                }
            }
            composable(
                route = TEA_DETAIL_ROUTE,
                arguments = listOf(navArgument("teaId") { type = NavType.StringType }),
            ) { detailEntry ->
                val teaId = detailEntry.arguments?.getString("teaId")?.let { rawId ->
                    runCatching { UUID.fromString(rawId) }.getOrNull()
                }
                val onBack = {
                    if (!navController.popBackStack()) navController.navigate(MainDestination.CATALOGUE.route)
                }
                if (teaId == null) {
                    TeaDetailContent(TeaDetailUiState.Unavailable, onBack)
                } else {
                    val detailViewModel: TeaDetailViewModel = viewModel(
                        key = "tea-detail-$teaId",
                        factory = TeaDetailViewModelFactory(
                            teaId,
                            container.database.teaDao(),
                            container.database.tastingDao(),
                        ),
                    )
                    TeaDetailScreen(detailViewModel, onBack)
                }
            }
        }
    }
}

@Composable
private fun BottomNavigationIcon(kind: String, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp).testTag("bottom-icon-$kind")) {
        val stroke = 1.5.dp.toPx()
        when (kind) {
            "catalogue" -> {
                val gap = size.width * .12f
                val cell = (size.width - gap * 2) / 3
                repeat(3) { row -> repeat(3) { column ->
                    drawRect(color, Offset(column * (cell + gap), row * (cell + gap)), Size(cell, cell), style = Stroke(stroke))
                } }
            }
            "knowledge" -> {
                drawRect(color, Offset(size.width * .18f, size.height * .12f), Size(size.width * .64f, size.height * .76f), style = Stroke(stroke))
                repeat(3) { row ->
                    val y = size.height * (.34f + row * .19f)
                    drawLine(color, Offset(size.width * .3f, y), Offset(size.width * .7f, y), stroke, cap = StrokeCap.Round)
                }
            }
            else -> {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(color, radius = size.width * .3f, center = center, style = Stroke(stroke))
                drawCircle(color, radius = size.width * .09f, center = center, style = Stroke(stroke))
                repeat(8) { index ->
                    val angle = Math.toRadians(index * 45.0)
                    val inner = size.width * .36f
                    val outer = size.width * .47f
                    drawLine(
                        color,
                        Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner),
                        Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer),
                        stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun KnowledgeScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ScreenTitle("Savoirs", "Conseils et notes autour du thé")
        Spacer(Modifier.height(32.dp))
        EmptyState(
            title = "Aucun article pour le moment",
            message = "Vos articles de savoirs apparaîtront ici.",
        )
    }
}

@Composable
private fun SettingsScreen(viewModel: SettingsViewModel, onOpenReference: (ReferenceKind) -> Unit) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ScreenTitle("Paramètres", "Personnalisez votre collection de thés")
        Spacer(Modifier.height(24.dp))
        Text("Référentiels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        referenceFamilies.chunked(2).forEach { rowFamilies ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowFamilies.forEach { family ->
                    ReferenceFamilyCard(
                        family = family,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenReference(family.kind) },
                    )
                }
                if (rowFamilies.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Préférences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        PreferenceCard("Grammage estimé par tasse", formatGrams(preferences.gramsPerCupMilli))
        Spacer(Modifier.height(10.dp))
        PreferenceCard("Seuil de stock faible", formatGrams(preferences.lowStockThresholdMilli))
    }
}

@Composable
private fun ReferenceFamilyCard(
    family: ReferenceFamily,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(54.dp).testTag("settings-reference-${family.kind.name}"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                family.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun InvalidReferenceScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Référentiel indisponible", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("Cette famille de références n’existe pas.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("Retour", modifier = Modifier.clickable(onClick = onBack), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Text(
        text = title,
        modifier = Modifier.testTag("screen-title-${title.lowercase()}"),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EmptyState(title: String, message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreferenceCard(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun formatGrams(milliGrams: Long): String {
    val grams = milliGrams / 1_000.0
    return if (milliGrams % 1_000L == 0L) "${milliGrams / 1_000L} g" else "$grams g"
}
