package fr.thesdupalais

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import fr.thesdupalais.presentation.navigation.AppNavigation
import fr.thesdupalais.presentation.theme.ThesDuPalaisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ThesDuPalaisApplication).container
        setContent {
            ThesDuPalaisTheme {
                AppNavigation(container)
            }
        }
    }
}
