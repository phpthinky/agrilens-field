package com.agrilens.field.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.camera.UsbWebcam
import com.agrilens.field.databinding.ActivityColorimetricBinding
import com.agrilens.field.databinding.ItemColorimetricParameterBinding
import com.agrilens.field.model.ColorimetricParameter
import com.agrilens.field.model.ColorimetricStatus
import com.agrilens.field.network.ApiClient
import com.agrilens.field.network.ApiService
import com.agrilens.field.storage.SessionStore
import com.google.gson.Gson
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Colorimetric soil test for one sample: preview the colorimeter box's USB/OTG
 * webcam, photograph each reagent tube (3 per parameter) and upload the photos.
 *
 * The server is the source of truth — it picks the color from each photo,
 * converts it, averages the captures and decides pH Step 2's indicator. This
 * screen just renders the status it returns. "Analyze" opens the server's own
 * result page in [ResultWebViewActivity].
 */
class ColorimetricActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorimetricBinding
    private lateinit var api: ApiService
    private lateinit var webcam: UsbWebcam

    private var sampleId: Long = -1
    private var status: ColorimetricStatus? = null
    private var selectedKey: String? = null
    private var busy = false
    private var timer: CountDownTimer? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                webcam.start()
            } else {
                binding.textCameraStatus.setText(R.string.webcam_status_camera_permission)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityColorimetricBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sampleId = intent.getLongExtra(EXTRA_SAMPLE_ID, -1)
        if (sampleId < 0) {
            finish()
            return
        }

        api = ApiClient.create(SessionStore(this))
        webcam = UsbWebcam(binding.cameraView) { state, device -> renderCameraState(state, device) }

        binding.toolbar.setNavigationOnClickListener { finish() }
        intent.getStringExtra(EXTRA_TITLE)?.let { binding.toolbar.subtitle = it }

        binding.btnRescan.setOnClickListener {
            if (hasCameraPermission()) webcam.rescan() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        binding.btnCapture.setOnClickListener { capture() }
        binding.btnReset.setOnClickListener { confirmReset() }
        binding.btnTimer.setOnClickListener { startTimer() }
        binding.btnAnalyze.setOnClickListener { analyze() }
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) {
            webcam.start()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // Also refreshes after returning from the result page, which is where
        // the server finalises the analysis.
        loadStatus()
    }

    override fun onStop() {
        super.onStop()
        webcam.stop()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ── Server status ────────────────────────────────────────────────

    private fun loadStatus() {
        lifecycleScope.launch {
            try {
                val response = api.getColorimetricStatus(sampleId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    render(body)
                } else {
                    showError(errorMessage(response.errorBody()?.string()) ?: getString(R.string.colorimetric_load_error))
                }
            } catch (e: IOException) {
                showError(getString(R.string.login_error_network))
            }
        }
    }

    private fun render(newStatus: ColorimetricStatus) {
        status = newStatus
        val sample = newStatus.sample

        binding.textSampleName.text = sample.sample_name ?: ""
        binding.textSampleMeta.text = listOfNotNull(sample.farm_name, sample.farmer_name).joinToString(" — ")

        // Stay on the current parameter until it's done, then move to the next unfinished one.
        val current = newStatus.parameters.find { it.key == selectedKey }
        if (current == null || !current.can_capture) {
            selectedKey = (newStatus.parameters.firstOrNull { it.can_capture } ?: current ?: newStatus.parameters.first()).key
        }

        binding.parameterContainer.removeAllViews()
        newStatus.parameters.forEach { parameter ->
            val item = ItemColorimetricParameterBinding.inflate(layoutInflater, binding.parameterContainer, true)
            item.textLabel.text = parameter.label
            item.textProgress.text = if (parameter.complete) {
                getString(R.string.colorimetric_done)
            } else {
                "${parameter.captures}/${parameter.required}"
            }
            item.textProgress.setTextColor(
                ContextCompat.getColor(this, if (parameter.complete) R.color.agrilens_green else R.color.text_muted)
            )
            item.textMessage.visibility = if (parameter.message.isNullOrBlank()) View.GONE else View.VISIBLE
            item.textMessage.text = parameter.message
            item.viewSwatch.setBackgroundColor(
                parseColor(parameter.average_hex) ?: ContextCompat.getColor(this, R.color.text_muted)
            )
            item.root.isChecked = parameter.key == selectedKey
            item.root.strokeWidth = if (parameter.key == selectedKey) dp(2) else 0
            item.root.setOnClickListener {
                selectedKey = parameter.key
                status?.let { render(it) }
            }
        }

        renderSelected()

        binding.btnAnalyze.isEnabled = sample.ready_to_analyze && !busy
        binding.btnAnalyze.setText(if (sample.analyzed) R.string.colorimetric_view_result else R.string.colorimetric_analyze)
        binding.textAnalyzeHint.visibility = if (sample.ready_to_analyze) View.GONE else View.VISIBLE
    }

    private fun selectedParameter(): ColorimetricParameter? =
        status?.parameters?.find { it.key == selectedKey }

    private fun renderSelected() {
        val parameter = selectedParameter() ?: return

        binding.textSelectedTitle.text = when {
            parameter.complete -> getString(R.string.colorimetric_selected_done, parameter.label)
            parameter.can_capture -> getString(
                R.string.colorimetric_selected_capture,
                parameter.label,
                parameter.captures + 1,
                parameter.required,
            )
            else -> parameter.label
        }
        binding.textSelectedReagent.text = listOfNotNull(parameter.reagent, parameter.message).joinToString("\n")
        binding.btnReset.visibility = if (parameter.can_reset) View.VISIBLE else View.GONE
        binding.btnTimer.isEnabled = parameter.can_capture
        if (timer == null) binding.textTimer.text = formatSeconds(parameter.reaction_seconds)

        updateCaptureEnabled()
    }

    private fun updateCaptureEnabled() {
        binding.btnCapture.isEnabled = !busy &&
            webcam.state == UsbWebcam.State.STREAMING &&
            selectedParameter()?.can_capture == true
    }

    // ── Webcam ───────────────────────────────────────────────────────

    private fun renderCameraState(state: UsbWebcam.State, device: UsbDevice?) {
        val name = device?.productName ?: getString(R.string.webcam_default_name)
        binding.textCameraStatus.text = when (state) {
            UsbWebcam.State.WAITING_FOR_DEVICE -> getString(R.string.webcam_status_waiting)
            UsbWebcam.State.CONNECTING -> getString(R.string.webcam_status_connecting, name)
            UsbWebcam.State.STREAMING -> getString(R.string.webcam_status_streaming, name)
            UsbWebcam.State.DISCONNECTED -> getString(R.string.webcam_status_disconnected)
            UsbWebcam.State.PERMISSION_DENIED -> getString(R.string.webcam_status_permission_denied)
            UsbWebcam.State.ERROR -> getString(R.string.webcam_status_error)
        }
        binding.textCameraStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when (state) {
                    UsbWebcam.State.STREAMING -> R.color.agrilens_green
                    UsbWebcam.State.PERMISSION_DENIED, UsbWebcam.State.ERROR -> R.color.agrilens_red
                    else -> R.color.text_muted
                },
            )
        )
        updateCaptureEnabled()
    }

    // ── Capture → upload ─────────────────────────────────────────────

    private fun capture() {
        val parameter = selectedParameter() ?: return
        setBusy(true)
        hideError()

        lifecycleScope.launch {
            val dir = File(cacheDir, "colorimeter").apply { mkdirs() }
            val photo = File(dir, "${parameter.key}-${System.currentTimeMillis()}.jpg")
            try {
                webcam.takePicture(photo)

                val response = api.uploadColorimetricCapture(
                    sampleId,
                    parameter.key.toRequestBody("text/plain".toMediaType()),
                    MultipartBody.Part.createFormData(
                        "photo",
                        photo.name,
                        photo.asRequestBody("image/jpeg".toMediaType()),
                    ),
                )
                val body = response.body()

                if (response.isSuccessful && body != null) {
                    timer?.cancel()
                    timer = null
                    Toast.makeText(
                        this@ColorimetricActivity,
                        getString(R.string.colorimetric_capture_saved, body.message, body.capture.color_hex),
                        Toast.LENGTH_SHORT,
                    ).show()
                    render(body.status)
                } else {
                    showError(errorMessage(response.errorBody()?.string()) ?: getString(R.string.colorimetric_upload_error))
                    loadStatus()
                }
            } catch (e: IOException) {
                showError(e.message ?: getString(R.string.login_error_network))
            } finally {
                photo.delete()
                setBusy(false)
            }
        }
    }

    private fun confirmReset() {
        val parameter = selectedParameter() ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.colorimetric_reset_title, parameter.label))
            .setMessage(R.string.colorimetric_reset_message)
            .setPositiveButton(R.string.colorimetric_reset) { _, _ -> reset(parameter) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reset(parameter: ColorimetricParameter) {
        setBusy(true)
        hideError()

        lifecycleScope.launch {
            try {
                val response = api.resetColorimetricParameter(sampleId, parameter.key)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    render(body.status)
                } else {
                    showError(errorMessage(response.errorBody()?.string()) ?: getString(R.string.colorimetric_reset_error))
                }
            } catch (e: IOException) {
                showError(getString(R.string.login_error_network))
            } finally {
                setBusy(false)
            }
        }
    }

    // ── Analyze → server result page ─────────────────────────────────

    private fun analyze() {
        setBusy(true)
        hideError()

        lifecycleScope.launch {
            try {
                val response = api.getResultLink(sampleId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val resultIntent = Intent(this@ColorimetricActivity, ResultWebViewActivity::class.java).apply {
                        putExtra(ResultWebViewActivity.EXTRA_URL, body.url)
                        putExtra(ResultWebViewActivity.EXTRA_TITLE, status?.sample?.sample_name)
                    }
                    startActivity(resultIntent)
                } else {
                    showError(errorMessage(response.errorBody()?.string()) ?: getString(R.string.colorimetric_analyze_error))
                }
            } catch (e: IOException) {
                showError(getString(R.string.login_error_network))
            } finally {
                setBusy(false)
            }
        }
    }

    // ── Reaction timer (informational, like the web page's) ──────────

    private fun startTimer() {
        val seconds = selectedParameter()?.reaction_seconds ?: return
        timer?.cancel()
        binding.textTimer.setTextColor(ContextCompat.getColor(this, R.color.agrilens_amber))
        timer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.textTimer.text = formatSeconds(((millisUntilFinished + 999) / 1000).toInt())
            }

            override fun onFinish() {
                binding.textTimer.setText(R.string.colorimetric_timer_done)
                binding.textTimer.setTextColor(ContextCompat.getColor(this@ColorimetricActivity, R.color.agrilens_green))
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progressCapture.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnReset.isEnabled = !value
        binding.btnAnalyze.isEnabled = !value && status?.sample?.ready_to_analyze == true
        updateCaptureEnabled()
    }

    private fun showError(message: String) {
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.textError.visibility = View.GONE
    }

    private fun errorMessage(errorBody: String?): String? =
        runCatching { errorBody?.let { Gson().fromJson(it, Map::class.java)["message"] as? String } }.getOrNull()

    private fun parseColor(hex: String?): Int? = hex?.let { runCatching { Color.parseColor(it) }.getOrNull() }

    private fun formatSeconds(seconds: Int): String =
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SAMPLE_ID = "extra_sample_id"
        const val EXTRA_TITLE = "extra_title"
    }
}
