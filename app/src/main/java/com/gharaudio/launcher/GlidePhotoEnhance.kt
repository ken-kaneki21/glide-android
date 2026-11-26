package com.gharaudio.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Serialize work across dialog lifetimes: a cancelled camera request can still be closing.
private val photoWorkMutex = Mutex()

private suspend fun enhancePhoto(bitmap: Bitmap): Bitmap {
    val job = currentCoroutineContext()
    val strength = photoLiftStrength(luminanceGrid(bitmap, minOf(144, bitmap.width), minOf(144, bitmap.height)))
    if (strength == 0.0) return checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
    val gains = photoLiftGains(strength)
    val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    try {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            job.ensureActive()
            bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
            for (i in row.indices) row[i] = liftPhotoPixel(row[i], gains)
            output.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        return output
    } catch (failure: Throwable) {
        output.recycle()
        throw failure
    }
}

private data class PhotoResult(val bitmap: Bitmap, val message: String, val originalPreview: Bitmap? = null)

/** Independent ARGB preview, including for inputs already smaller than the bound. */
internal fun createOriginalPhotoPreview(bitmap: Bitmap): Bitmap {
    val (width, height) = photoPreviewDimensions(bitmap.width, bitmap.height)
    val preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(preview).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return preview
    } catch (failure: Throwable) {
        preview.recycle()
        throw failure
    }
}

private suspend fun repairPhotoMark(current:Bitmap, original:Bitmap?, point:Pair<Int,Int>):PhotoResult {
    val job=currentCoroutineContext()
    val scale=minOf(1.0,1024.0/maxOf(current.width,current.height))
    val width=(current.width*scale).toInt().coerceAtLeast(1)
    val height=(current.height*scale).toInt().coerceAtLeast(1)
    val working=if(scale<1.0)Bitmap.createScaledBitmap(current,width,height,true) else current
    val pixels=IntArray(width*height)
    try {working.getPixels(pixels,0,width,0,0,width,height)}
    finally {if(working!==current)working.recycle()}
    val radius=(minOf(32,maxOf(1,minOf(current.width,current.height)/40))*scale).toInt().coerceAtLeast(1)
    val repaired=repairSmallPhotoMark(pixels,width,height,(point.first*scale).toInt().coerceIn(0,width-1),
        (point.second*scale).toInt().coerceIn(0,height-1),radius){job.ensureActive()}
    val result=Bitmap.createBitmap(repaired,width,height,Bitmap.Config.ARGB_8888)
    var before:Bitmap?=null
    try {
        job.ensureActive()
        before=original?.copy(Bitmap.Config.ARGB_8888,false)
        job.ensureActive()
        return PhotoResult(result,"Small mark repaired locally. Review edges before saving a copy, up to 1024 px.",before)
    }catch(failure:Throwable){result.recycle();before?.recycle();throw failure}
}

private fun luminanceGrid(bitmap: Bitmap, width: Int, height: Int): IntArray {
    val preview = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val pixels = IntArray(width * height)
    try {
        preview.getPixels(pixels, 0, width, 0, 0, width, height)
    } finally {
        if (preview !== bitmap) preview.recycle()
    }
    for (i in pixels.indices) {
        val pixel = pixels[i]
        pixels[i] = (54 * (pixel ushr 16 and 255) + 183 * (pixel ushr 8 and 255) +
            19 * (pixel and 255)) ushr 8
    }
    return pixels
}

