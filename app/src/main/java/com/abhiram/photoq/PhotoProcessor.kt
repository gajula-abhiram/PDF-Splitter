package com.abhiram.photoq

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

class PhotoProcessor(
    private val context: Context,
    private val store: PhotoStore,
    private val scope: CoroutineScope
) {
    private val slots = Semaphore(4)
    private val client = CommandCodeClient(context)

    fun enqueueSingleAfterGrace(photo: CameraPhoto) {
        scope.launch {
            delay(10_000)
            val bundle = "single-${photo.id}-${UUID.randomUUID()}"
            if (store.claimBundle(listOf(photo), bundle)) process(listOf(photo))
        }
    }

    fun enqueueManual(photos: List<CameraPhoto>): Boolean {
        val ordered = photos.sortedBy { it.capturedAt }
        val bundle = "group-${UUID.randomUUID()}"
        if (!store.claimBundle(ordered, bundle)) return false
        process(ordered)
        return true
    }

    fun scanSince(since: Long) {
        scope.launch {
            queryCameraPhotos(since).forEach { p ->
                val bundle = "scan-${p.id}-${UUID.randomUUID()}"
                if (store.claimBundle(listOf(p), bundle)) process(listOf(p))
            }
        }
    }

    fun resumePending() {
        store.pendingBundles().forEach { process(it) }
    }

    fun retry(mediaId: Long) {
        val photos = store.retryMedia(mediaId)
        if (photos.isNotEmpty()) process(photos)
    }

    private fun process(photos: List<CameraPhoto>) {
        scope.launch {
            slots.withPermit {
                store.setBundleState(photos, "SENDING")
                try {
                    val (raw, parsed) = client.answer(photos)
                    if (parsed.results.isEmpty()) {
                        store.insertResult(null, emptyList(), null, raw, photos.map { it.uri }, "UNPARSED", "Could not normalize answer")
                    } else {
                        parsed.results.forEach { r ->
                            val status = if (r.answers.isEmpty() && r.answerText.isNullOrBlank()) "UNPARSED" else "DONE"
                            store.insertResult(r.questionNumber, r.answers, r.answerText, raw, photos.map { it.uri }, status,
                                if (status == "UNPARSED") "Could not normalize answer" else null)
                        }
                    }
                    store.setBundleState(photos, "DONE")
                } catch (t: Throwable) {
                    store.setBundleState(photos, "FAILED", t.message ?: t.javaClass.simpleName)
                    store.insertResult(null, emptyList(), null, "", photos.map { it.uri }, "FAILED", t.message ?: "Request failed")
                }
            }
        }
    }

    fun queryCameraPhotos(since: Long = 0L, limit: Int = 500): List<CameraPhoto> {
        val out = mutableListOf<CameraPhoto>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val selection = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?) AND (${MediaStore.Images.Media.DATE_TAKEN}>=? OR ${MediaStore.Images.Media.DATE_ADDED}>=?)"
        val args = arrayOf("DCIM/Camera/%", "DCIM/Camera", since.toString(), (since / 1000).toString())
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_TAKEN} ASC LIMIT $limit")?.use { c ->
            val idIx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeIx = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idIx)
                val taken = c.getLong(takenIx).takeIf { it > 0 } ?: c.getLong(addedIx) * 1000
                if (taken < since) continue
                val mime = c.getString(mimeIx) ?: continue
                if (!mime.startsWith("image/")) continue
                out += CameraPhoto(id, ContentUris.withAppendedId(collection, id).toString(), taken, mime)
            }
        }
        return out.distinctBy { it.id }.sortedBy { it.capturedAt }
    }
}
