package app.roomplanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.roomplanner.Prefs
import app.roomplanner.api.Room
import app.roomplanner.api.RoomPlannerApi
import app.roomplanner.device.ArSupport
import app.roomplanner.device.checkArSupport
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    prefs: Prefs,
    onCapture: (api: RoomPlannerApi, room: Room) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var arSupport by remember { mutableStateOf<ArSupport?>(null) }
    LaunchedEffect(Unit) { arSupport = checkArSupport(context) }

    var serverInput by remember { mutableStateOf(prefs.serverUrl) }
    var api by remember { mutableStateOf<RoomPlannerApi?>(null) }
    var serverStatus by remember { mutableStateOf("Not connected") }
    val rooms = remember { mutableStateListOf<Room>() }
    var selectedRoomId by remember { mutableStateOf(prefs.roomId) }
    var newRoomName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun connect() {
        val base = RoomPlannerApi.parseBaseUrl(serverInput)
        if (base == null) {
            serverStatus = "That doesn't look like a server address"
            return
        }
        busy = true
        scope.launch {
            try {
                val client = RoomPlannerApi(base)
                val health = client.health()
                rooms.clear()
                rooms += client.listRooms()
                api = client
                prefs.serverUrl = serverInput.trim()
                serverStatus = "Connected to $base (server ${health.version})"
                if (rooms.none { it.id == selectedRoomId }) selectedRoomId = rooms.firstOrNull()?.id
            } catch (e: Exception) {
                api = null
                serverStatus = "Can't reach $base: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    // Reconnect automatically to the last server.
    LaunchedEffect(Unit) { if (serverInput.isNotBlank()) connect() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Room Planner", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This phone", style = MaterialTheme.typography.titleMedium)
                Text(arSupport?.label ?: "Checking ARCore…")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverInput,
                    onValueChange = { serverInput = it },
                    label = { Text("Address, e.g. 192.168.1.20:8000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::connect, enabled = !busy) { Text("Connect") }
                Text(serverStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (api != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Room", style = MaterialTheme.typography.titleMedium)
                    if (rooms.isEmpty()) Text("No rooms yet. Add one below.")
                    rooms.forEach { room ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = room.id == selectedRoomId,
                                    onClick = { selectedRoomId = room.id },
                                ),
                        ) {
                            RadioButton(selected = room.id == selectedRoomId, onClick = null)
                            Text(room.name, Modifier.padding(start = 8.dp))
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newRoomName,
                            onValueChange = { newRoomName = it },
                            label = { Text("New room") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                val client = api ?: return@OutlinedButton
                                val name = newRoomName.trim()
                                busy = true
                                scope.launch {
                                    try {
                                        val room = client.createRoom(name)
                                        rooms += room
                                        selectedRoomId = room.id
                                        newRoomName = ""
                                    } catch (e: Exception) {
                                        serverStatus = "Couldn't add room: ${e.message}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && newRoomName.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Add") }
                    }
                }
            }
        }

        val room = rooms.firstOrNull { it.id == selectedRoomId }
        val client = api
        Button(
            onClick = {
                if (client != null && room != null) {
                    prefs.roomId = room.id
                    onCapture(client, room)
                }
            },
            enabled = client != null && room != null && arSupport?.canCapture == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (room != null) "Capture snapshots of ${room.name}" else "Capture snapshots")
        }
    }
}
