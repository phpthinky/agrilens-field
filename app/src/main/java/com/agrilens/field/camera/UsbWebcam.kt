package com.agrilens.field.camera

import android.hardware.usb.UsbDevice
import android.view.SurfaceHolder
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.serenegiant.utils.UVCUtils
import com.serenegiant.widget.AspectRatioSurfaceView
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives the colorimeter box's USB/OTG (UVC) webcam via UVCAndroid, which talks
 * to the camera over USB host directly — mobile browsers and most phones'
 * Camera2 stacks can't open such cameras, which is why this lives in the app.
 *
 * This class only previews and takes photos; it never interprets colors.
 * Call [start] in onStart and [stop] in onStop (after CAMERA permission is
 * granted — Android requires it before granting USB access to a video device).
 */
class UsbWebcam(
    private val surfaceView: AspectRatioSurfaceView,
    private val onStateChanged: (State, UsbDevice?) -> Unit,
) {

    enum class State { WAITING_FOR_DEVICE, CONNECTING, STREAMING, DISCONNECTED, PERMISSION_DENIED, ERROR }

    private var helper: ICameraHelper? = null

    var state: State = State.WAITING_FOR_DEVICE
        private set

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (state == State.STREAMING) helper?.addSurface(holder.surface, false)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            helper?.removeSurface(holder.surface)
        }
    }

    // UVCAndroid delivers these on the main thread.
    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            setState(State.CONNECTING, device)
            // Triggers the USB permission dialog the first time.
            helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            helper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val h = helper ?: return
            h.startPreview()
            h.previewSize?.let { surfaceView.setAspectRatio(it.width, it.height) }
            h.addSurface(surfaceView.holder.surface, false)
            setState(State.STREAMING, device)
        }

        override fun onCameraClose(device: UsbDevice) {
            helper?.removeSurface(surfaceView.holder.surface)
        }

        override fun onDeviceClose(device: UsbDevice) {
            setState(State.DISCONNECTED, device)
        }

        override fun onDetach(device: UsbDevice) {
            setState(State.WAITING_FOR_DEVICE, null)
        }

        override fun onCancel(device: UsbDevice) {
            setState(State.PERMISSION_DENIED, device)
        }

        override fun onError(device: UsbDevice?, e: CameraException?) {
            setState(State.ERROR, device)
        }
    }

    init {
        surfaceView.setAspectRatio(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        surfaceView.holder.addCallback(surfaceCallback)
    }

    fun start() {
        if (helper != null) return
        // Hand the library the Application up front instead of its reflection fallback.
        UVCUtils.init(surfaceView.context)
        setState(State.WAITING_FOR_DEVICE, null)
        helper = CameraHelper().apply { setStateCallback(stateCallback) }
    }

    fun stop() {
        helper?.release()
        helper = null
    }

    /** Re-try an already plugged-in camera, e.g. after the user denied USB permission. */
    fun rescan() {
        val h = helper ?: return start()
        val device = h.deviceList?.firstOrNull()
        if (device == null) {
            setState(State.WAITING_FOR_DEVICE, null)
        } else {
            setState(State.CONNECTING, device)
            h.selectDevice(device)
        }
    }

    /** Saves the current frame as a JPEG at [file]. */
    suspend fun takePicture(file: File): File = suspendCancellableCoroutine { cont ->
        val h = helper
        if (h == null || state != State.STREAMING) {
            cont.resumeWithException(IOException("The USB camera is not streaming."))
            return@suspendCancellableCoroutine
        }

        val options = IImageCapture.OutputFileOptions.Builder(file).build()
        h.takePicture(options, object : IImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(outputFileResults: IImageCapture.OutputFileResults) {
                if (cont.isActive) cont.resume(file)
            }

            override fun onError(imageCaptureError: Int, message: String, cause: Throwable?) {
                if (cont.isActive) cont.resumeWithException(IOException(message, cause))
            }
        })
    }

    private fun setState(newState: State, device: UsbDevice?) {
        state = newState
        onStateChanged(newState, device)
    }

    companion object {
        private const val DEFAULT_WIDTH = 640
        private const val DEFAULT_HEIGHT = 480
    }
}
