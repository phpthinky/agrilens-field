package com.agrilens.field.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.CountDownTimer
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.camera.UsbWebcam
import com.agrilens.field.colorimetry.ColorimetricRepository
import com.agrilens.field.colorimetry.MobileColorimetry
import com.agrilens.field.colorimetry.MobileTest
import com.agrilens.field.colorimetry.ParameterView
import com.agrilens.field.colorimetry.SnapshotColor
import com.agrilens.field.colorimetry.StoredConfig
import com.agrilens.field.colorimetry.TubeColor
import com.agrilens.field.databinding.ActivityColorimetricBinding
import com.agrilens.field.databinding.ItemColorimetricParameterBinding
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.ConfigStore
import com.agrilens.field.storage.SessionStore
import com.agrilens.field.storage.TestStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mobile colorimetric test: preview the colorimeter box's USB/OTG webcam,
 * photograph each reagent tube (3 per parameter) and read every photo on the
 * phone against the downloaded colour charts — the same steps as the website,
 * with no signal needed. The test and its photos stay on the phone
 * ([TestStore]) until "Sync" uploads them as one mobile_colorimetric sample.
 */
class ColorimetricActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorimetricBinding
    private lateinit var webcam: UsbWebcam
    private lateinit var testStore: TestStore
    private lateinit var configStore: ConfigStore
    private lateinit var repository: ColorimetricRepository

    private lateinit var test: MobileTest
    private var config: StoredConfig? = null
    private var selectedKey: String? = null
    private var busy = false
    private var timer: CountDownTimer? = null
    private var captureJob: Job? = null

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

        testStore = TestStore(this)
        configStore = ConfigStore(this)
        repository = ColorimetricRepository(ApiClient.create(SessionStore(this)), configStore, testStore)

        val loaded = intent.getStringExtra(EXTRA_TEST_ID)?.let { testStore.load(it) }
        if (loaded == null) {
            Toast.makeText(this, R.string.colorimetric_load_error, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        test = loaded
        config = configStore.load()

        webcam = UsbWebcam(binding.cameraView) { state, device -> renderCameraState(state, device) }
        binding.captureZone.target = binding.cameraView

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.subtitle = test.farm_name

        binding.btnRescan.setOnClickListener {
            if (hasCameraPermission()) webcam.rescan() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        binding.btnRefreshCharts.setOnClickListener { refreshCharts() }
        binding.btnCapture.setOnClickListener { capture() }
        binding.btnCancel.setOnClickListener { cancel() }
        binding.btnReset.setOnClickListener { confirmReset() }
        binding.btnTimer.setOnClickListener { startTimer() }
        binding.btnAnalyze.setOnClickListener { if (test.isSynced) viewResult() else sync() }

        render()
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) {
            webcam.start()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
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

    // ── Rendering ────────────────────────────────────────────────────

    private fun render() {
        binding.textSampleName.text = test.sample_name ?: getString(R.string.colorimetric_title)
        binding.textSampleMeta.text = listOfNotNull(test.farm_name, test.farm_meta).joinToString(" — ")

        val views = MobileColorimetry.views(test)

        // Stay on the current parameter until it's done, then move to the next unfinished one.
        val current = views.find { it.key == selectedKey }
        if (current == null || !current.canCapture) {
            selectedKey = (views.firstOrNull { it.canCapture } ?: current ?: views.first()).key
        }

        binding.parameterContainer.removeAllViews()
        views.forEach { parameter ->
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
                parseColor(parameter.averageHex) ?: ContextCompat.getColor(this, R.color.text_muted)
            )
            // The stroke marks the selected card; the card's own check icon would cover the count.
            item.root.checkedIcon = null
            item.root.strokeWidth = if (parameter.key == selectedKey) dp(2) else 0
            item.root.setOnClickListener {
                selectedKey = parameter.key
                render()
            }
        }

        renderSelected(views)
        renderCharts()
        renderSync()
    }

    private fun selectedParameter(): ParameterView? =
        MobileColorimetry.views(test).find { it.key == selectedKey }

    private fun renderSelected(views: List<ParameterView>) {
        val parameter = views.find { it.key == selectedKey } ?: return

        binding.textSelectedTitle.text = when {
            parameter.complete -> getString(R.string.colorimetric_selected_done, parameter.label)
            parameter.canCapture -> getString(
                R.string.colorimetric_selected_capture,
                parameter.label,
                parameter.captures + 1,
                parameter.required,
            )
            else -> parameter.label
        }
        binding.textSelectedReagent.text = listOfNotNull(parameter.reagent, parameter.message).joinToString("\n")
        binding.btnReset.visibility = if (parameter.canReset) View.VISIBLE else View.GONE
        binding.btnTimer.isEnabled = parameter.canCapture
        if (timer == null) binding.textTimer.text = formatSeconds(reactionSeconds())

        updateCaptureEnabled()
    }

    private fun renderCharts() {
        val stored = config
        val missing = stored?.config?.missingCharts().orEmpty()
        binding.textChartsStatus.text = when {
            stored == null -> getString(R.string.charts_missing)
            missing.isNotEmpty() -> getString(R.string.charts_incomplete, missing.joinToString(", "))
            else -> getString(
                R.string.charts_status,
                formatServerDate(stored.config.updated_at),
                DateFormat.getMediumDateFormat(this).format(Date(stored.downloaded_at)) + ", " +
                    DateFormat.getTimeFormat(this).format(Date(stored.downloaded_at)),
            )
        }
        binding.textChartsStatus.setTextColor(
            ContextCompat.getColor(this, if (stored == null || missing.isNotEmpty()) R.color.agrilens_red else R.color.text_muted)
        )
        binding.btnRefreshCharts.visibility = if (test.isSynced) View.GONE else View.VISIBLE
    }

    private fun renderSync() {
        val complete = MobileColorimetry.isComplete(test)
        binding.btnAnalyze.setText(if (test.isSynced) R.string.colorimetric_view_result else R.string.colorimetric_analyze)
        binding.btnAnalyze.isEnabled = !busy && (test.isSynced || (complete && config != null))
        binding.textAnalyzeHint.text = when {
            test.isSynced -> getString(R.string.colorimetric_synced_status, test.synced_sample_id ?: 0L)
            complete -> test.sync_error ?: getString(R.string.colorimetric_sync_hint)
            else -> getString(R.string.colorimetric_analyze_hint)
        }
        binding.textAnalyzeHint.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    test.isSynced -> R.color.agrilens_green
                    complete && test.sync_error != null -> R.color.agrilens_red
                    else -> R.color.text_muted
                },
            )
        )
        binding.textAnalyzeHint.visibility = View.VISIBLE
    }

    private fun updateCaptureEnabled() {
        binding.btnCapture.isEnabled = !busy &&
            webcam.state == UsbWebcam.State.STREAMING &&
            config?.config?.missingCharts()?.isEmpty() == true &&
            selectedParameter()?.canCapture == true
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

    // ── Capture → read on the phone ──────────────────────────────────

    private fun capture() {
        val parameter = selectedParameter() ?: return
        val photo = parameter.nextPhoto ?: return
        val stored = config ?: return
        setBusy(true)
        hideError()

        captureJob = lifecycleScope.launch {
            val file = testStore.photoFile(test.uuid, photo)
            try {
                webcam.takePicture(file)
                val color = withContext(Dispatchers.Default) { readColor(file) }
                val message = MobileColorimetry.addCapture(test, parameter.key, color, photo, stored.config)
                test.config_version = stored.config.version
                test.sync_error = null
                withContext(Dispatchers.IO) { testStore.save(test) }

                timer?.cancel()
                timer = null
                binding.textTimer.setTextColor(ContextCompat.getColor(this@ColorimetricActivity, R.color.text_muted))
                Toast.makeText(this@ColorimetricActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                file.delete()
                Toast.makeText(this@ColorimetricActivity, R.string.colorimetric_capture_cancelled, Toast.LENGTH_SHORT).show()
                throw e
            } catch (e: Exception) {
                file.delete()
                showError(getString(R.string.colorimetric_capture_error, e.message ?: e.javaClass.simpleName))
            } finally {
                captureJob = null
                setBusy(false)
                render()
            }
        }
    }

    /** Decode the saved JPEG and pick the tube colour the way the server does. */
    private fun readColor(file: File): TubeColor {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IOException("The photo could not be decoded.")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return SnapshotColor.sampleTube(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Stops a capture in progress; otherwise leaves the screen (the test stays saved on the phone). */
    private fun cancel() {
        val job = captureJob
        if (job != null && job.isActive) {
            job.cancel()
        } else {
            finish()
        }
    }

    private fun confirmReset() {
        val parameter = selectedParameter() ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.colorimetric_reset_title, parameter.label))
            .setMessage(R.string.colorimetric_reset_message)
            .setPositiveButton(R.string.colorimetric_reset) { _, _ -> reset(parameter.key) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reset(key: String) {
        val photos = MobileColorimetry.reset(test, key)
        test.sync_error = null
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                testStore.deletePhotos(test.uuid, photos)
                testStore.save(test)
            }
            selectedKey = key
            render()
        }
    }

    // ── Colour charts ────────────────────────────────────────────────

    private fun refreshCharts() {
        binding.btnRefreshCharts.isEnabled = false
        binding.textChartsStatus.setText(R.string.charts_refreshing)
        lifecycleScope.launch {
            try {
                config = repository.downloadConfig()
                Toast.makeText(this@ColorimetricActivity, R.string.charts_refreshed, Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Toast.makeText(
                    this@ColorimetricActivity,
                    getString(R.string.charts_refresh_error, e.message ?: getString(R.string.login_error_network)),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                binding.btnRefreshCharts.isEnabled = true
                render()
            }
        }
    }

    // ── Sync → server ────────────────────────────────────────────────

    private fun sync() {
        val stored = config ?: return
        setBusy(true)
        hideError()
        binding.textAnalyzeHint.setText(R.string.colorimetric_syncing)

        lifecycleScope.launch {
            try {
                val result = repository.sync(test, stored.config)
                Toast.makeText(
                    this@ColorimetricActivity,
                    getString(R.string.colorimetric_sync_success, result.fertility_score?.toString() ?: "—", result.recommended_crop ?: "—"),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: IOException) {
                showError(e.message ?: getString(R.string.login_error_network))
            } finally {
                setBusy(false)
                render()
            }
        }
    }

    /** Open the server's result page for the synced sample. */
    private fun viewResult() {
        val sampleId = test.synced_sample_id ?: return
        setBusy(true)
        hideError()

        lifecycleScope.launch {
            try {
                val response = ApiClient.create(SessionStore(this@ColorimetricActivity)).getResultLink(sampleId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    startActivity(
                        Intent(this@ColorimetricActivity, ResultWebViewActivity::class.java).apply {
                            putExtra(ResultWebViewActivity.EXTRA_URL, body.url)
                            putExtra(ResultWebViewActivity.EXTRA_TITLE, test.sample_name)
                        }
                    )
                } else {
                    showError(getString(R.string.colorimetric_analyze_error))
                }
            } catch (e: IOException) {
                showError(getString(R.string.login_error_network))
            } finally {
                setBusy(false)
            }
        }
    }

    // ── Reaction timer (informational, like the web page's) ──────────

    private fun reactionSeconds(): Int = config?.config?.reaction_seconds?.takeIf { it > 0 } ?: DEFAULT_REACTION_SECONDS

    private fun startTimer() {
        val seconds = reactionSeconds()
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
        renderSync()
        updateCaptureEnabled()
    }

    private fun showError(message: String) {
        binding.textError.text = message
        binding.textError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.textError.visibility = View.GONE
    }

    private fun formatServerDate(iso: String?): String =
        iso?.takeIf { it.length >= 10 }?.let {
            runCatching {
                // The calendar date is enough here; java.time would need Android 8+.
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it.substring(0, 10))
                date?.let { d -> DateFormat.getMediumDateFormat(this).format(d) }
            }.getOrNull()
        } ?: getString(R.string.charts_status_unknown_date)

    private fun parseColor(hex: String?): Int? = hex?.let { runCatching { Color.parseColor(it) }.getOrNull() }

    private fun formatSeconds(seconds: Int): String =
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TEST_ID = "extra_test_id"
        private const val DEFAULT_REACTION_SECONDS = 480

        fun intent(context: Context, testId: String): Intent =
            Intent(context, ColorimetricActivity::class.java).putExtra(EXTRA_TEST_ID, testId)
    }
}
