package com.gharaudio.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@SuppressLint("MissingPermission")
internal fun captureGlideBurst(context: Context, rotationDegrees: Int, files: List<File>) {
    val manager = context.getSystemService(CameraManager::class.java)
    val cameraId = manager.cameraIdList.first { id ->
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    }
    val characteristics = manager.getCameraCharacteristics(cameraId)
    val streams = checkNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
    val jpeg = streams.getOutputSizes(ImageFormat.JPEG).minBy {
        kotlin.math.abs(maxOf(it.width, it.height) - 1600)
    }
    val previewSize = streams.getOutputSizes(ImageFormat.YUV_420_888).minBy { it.width * it.height }
    val orientation = ((characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) -
        rotationDegrees + 360) % 360
    val opened = CompletableFuture<CameraDevice>()
    val configured = CompletableFuture<CameraCaptureSession>()
    val exposed = CompletableFuture<Unit>()
    val saved = CompletableFuture<Unit>()
    val thread = HandlerThread("GlideBurstCapture").apply { start() }
    val handler = Handler(thread.looper)
    try {
        ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 3).use { images ->
            ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).use { preview ->
                var index = 0
                images.setOnImageAvailableListener({ reader ->
                    runCatching {
                        reader.acquireNextImage()?.use { image ->
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            files[index++].writeBytes(bytes)
                            if (index == files.size) saved.complete(Unit)
                        }
                    }.onFailure { saved.completeExceptionally(it) }
                }, handler)
                preview.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (!opened.complete(camera)) camera.close()
                    }
                    override fun onDisconnected(camera: CameraDevice) = fail(camera, "Camera disconnected")
                    override fun onError(camera: CameraDevice, error: Int) = fail(camera, "Camera error $error")
                    private fun fail(camera: CameraDevice, message: String) {
                        camera.close()
                        val failure = IllegalStateException(message)
                        opened.completeExceptionally(failure)
                        configured.completeExceptionally(failure)
                        saved.completeExceptionally(failure)
                    }
                }, handler)
                val camera = try {
                    opened.get(4, TimeUnit.SECONDS)
                } catch (failure: TimeoutException) {
                    if (!opened.completeExceptionally(failure)) opened.getNow(null)?.close()
                    throw failure
                }
                camera.use {
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(listOf(preview.surface, images.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                configured.complete(session)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                configured.completeExceptionally(IllegalStateException("Camera configuration failed"))
                            }
                        }, handler)
                    configured.get(4, TimeUnit.SECONDS).use { session ->
                        val warmup = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview.surface)
                        }.build()
                        session.setRepeatingRequest(warmup, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
                                result: TotalCaptureResult) {
                                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                                if ((ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == null) &&
                                    af != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) exposed.complete(Unit)
                            }
                        }, handler)
                        try {
                            exposed.get(2, TimeUnit.SECONDS)
                        } catch (_: TimeoutException) {
                            // Some cameras never report converged exposure in low light.
                        }
                        session.stopRepeating()
                        val shot = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(images.surface)
                            set(CaptureRequest.JPEG_ORIENTATION, orientation)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        session.captureBurst(List(files.size) { shot }, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest,
                                failure: CaptureFailure) {
                                saved.completeExceptionally(IllegalStateException("Burst frame failed: ${failure.reason}"))
                            }

                            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                                saved.completeExceptionally(IllegalStateException("Burst was aborted"))
                            }
                        }, handler)
                        saved.get(8, TimeUnit.SECONDS)
                    }
                }
            }
        }
    } finally {
        thread.quitSafely()
        thread.join()
    }
}
