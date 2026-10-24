package com.xblackbytesx.slopstream.ui.comments

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xblackbytesx.slopstream.core.Comment
import com.xblackbytesx.slopstream.core.CommentsPage
import com.xblackbytesx.slopstream.core.ContentSource
import com.xblackbytesx.slopstream.core.SourceException
import com.xblackbytesx.slopstream.ui.components.ChannelAvatar
import com.xblackbytesx.slopstream.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** A comment and, once opened, its replies. */
data class CommentNode(
    val comment: Comment,
    val open: Boolean = false,
    val replies: List<Comment> = emptyList(),
    val next: CommentsPage.Cursor? = null,
    val loading: Boolean = false,
)

data class CommentsState(
    val nodes: List<CommentNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val next: CommentsPage.Cursor? = null,
    val disabled: Boolean = false,
    val started: Boolean = false,
)

/**
 * One video's comments, loaded page by page when asked (decision 20). In memory only: they
 * go with the screen or panel that showed them.
 */
class CommentsLoader(private val source: ContentSource, private val videoId: String, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(CommentsState())
    val state: StateFlow<CommentsState> = _state.asStateFlow()

    /** The first page, once. */
    fun start() {
        if (_state.value.started) return
        _state.update { it.copy(started = true) }
        page(null)
    }

    fun more() {
        val s = _state.value
        if (s.loading) return
        if (s.error != null && s.nodes.isEmpty()) page(null) else s.next?.let(::page)
    }

    private fun page(from: CommentsPage.Cursor?) {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val p = source.comments(videoId, from)
                _state.update { s ->
                    s.copy(nodes = s.nodes + p.comments.map(::CommentNode), next = p.next, disabled = p.disabled, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                _state.update { it.copy(loading = false, error = e.message ?: "Comments did not load") }
            }
        }
    }

    /** Opens a comment's replies (the first page the first time), or closes them. */
    fun toggleReplies(id: String) {
        val node = _state.value.nodes.firstOrNull { it.comment.id == id } ?: return
        if (node.open) {
            edit(id) { it.copy(open = false) }
            return
        }
        edit(id) { it.copy(open = true) }
        if (node.replies.isEmpty()) moreReplies(id, node.comment.replies)
    }

    fun moreReplies(id: String) = moreReplies(id, _state.value.nodes.firstOrNull { it.comment.id == id }?.next)

    private fun moreReplies(id: String, from: CommentsPage.Cursor?) {
        from ?: return
        edit(id) { it.copy(loading = true) }
        scope.launch {
            try {
                val p = source.comments(videoId, from)
                edit(id) { it.copy(replies = it.replies + p.comments, next = p.next, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SourceException) {
                edit(id) { it.copy(loading = false) }
                _state.update { it.copy(error = e.message ?: "Replies did not load") }
            }
        }
    }

    private fun edit(id: String, f: (CommentNode) -> CommentNode) =
        _state.update { s -> s.copy(nodes = s.nodes.map { if (it.comment.id == id) f(it) else it }) }
}

/** The list, for the player's side panel and the Comments screen. Every row takes focus (TV). */
@Composable
fun CommentsList(
    state: CommentsState,
    onMore: () -> Unit,
    onReplies: (String) -> Unit,
    onMoreReplies: (String) -> Unit,
    modifier: Modifier = Modifier,
    firstFocus: FocusRequester? = null,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.disabled) {
            item { Text("Comments are turned off for this video.", color = Palette.TextDim) }
        }
        if (state.nodes.isEmpty() && !state.loading && state.error == null && !state.disabled && state.started) {
            item { Text("No comments yet.", color = Palette.TextDim) }
        }
        items(state.nodes, key = { it.comment.id }) { node ->
            val first = firstFocus?.takeIf { node === state.nodes.first() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CommentRow(node.comment, Modifier.then(first?.let { Modifier.focusRequester(it) } ?: Modifier))
                if (node.comment.replyCount > 0 && node.comment.replies != null) {
                    TextButton(onClick = { onReplies(node.comment.id) }, modifier = Modifier.padding(start = 44.dp)) {
                        Text(
                            if (node.open) "Hide replies" else "${node.comment.replyCount} ${if (node.comment.replyCount == 1) "reply" else "replies"}",
                            color = Palette.Accent,
                        )
                    }
                }
                if (node.open) {
                    Column(Modifier.padding(start = 44.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        node.replies.forEach { CommentRow(it, small = true) }
                        when {
                            node.loading -> Spinner()
                            node.next != null -> TextButton(onClick = { onMoreReplies(node.comment.id) }) { Text("More replies", color = Palette.Accent) }
                        }
                    }
                }
            }
        }
        item {
            when {
                state.loading -> Spinner()
                state.error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.error, color = Palette.Live, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onMore) { Text("Try again", color = Palette.Accent) }
                }
                state.next != null -> TextButton(onClick = onMore) { Text("More comments", color = Palette.Accent) }
            }
        }
    }
}

@Composable
private fun CommentRow(c: Comment, modifier: Modifier = Modifier, small: Boolean = false) {
    Row(modifier.fillMaxWidth().focusable()) {
        ChannelAvatar(c.author.removePrefix("@"), c.avatarUrl, if (small) 28.dp else 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.pinned) Icon(Icons.Filled.PushPin, "Pinned", tint = Palette.TextDim, modifier = Modifier.size(14.dp))
                Text(
                    c.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (c.byUploader) FontWeight.Bold else FontWeight.Medium,
                    color = if (c.byUploader) Palette.Accent else Palette.Text,
                )
                c.published?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint) }
            }
            Text(c.text, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.likes?.takeIf { it > 0 }?.let {
                    Icon(Icons.Filled.ThumbUp, null, tint = Palette.TextFaint, modifier = Modifier.size(12.dp))
                    Text(compact(it), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                }
                if (c.heartedByUploader) Icon(Icons.Filled.Favorite, "Hearted by the uploader", tint = Palette.Live, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Accent, strokeWidth = 2.dp)
    }
}

/** 1234 -> 1.2K, as YouTube shows it. */
fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(Locale.ROOT, n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.1fK".format(Locale.ROOT, n / 1_000.0).replace(".0K", "K")
    else -> n.toString()
}