private suspend fun stackBurst(context: android.content.Context, frames: List<Uri>): PhotoResult {
    val job = currentCoroutineContext()
    require(frames.size == 3)
    val bitmaps = mutableListOf<Bitmap>()
    try {
        for (uri in frames) {
            job.ensureActive()
            withContext(Dispatchers.IO) {
                bitmaps.add(ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val size = info.size
                    val scale = minOf(1.0, 1600.0 / maxOf(size.width, size.height))
                    decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1),
                        (size.height * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                })
            }
        }
        val a = bitmaps.first()
        require(bitmaps.all { it.width == a.width && it.height == a.height })
        val gridWidth = minOf(144, a.width)
        val gridHeight = minOf(144, a.height)
        val reference = luminanceGrid(bitmaps[1], gridWidth, gridHeight)
        var reliable = gridWidth >= 3 && gridHeight >= 3
        val offsets = bitmaps.mapIndexed { index, bitmap ->
            if (index == 1 || !reliable) 0 to 0 else {
                val grid = luminanceGrid(bitmap, gridWidth, gridHeight)
                val offset = findFrameOffset(reference, grid, gridWidth, gridHeight) { job.ensureActive() }
                reliable = safeToStack(reference, grid, gridWidth, gridHeight, offset)
                offset.first * a.width / gridWidth to offset.second * a.height / gridHeight
            }
        }
        if (!reliable) {
            // The middle frame is the alignment reference; do not invent a sharpness ranking.
            return PhotoResult(checkNotNull(bitmaps[1].copy(Bitmap.Config.ARGB_8888, false)),
                "Motion or uncertain alignment: using the middle photo, without stacking. Up to 1600 px.")
        }
        val left = -offsets.minOf { it.first }
        val top = -offsets.minOf { it.second }
        val width = a.width - offsets.maxOf { it.first } - left
        val height = a.height - offsets.maxOf { it.second } - top
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rows = Array(3) { IntArray(width) }
        val result = IntArray(width)
        try {
        for (y in 0 until height) {
            job.ensureActive()
            bitmaps.forEachIndexed { index, bitmap ->
                val (dx, dy) = offsets[index]
                bitmap.getPixels(rows[index], 0, width, left + dx, top + y + dy, width, 1)
            }
            for (x in result.indices) {
                val r = medianOfThree(rows[0][x] ushr 16 and 255, rows[1][x] ushr 16 and 255, rows[2][x] ushr 16 and 255)
                val g = medianOfThree(rows[0][x] ushr 8 and 255, rows[1][x] ushr 8 and 255, rows[2][x] ushr 8 and 255)
                val b = medianOfThree(rows[0][x] and 255, rows[1][x] and 255, rows[2][x] and 255)
                result[x] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
            output.setPixels(result, 0, width, 0, y, width, 1)
        }
        return PhotoResult(output, "Aligned three-frame preview. Up to 1600 px; save a JPEG copy.")
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    } finally {
        bitmaps.forEach(Bitmap::recycle)
    }
}

@Composable
internal fun GlidePhotoEnhance(onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var originalPreview by remember { mutableStateOf<Bitmap?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var selectingMark by remember { mutableStateOf(false) }
    var selectedMark by remember { mutableStateOf<Pair<Int,Int>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a photo to lift shadows and save an enhanced copy up to 2048 px.") }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var requestBurst by rememberSaveable { mutableStateOf(false) }

    var work by remember { mutableStateOf<Job?>(null) }
    var documentPending by remember { mutableStateOf(false) }

    fun runPhotoWork(operation: suspend () -> PhotoResult) {
        if (busy || documentPending || capturePath != null) return
        busy = true
        work = scope.launch {
            // Holder also owns a result if cancellation occurs while dispatching it back to Main.
            var completed: PhotoResult? = null
            try {
                photoWorkMutex.withLock {
                    withContext(Dispatchers.Default) { completed = operation() }
                    currentCoroutineContext().ensureActive()
                    val ready = checkNotNull(completed)
                    image = ready.bitmap
                    originalPreview = ready.originalPreview
                    showOriginal = false
                    selectingMark = false
                    selectedMark = null
                    status = ready.message
                    completed = null // UI owns it; old UI images are released by GC, never recycled while drawn.
                }
            } catch (cancelled: CancellationException) {
                status = "Cancelled. Your previous preview is unchanged."
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("GlidePhoto", "Photo operation failed", failure)
                status = "Glide could not process these photos. Your previous preview is unchanged."
            } finally {
                completed?.bitmap?.recycle()
                completed?.originalPreview?.recycle()
                busy = false
                work = null
            }
        }
    }

    fun captureBurst() {
        val rotation = (view.display?.rotation ?: 0) * 90
        runPhotoWork {
            val files = mutableListOf<File>()
            try {
                withContext(Dispatchers.IO) {
                    repeat(3) { files.add(File.createTempFile("glide-burst-", ".jpg", context.cacheDir)) }
                    captureGlideBurst(context, rotation, files)
                }
                currentCoroutineContext().ensureActive()
                stackBurst(context, files.map(Uri::fromFile))
            } finally {
                files.forEach { it.delete() }
            }
        }
    }
    fun process(uri: Uri, capturedFile: File? = null) {
        runPhotoWork {
            var decoded: Bitmap? = null
            var enhanced: Bitmap? = null
            var before: Bitmap? = null
            var transferred = false
            try {
                withContext(Dispatchers.IO) {
                    decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                        val source = info.size
                        val scale = minOf(1.0, 2048.0 / maxOf(source.width, source.height))
                        decoder.setTargetSize((source.width * scale).toInt().coerceAtLeast(1),
                            (source.height * scale).toInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
                currentCoroutineContext().ensureActive()
                val input = checkNotNull(decoded)
                enhanced = enhancePhoto(input)
                currentCoroutineContext().ensureActive()
                before = createOriginalPhotoPreview(input)
                currentCoroutineContext().ensureActive()
                PhotoResult(checkNotNull(enhanced), "Compare the original and enhanced previews. Save copy exports Enhanced, up to 2048 px.",
                    checkNotNull(before)).also { transferred = true }
            } finally {
                decoded?.recycle()
                if (!transferred) { enhanced?.recycle(); before?.recycle() }
                capturedFile?.delete()
            }
        }
    }
    val burstPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { frames ->
        documentPending = false
        if (frames.isNotEmpty() && frames.size != 3) {
            status = "Choose exactly three frames of the same still scene."
        } else if (frames.size == 3) runPhotoWork { stackBurst(context, frames) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        documentPending = false
        uri?.let { process(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        documentPending = false
        val file = capturePath?.let(::File)
        capturePath = null
        if (captured && file != null) {
            process(FileProvider.getUriForFile(context, context.packageName + ".photo", file), file)
        } else {
            file?.delete()
        }
    }
    fun takePhoto() {
        var file: File? = null
        runCatching {
            val directory = File(context.cacheDir, "glide-photo")
            directory.mkdirs()
            val pending = File.createTempFile("capture-", ".jpg", directory)
            file = pending
            val uri = FileProvider.getUriForFile(context, context.packageName + ".photo", pending)
            capturePath = pending.absolutePath
            documentPending = true
            camera.launch(uri)
        }.onFailure {
            file?.delete()
            capturePath = null
            documentPending = false
            status = "Glide could not open the camera"
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        documentPending = false
        when {
            !granted -> status = "Allow camera access to take photos."
            requestBurst -> captureBurst()
            else -> takePhoto()
        }
    }
    fun requestCapture(burst: Boolean) {
        val permission = android.Manifest.permission.CAMERA
        if (context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestBurst = burst
            documentPending = true
            cameraPermission.launch(permission)
        } else if (burst) captureBurst() else takePhoto()
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        documentPending = false
        val ready = image
        if (uri != null && ready != null && !busy) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            check(ready.compress(Bitmap.CompressFormat.JPEG, 94, it))
                        } ?: error("Could not open output")
                    }
                    status = "Enhanced copy saved"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status = "Glide could not save the copy. The destination may contain an incomplete file."
                } finally { busy = false }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy && !documentPending) onClose() },
        title = { Text("Enhance photo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val visible = if (showOriginal) originalPreview ?: image else image
                visible?.let { GlidePhotoRepairPreview(it,
                    if(showOriginal) "Original photo preview" else "Enhanced photo preview",
                    selectingMark && !showOriginal && !busy && !documentPending,selectedMark){selectedMark=it} }
                if (originalPreview != null) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(enabled = !busy && !documentPending, onClick = { showOriginal = true; selectingMark=false; selectedMark=null }) {
                            Text(if (showOriginal) "Original • selected" else "Original")
                        }
                        TextButton(enabled = !busy && !documentPending, onClick = { showOriginal = false }) {
                            Text(if (!showOriginal) "Enhanced • selected" else "Enhanced")
                        }
                    }
                    Text("Save copy always exports Enhanced. Original preview is limited to 768 px.")
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(enabled=!busy && !documentPending,onClick={showOriginal=false;selectingMark=!selectingMark;selectedMark=null}) {
                            Text(if(selectingMark) "Cancel mark selection" else "Select small mark")
                        }
                        TextButton(enabled=!busy && !documentPending && selectedMark!=null,onClick={
                            val current=image;val before=originalPreview;val point=selectedMark
                            if(current!=null && point!=null)runPhotoWork{repairPhotoMark(current,before,point)}
                        }) {Text("Repair mark")}
                    }
                    if(selectingMark)Text("Tap a small mark. Simple pixel repair works best on plain backgrounds; repair copies are limited to 1024 px.")
                }
                Text(if (busy) "Processing…" else status)
                if (busy && work != null) TextButton(onClick = { work?.cancel() }) { Text("Cancel processing") }
                TextButton(enabled = !busy && !documentPending && capturePath == null, onClick = { requestCapture(false) }) { Text("Take photo") }
                TextButton(enabled = !busy && !documentPending && capturePath == null, onClick = { requestCapture(true) }) {
                    Text("Capture three-frame burst")
                }
                TextButton(enabled = !busy && !documentPending, onClick = { documentPending = true; picker.launch("image/*") }) { Text("Choose photo") }
                TextButton(enabled = !busy && !documentPending, onClick = { documentPending = true; burstPicker.launch("image/*") }) { Text("Stack three still frames") }
            }
        },
        confirmButton = {
            TextButton(enabled = image != null && !busy && !documentPending, onClick = { documentPending = true; exporter.launch("Glide-enhanced.jpg") }) {
                Text("Save copy")
            }
        },
        dismissButton = { TextButton(enabled = !busy && !documentPending, onClick = onClose) { Text("Close") } }
    )
}
