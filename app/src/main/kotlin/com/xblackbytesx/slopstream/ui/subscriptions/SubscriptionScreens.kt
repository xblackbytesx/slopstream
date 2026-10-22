package com.xblackbytesx.slopstream.ui.subscriptions

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xblackbytesx.slopstream.core.Channel
import com.xblackbytesx.slopstream.core.SourceKey
import com.xblackbytesx.slopstream.data.db.SubscriptionEntity
import com.xblackbytesx.slopstream.ui.components.ChannelAvatar
import com.xblackbytesx.slopstream.ui.components.EmptyState
import com.xblackbytesx.slopstream.ui.components.ListRow
import com.xblackbytesx.slopstream.ui.components.PrimaryButton
import com.xblackbytesx.slopstream.ui.components.ScreenHeader
import com.xblackbytesx.slopstream.ui.components.ServiceBar
import com.xblackbytesx.slopstream.ui.components.SecondaryButton
import com.xblackbytesx.slopstream.ui.components.matchesWords
import com.xblackbytesx.slopstream.ui.components.relativeTime
import com.xblackbytesx.slopstream.ui.feed.refreshStatus
import com.xblackbytesx.slopstream.ui.theme.LocalDims
import com.xblackbytesx.slopstream.ui.theme.LocalIsTv
import com.xblackbytesx.slopstream.ui.theme.Palette

@Composable
fun SubscriptionsScreen(
    openChannel: (SubscriptionEntity) -> Unit,
    addChannel: () -> Unit,
    import: () -> Unit,
    search: (() -> Unit)? = null,
    signIn: (SourceKey) -> Unit = {},
    vm: SubscriptionsViewModel = hiltViewModel(),
) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val service by vm.service.collectAsStateWithLifecycle()
    // Account subscriptions are managed on the service's site: nothing to add or remove here.
    val managed = service.capabilities.accountSubscriptions
    var confirm by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    val dims = LocalDims.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Subscriptions",
            status = channels?.let { list ->
                val favorites = list.count { it.favorite }
                "${list.size} channels" + (if (favorites > 0) " \u00b7 $favorites favorites" else "") +
                    (refreshStatus(refresh)?.let { s -> " \u00b7 $s" } ?: "")
            },
            actions = { search?.let { IconButton(onClick = it) { Icon(Icons.Filled.Search, "Search") } } },
        )
        ServiceBar(service, vm::selectService, signIn)
        Row(
            Modifier.padding(horizontal = dims.gutter).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!managed) {
                PrimaryButton("Add channel", addChannel, icon = Icons.Filled.Add)
                SecondaryButton("Import", import, icon = Icons.Filled.Download)
            }
            SecondaryButton("Refresh all", vm::refreshAll, icon = Icons.Filled.Refresh, enabled = !refresh.running)
            if (managed) {
                Text("From your ${service.current.label} account", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
            }
        }
        val list = channels
        when {
            list == null -> Unit
            list.isEmpty() && managed -> EmptyState(
                "No subscriptions",
                "Your ${service.current.label} account has none yet. Subscribe on their site, then refresh.",
            )
            list.isEmpty() -> EmptyState("No subscriptions", "Add channels one by one, or import everything you follow in NewPipe or on YouTube.")
            else -> SubscriptionList(
                channels = list,
                filter = filter,
                onFilter = { filter = it },
                onOpen = openChannel,
                onFavorite = vm::setFavorite,
                onUnsubscribe = if (managed) null else ({ confirm = it }),
            )
        }
    }

    confirm?.let { s ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Unsubscribe from ${s.name}?") },
            text = { Text("Its videos disappear from your feeds. Watch later keeps what you saved.") },
            confirmButton = { TextButton(onClick = { vm.unsubscribe(s); confirm = null }) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }
}

/**
 * The filter field and the list: favorites pinned in their own section on top, then everyone
 * else, both A to Z. Stateless, so the screenshot test renders it.
 */
