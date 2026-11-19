package fr.thesdupalais.presentation.photos

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TeaPhotoImage(path: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(if (path.startsWith("/")) path else File(context.filesDir, path).path) }.getOrNull() }
    }
    val click = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "Photo du thé", modifier = click, contentScale = contentScale)
}

@Composable
fun PhotoStrip(paths: List<String>, modifier: Modifier = Modifier, onPhotoClick: (Int) -> Unit) {
    if (paths.isEmpty()) return
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(paths) { index, path ->
            TeaPhotoImage(path, Modifier.size(width = 112.dp, height = 84.dp), onClick = { onPhotoClick(index) })
        }
    }
}

@Composable
fun PhotoViewer(paths: List<String>, initialIndex: Int, onDismiss: () -> Unit) {
    if (paths.isEmpty()) return
    var index by remember(paths, initialIndex) { mutableIntStateOf(initialIndex.coerceIn(paths.indices)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        title = { Text("Photo ${index + 1} sur ${paths.size}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TeaPhotoImage(paths[index], Modifier.fillMaxWidth().heightIn(max = 480.dp), ContentScale.Fit)
                if (paths.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { index = (index - 1 + paths.size) % paths.size }) { Text("‹ Précédente") }
                    TextButton(onClick = { index = (index + 1) % paths.size }) { Text("Suivante ›") }
                }
            }
        },
    )
}
