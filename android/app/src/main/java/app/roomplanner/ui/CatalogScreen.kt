package app.roomplanner.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.roomplanner.api.ApiException
import app.roomplanner.api.Item
import app.roomplanner.api.ItemKinds
import app.roomplanner.api.RoomPlannerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The item catalog: furniture and wall decor with real dimensions, shared by all rooms. */
@Composable
fun CatalogScreen(api: RoomPlannerApi, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<Item>() }
    var status by remember { mutableStateOf<String?>("Loading…") }
    // Showing the list unless an item is being edited or created.
    var editing by remember { mutableStateOf<Item?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun reload() = scope.launch {
        status = try {
            val loaded = api.listItems()
            items.clear()
            items += loaded
            if (loaded.isEmpty()) "No items yet. Add the things you're thinking of buying." else null
        } catch (e: Exception) {
            "Couldn't load items: ${e.message}"
        }
    }
    LaunchedEffect(Unit) { reload() }

    if (creating || editing != null) {
        ItemEditor(
            api = api,
            existing = editing,
            onDone = {
                creating = false
                editing = null
                reload()
            },
            modifier = modifier,
        )
        return
    }

    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Item catalog", style = MaterialTheme.typography.headlineSmall)
        }
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("Add item") }
        status?.let { Text(it) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth().clickable { editing = item }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(if (item.kind == ItemKinds.WALL_DECOR) "Wall decor" else "Furniture")
                                append(" · ")
                                append(ItemForm.dimensions(item))
                                item.price?.let { append(" · $it") }
                                if (item.imageUrl != null) append(" · has image")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemEditor(
    api: RoomPlannerApi,
    existing: Item?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDone)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(existing?.let(ItemForm::from) ?: ItemForm()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var pickedJpeg by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(existing?.imageUrl) {
        existing?.imageUrl?.let { url -> preview = runCatching { api.fetchBitmap(url, 800) }.getOrNull() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (bmp, jpeg) = withContext(Dispatchers.IO) { importImageAsJpeg(context, uri) }
                preview = bmp
                pickedJpeg = jpeg
            } catch (e: Exception) {
                error = "Couldn't use that image: ${e.message}"
            }
        }
    }

    fun save() {
        val fields = when (val r = form.validate()) {
            is ItemForm.Result.Invalid -> {
                error = r.message
                return
            }
            is ItemForm.Result.Ok -> r.fields
        }
        busy = true
        error = null
        scope.launch {
            try {
                val saved = if (existing == null) api.createItem(fields) else api.updateItem(existing.id, fields)
                pickedJpeg?.let { api.uploadItemImage(saved.id, it) }
                onDone()
            } catch (e: Exception) {
                error = "Couldn't save: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun delete(item: Item) {
        busy = true
        scope.launch {
            try {
                api.deleteItem(item.id)
                onDone()
            } catch (e: ApiException) {
                error = if (e.code == 409) {
                    "It's placed in a layout. Remove it from those layouts first."
                } else {
                    "Couldn't delete: ${e.message}"
                }
            } catch (e: Exception) {
                error = "Couldn't delete: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val number = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Text(if (existing == null) "New item" else "Edit item", style = MaterialTheme.typography.headlineSmall)
        }
        OutlinedTextField(
            value = form.name, onValueChange = { form = form.copy(name = it) },
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.kind == ItemKinds.FURNITURE,
                onClick = { form = form.copy(kind = ItemKinds.FURNITURE) },
                label = { Text("Furniture (on the floor)") },
            )
            FilterChip(
                selected = form.kind == ItemKinds.WALL_DECOR,
                onClick = { form = form.copy(kind = ItemKinds.WALL_DECOR) },
                label = { Text("Wall decor") },
            )
        }
        Text("Size in centimetres, from the product listing", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.widthCm, onValueChange = { form = form.copy(widthCm = it) },
                label = { Text("Width") }, singleLine = true, keyboardOptions = number,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.depthCm, onValueChange = { form = form.copy(depthCm = it) },
                label = { Text(if (form.kind == ItemKinds.WALL_DECOR) "Depth (opt.)" else "Depth") },
                singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = form.heightCm, onValueChange = { form = form.copy(heightCm = it) },
                label = { Text("Height") }, singleLine = true, keyboardOptions = number,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = form.sourceUrl, onValueChange = { form = form.copy(sourceUrl = it) },
            label = { Text("Product link (optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.price, onValueChange = { form = form.copy(price = it) },
            label = { Text("Price (optional)") }, singleLine = true, keyboardOptions = number,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.notes, onValueChange = { form = form.copy(notes = it) },
            label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(),
        )

        Text(
            if (form.kind == ItemKinds.WALL_DECOR) {
                "Image: shown on the wall at the real size. Crop it to the artwork first."
            } else {
                "Image (optional): for recognising the item in lists."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        preview?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Item image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            )
        }
        OutlinedButton(
            onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !busy,
        ) { Text(if (preview == null) "Choose image" else "Change image") }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::save, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save") }
            if (existing != null) {
                OutlinedButton(onClick = { delete(existing) }, enabled = !busy) { Text("Delete") }
            }
        }
    }
}