@Composable
fun SubscriptionList(
    channels: List<SubscriptionEntity>,
    filter: String,
    onFilter: (String) -> Unit,
    onOpen: (SubscriptionEntity) -> Unit,
    onFavorite: (SubscriptionEntity, Boolean) -> Unit,
    /** Null for account subscriptions, which are managed on the service's site. */
    onUnsubscribe: ((SubscriptionEntity) -> Unit)?,
) {
    val dims = LocalDims.current
    val (favorites, others) = remember(channels, filter) {
        channels.filter { matchesWords(it.name, filter) }.partition { it.favorite }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilter,
            singleLine = true,
            placeholder = { Text("Filter channels") },
            leadingIcon = { Icon(Icons.Filled.FilterList, null) },
            trailingIcon = if (filter.isNotEmpty()) ({ IconButton(onClick = { onFilter("") }) { Icon(Icons.Filled.Clear, "Clear filter") } }) else null,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent),
            modifier = Modifier.padding(horizontal = dims.gutter).padding(bottom = 12.dp).fillMaxWidth().widthIn(max = 640.dp),
        )
        if (favorites.isEmpty() && others.isEmpty()) {
            Text("No channel matches \u201c$filter\u201d.", color = Palette.TextDim, modifier = Modifier.padding(horizontal = dims.gutter))
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (favorites.isNotEmpty()) {
                item(key = "h:fav") { SectionLabel("Favorites") }
                items(favorites, key = { "${it.source}:${it.remoteId}" }) { s -> SubscriptionRow(s, onOpen, onFavorite, onUnsubscribe) }
                if (others.isNotEmpty()) item(key = "h:all") { SectionLabel("All channels") }
            }
            items(others, key = { "${it.source}:${it.remoteId}" }) { s -> SubscriptionRow(s, onOpen, onFavorite, onUnsubscribe) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Palette.TextDim, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
private fun SubscriptionRow(
    s: SubscriptionEntity,
    onOpen: (SubscriptionEntity) -> Unit,
    onFavorite: (SubscriptionEntity, Boolean) -> Unit,
    onUnsubscribe: ((SubscriptionEntity) -> Unit)?,
) {
    ListRow(onClick = { onOpen(s) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(s.name, s.avatarUrl, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = when {
                    s.lastError != null -> "Last refresh failed: ${s.lastError}"
                    s.lastRefreshAt != null -> "Refreshed ${relativeTime(s.lastRefreshAt).lowercase()}"
                    else -> "Not fetched yet"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (s.lastError != null) Palette.Live else Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onFavorite(s, !s.favorite) }) {
                Icon(
                    if (s.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (s.favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (s.favorite) Palette.Accent else Palette.TextDim,
                )
            }
            onUnsubscribe?.let { TextButton(onClick = { it(s) }) { Text("Unsubscribe", color = Palette.TextDim) } }
        }
    }
}

@Composable
fun AddChannelScreen(vm: AddChannelViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dims = LocalDims.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Add channel", status = "Search by name, or paste a channel link or @handle")
        Row(Modifier.padding(horizontal = dims.gutter).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = { Text("Channel name, URL or @handle") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent),
                modifier = Modifier.weight(1f).widthIn(max = 640.dp),
            )
            Spacer(Modifier.width(12.dp))
            PrimaryButton("Search", vm::search, enabled = state.query.isNotBlank() && !state.searching)
        }
        when {
            state.searching -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = Palette.Accent) }
            state.error != null -> Text(state.error!!, color = Palette.TextDim, modifier = Modifier.padding(horizontal = dims.gutter))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = dims.gutter, end = dims.gutter, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.results, key = { it.remoteId }) { c -> ChannelResult(c, c.remoteId in state.subscribed) { vm.toggle(c) } }
        }
    }
}

@Composable
internal fun ChannelResult(c: Channel, subscribed: Boolean, onToggle: () -> Unit) {
    ListRow(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(c.name, c.avatarUrl, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall)
                c.subscriberCount?.let { Text(formatCount(it) + " subscribers", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim) }
            }
            if (subscribed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Subscribed", color = Palette.Accent, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text("Subscribe", color = Palette.Text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun ImportScreen(vm: ImportViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isTv = LocalIsTv.current
    val dims = LocalDims.current
    var pickerMissing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importUri) }

    // A TV rarely has a file picker: open the LAN page right away there.
    LaunchedEffect(isTv) { if (isTv) vm.startLan() }

    Column(Modifier.fillMaxSize().padding(horizontal = dims.gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Import subscriptions", modifier = Modifier.padding(start = 0.dp))
        Text(
            "NewPipe: Settings → Backup and restore → Export database (zip), or Subscriptions → ⋮ → Export to file (json). " +
                "YouTube: Google Takeout → YouTube → subscriptions.csv. Importing only adds channels.",
            color = Palette.TextDim,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 720.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Choose file", {
                try {
                    picker.launch(arrayOf("application/zip", "application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
                } catch (e: ActivityNotFoundException) {
                    pickerMissing = true
                    vm.startLan()
                }
            }, icon = Icons.Filled.Download)
            if (state.lanAddress == null) SecondaryButton("Send from another device", vm::startLan, icon = Icons.Filled.Wifi)
        }
        if (pickerMissing) Text("This device has no file picker; use the address below instead.", color = Palette.TextDim)
        state.lanAddress?.let { address ->
            ListRow {
                Column {
                    Text("On a phone or computer in the same network, open", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
                    Text(address, style = MaterialTheme.typography.headlineSmall, color = Palette.Accent)
                    Text("Pick the export there, or paste channel links. Keep this screen open.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(state.progress ?: "Importing…")
            }
        }
        state.result?.let { r ->
            Text(
                buildString {
                    append("Added ${r.added} channels")
                    if (r.alreadySubscribed > 0) append(", ${r.alreadySubscribed} already followed")
                    append(". Their videos are being fetched now.")
                    if (r.failed.isNotEmpty()) append("\nNot found: ${r.failed.take(8).joinToString()}${if (r.failed.size > 8) "…" else ""}")
                },
                color = Palette.Text,
            )
        }
        state.error?.let { Text(it, color = Palette.Live) }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.1fK".format(n / 1_000.0).replace(".0K", "K")
    else -> n.toString()
}
