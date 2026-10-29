package com.xblackbytesx.slopstream.data.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The sound of an MP4 alone, as an M4A: Media3 Transformer with the video removed copies the
 * AAC track as it is (no re-encoding). Transformer wants a looper thread, so it runs on main;
 * the work itself is on its own threads.
 */
@OptIn(UnstableApi::class)
class TransformerAudioExtractor @Inject constructor(@ApplicationContext private val context: Context) : AudioExtractor {
    override suspend fun extract(input: File, output: File) = withContext(Dispatchers.Main) {
        output.delete()
        suspendCancellableCoroutine { done ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.resume(Unit)
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) =
                        done.resumeWithException(exportException)
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(input.toUri())).setRemoveVideo(true).build()
            transformer.start(item, output.path)
            done.invokeOnCancellation { transformer.cancel() }
        }
    }
}
