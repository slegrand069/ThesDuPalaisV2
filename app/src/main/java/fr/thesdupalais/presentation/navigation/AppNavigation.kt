package fr.thesdupalais.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import fr.thesdupalais.AppContainer
import fr.thesdupalais.presentation.settings.SettingsViewModel
import fr.thesdupalais.presentation.settings.SettingsViewModelFactory

private enum class MainDestination(val route: String, val label: String, val glyph: String) {
    CATALOGUE("catalogue", "Catalogue", "▦"),
    KNOWLEDGE("savoirs", "Savoirs", "▤"),
    SETTINGS("parametres", "Paramètres", "⚙"),
}

@Composable
fun AppNavigation(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MainDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == destination.route
                    } == true
                    NavigationBarItem(
                        modifier = Modifier.testTag("bottom-tab-${destination.route}"),
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.glyph, style = MaterialTheme.typography.titleMedium) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.CATALOGUE.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(MainDestination.CATALOGUE.route) { CatalogueScreen() }
            composable(MainDestination.KNOWLEDGE.route) { KnowledgeScreen() }
            composable(MainDestination.SETTINGS.route) {
                val settingsViewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModelFactory(container.preferences),
                )
                SettingsScreen(settingsViewModel)
            }
        }
    }
}

@Composable
private fun CatalogueScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Text(
            text = "ThésDuPalais",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text("Ma collection de thés", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(36.dp))
        EmptyState(
            title = "Votre catalogue se prépare",
            message = "Vos thés apparaîtront ici.",
        )
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
private fun SettingsScreen(viewModel: SettingsViewModel) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        ScreenTitle("Paramètres", "Personnalisez votre collection de thés")
        Spacer(Modifier.height(24.dp))
        Text("Préférences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        PreferenceCard("Grammage estimé par tasse", formatGrams(preferences.gramsPerCupMilli))
        Spacer(Modifier.height(10.dp))
        PreferenceCard("Seuil de stock faible", formatGrams(preferences.lowStockThresholdMilli))
        Spacer(Modifier.height(24.dp))
        Text("Référentiels", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Couleurs, statuts, pays, régions, arômes, moments, badges et contenants",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun EmptyState(title: String, message: String) {
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
