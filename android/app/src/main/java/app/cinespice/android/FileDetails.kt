package app.cinespice.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

@Composable internal fun FileDetails(activity: MainActivity, model: LibraryModel, video: Video) {
    Dialog(onDismissRequest = { activity.details = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row {
                    TextButton(onClick = { activity.details = null }) { Icon(Icons.Default.ArrowBack, null); Text("Back") }
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ArtworkImage(model, video, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Text(video.name, style = MaterialTheme.typography.headlineSmall)
                    Text(videoDetails(video), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(video.mime, style = MaterialTheme.typography.bodySmall)
                    if (video.caption.isNotBlank()) Text(video.caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Favorites", "Watchlist").forEach { list ->
                            val saved = SavedStore.id(video) in if (list == "Favorites") model.favorites else model.watchlist
                            OutlinedButton(onClick = { model.toggle(video, list) }, modifier = Modifier.weight(1f)) { Text(if (saved) "In $list" else "Add to $list") }
                        }
                    }
                    CatalogueLookup(activity, video.name)
                }
            }
        }
    }
}

private data class CatalogueMatch(val id: String, val label: String, val description: String)
@Composable private fun CatalogueLookup(activity: MainActivity, filename: String) {
    var query by remember(filename) { mutableStateOf(filename.substringBeforeLast('.', filename).replace('.', ' ').replace('_', ' ')) }
    var matches by remember { mutableStateOf<List<CatalogueMatch>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Text("Discover more", style = MaterialTheme.typography.titleMedium)
    Text("Look up a title on Wikidata for a description. Suggested matches may refer to a different title.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(query, { query = it }, label = { Text("Movie or show title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = {
        val text = query.trim().take(200)
        loading = true; matches = emptyList(); message = ""
        scope.launch {
            try {
                matches = withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(text, "UTF-8")
                    val connection = URI("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=5&search=$encoded").toURL().openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 15000; connection.readTimeout = 15000
                        connection.setRequestProperty("User-Agent", "Cinespice/1.2 (title lookup; https://github.com/masalaoats007/Cinespice)")
                        check(connection.responseCode == 200)
                        val bytes = connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                check(output.size() + count <= 1_000_000)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val results = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("search")
                        (0 until results.length()).map { index -> val entry = results.getJSONObject(index); CatalogueMatch(entry.getString("id"), entry.getString("label"), entry.optString("description")) }.filter { it.id.matches(Regex("Q[0-9]+")) }
                    } finally { connection.disconnect() }
                }
                if (matches.isEmpty()) message = "No match found. Try a shorter title; file details remain available above."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Title info is unavailable. Please try again later." }
            finally { loading = false }
        }
    }, enabled = !loading && query.isNotBlank()) { Text(if (loading) "Looking up…" else "Find title info") }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    matches.forEach { match ->
        TextButton(onClick = { activity.link("https://www.wikidata.org/wiki/${match.id}") }) { Text(match.label) }
        if (match.description.isNotBlank()) Text(match.description)
    }
    TextButton(onClick = { activity.link("https://www.wikidata.org/wiki/Wikidata:Licensing") }) { Text("Data: Wikidata · CC0") }
}
