package com.agrilens.field.ui

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityCreateAnalysisBinding
import com.agrilens.field.model.CreateAnalysisRequest
import com.agrilens.field.model.CreateColorimetricSampleRequest
import com.agrilens.field.model.ProbeReading
import com.agrilens.field.network.ApiClient
import com.agrilens.field.serial.ProbeException
import com.agrilens.field.serial.ProbeReader
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class CreateAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateAnalysisBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var probeReader: ProbeReader

    private var farmId: Long = -1
    private var lastReading: ProbeReading? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        farmId = intent.getLongExtra(EXTRA_FARM_ID, -1)
        if (farmId < 0) {
            finish()
            return
        }

        sessionStore = SessionStore(this)
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        probeReader = ProbeReader(usbManager)

        binding.textFarmName.text = intent.getStringExtra(EXTRA_FARM_NAME) ?: ""
        binding.textFarmMeta.text = intent.getStringExtra(EXTRA_FARM_META) ?: ""

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.toggleMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val colorimetric = checkedId == R.id.btnMethodColorimetric
            binding.groupProbeControls.visibility =
                if (checkedId == R.id.btnMethodProbe) View.VISIBLE else View.GONE
            binding.groupValueEntry.visibility = if (colorimetric) View.GONE else View.VISIBLE
            binding.textColorimetricInfo.visibility = if (colorimetric) View.VISIBLE else View.GONE
            binding.btnSubmit.setText(if (colorimetric) R.string.colorimetric_start else R.string.analysis_submit)
            hideSubmitError()
            updateSubmitEnabled()
        }

        binding.btnConnect.setOnClickListener { performRead() }
        binding.btnRead.setOnClickListener { performRead() }
        binding.btnSubmit.setOnClickListener { submit() }
        binding.btnOpenSettings.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }

        val requiredFieldWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSubmitEnabled()
        }
        binding.inputNitrogen.addTextChangedListener(requiredFieldWatcher)
        binding.inputPhosphorus.addTextChangedListener(requiredFieldWatcher)
        binding.inputPotassium.addTextChangedListener(requiredFieldWatcher)
    }

    private fun isProbeMode(): Boolean = binding.toggleMethod.checkedButtonId == R.id.btnMethodProbe

    private fun isColorimetricMode(): Boolean =
        binding.toggleMethod.checkedButtonId == R.id.btnMethodColorimetric

    private fun updateSubmitEnabled() {
        if (isColorimetricMode()) {
            binding.btnSubmit.isEnabled = true
            return
        }
        val n = binding.inputNitrogen.text?.toString()?.toDoubleOrNull()
        val p = binding.inputPhosphorus.text?.toString()?.toDoubleOrNull()
        val k = binding.inputPotassium.text?.toString()?.toDoubleOrNull()
        binding.btnSubmit.isEnabled = n != null && p != null && k != null
    }

    private fun performRead() {
        setReadingInProgress(true)
        setStatus(getString(R.string.probe_status_connecting))
        binding.btnOpenSettings.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val reading = probeReader.readProbe(this@CreateAnalysisActivity)
                lastReading = reading
                applyReading(reading)
                updateSubmitEnabled()

                setStatus(
                    if (reading.hasCoreNpk()) {
                        getString(R.string.probe_status_connected)
                    } else {
                        "Connected, but the probe didn't report N/P/K. Try again, or enter values manually below."
                    }
                )
                binding.btnRead.isEnabled = true
            } catch (e: ProbeException) {
                setStatus(messageFor(e))
                binding.btnOpenSettings.visibility = if (e is ProbeException.NoDeviceFound) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                setStatus(e.message ?: "Unexpected error reading the probe.")
            } finally {
                setReadingInProgress(false)
            }
        }
    }

    private fun messageFor(e: ProbeException): String = when (e) {
        is ProbeException.NoDeviceFound -> getString(R.string.probe_status_no_device)
        is ProbeException.PermissionDenied -> getString(R.string.probe_status_permission_denied)
        else -> e.message ?: "Could not read the probe."
    }

    private fun applyReading(reading: ProbeReading) {
        reading.ph?.let { binding.inputPh.setText(formatNumber(it)) }
        reading.nitrogen?.let { binding.inputNitrogen.setText(formatNumber(it)) }
        reading.phosphorus?.let { binding.inputPhosphorus.setText(formatNumber(it)) }
        reading.potassium?.let { binding.inputPotassium.setText(formatNumber(it)) }
    }

    private fun formatNumber(value: Double): String {
        return if (value == Math.floor(value)) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
        }
    }

    private fun submit() {
        if (isColorimetricMode()) {
            startColorimetric()
            return
        }

        val nitrogen = binding.inputNitrogen.text?.toString()?.toDoubleOrNull()
        val phosphorus = binding.inputPhosphorus.text?.toString()?.toDoubleOrNull()
        val potassium = binding.inputPotassium.text?.toString()?.toDoubleOrNull()
        val ph = binding.inputPh.text?.toString()?.toDoubleOrNull()
        val probeModel = binding.inputProbeModel.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
        val sampleName = binding.inputSampleName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }

        if (nitrogen == null || phosphorus == null || potassium == null) {
            showSubmitError("Nitrogen, Phosphorus, and Potassium are required.")
            return
        }

        hideSubmitError()
        setSubmitting(true)

        val probeMode = isProbeMode()
        val request = CreateAnalysisRequest(
            analysis_type = if (probeMode) "probe" else "manual",
            sample_name = sampleName,
            probe_id = if (probeMode) "RS485-${System.currentTimeMillis()}" else null,
            probe_model = probeModel,
            ph_level = ph,
            nitrogen_level = nitrogen,
            phosphorus_level = phosphorus,
            potassium_level = potassium,
        )

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.createAnalysis(farmId, request)

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    Toast.makeText(
                        this@CreateAnalysisActivity,
                        getString(R.string.analysis_submit_success, body.fertility_score, body.recommended_crop ?: "—"),
                        Toast.LENGTH_LONG,
                    ).show()
                    returnToDashboard()
                } else {
                    val serverMessage = runCatching {
                        response.errorBody()?.string()
                            ?.let { com.google.gson.Gson().fromJson(it, Map::class.java)["message"] as? String }
                    }.getOrNull()
                    showSubmitError(serverMessage ?: "${getString(R.string.analysis_submit_error)} (${response.code()})")
                }
            } catch (e: IOException) {
                showSubmitError(getString(R.string.login_error_network))
            } finally {
                setSubmitting(false)
            }
        }
    }

    /**
     * Creates the colorimetric sample on the server, then hands over to the
     * webcam capture screen. No values are entered here — the server derives
     * them from the uploaded photos.
     */
    private fun startColorimetric() {
        val sampleName = binding.inputSampleName.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }

        hideSubmitError()
        setSubmitting(true)

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.createColorimetricSample(farmId, CreateColorimetricSampleRequest(sampleName))
                val body = response.body()

                if (response.isSuccessful && body != null) {
                    val farmName = intent.getStringExtra(EXTRA_FARM_NAME)
                    val colorimetricIntent = Intent(this@CreateAnalysisActivity, ColorimetricActivity::class.java).apply {
                        putExtra(ColorimetricActivity.EXTRA_SAMPLE_ID, body.sample.id)
                        putExtra(ColorimetricActivity.EXTRA_TITLE, farmName)
                    }
                    startActivity(colorimetricIntent)
                    finish()
                } else {
                    val serverMessage = runCatching {
                        response.errorBody()?.string()
                            ?.let { com.google.gson.Gson().fromJson(it, Map::class.java)["message"] as? String }
                    }.getOrNull()
                    showSubmitError(serverMessage ?: "${getString(R.string.colorimetric_start_error)} (${response.code()})")
                }
            } catch (e: IOException) {
                showSubmitError(getString(R.string.login_error_network))
            } finally {
                setSubmitting(false)
            }
        }
    }

    private fun returnToDashboard() {
        val intent = Intent(this, DashboardActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun setStatus(message: String) {
        binding.textProbeStatus.text = message
    }

    private fun setReadingInProgress(inProgress: Boolean) {
        binding.progressProbe.visibility = if (inProgress) View.VISIBLE else View.GONE
        binding.btnConnect.isEnabled = !inProgress
        binding.btnRead.isEnabled = !inProgress && lastReading != null
    }

    private fun setSubmitting(submitting: Boolean) {
        binding.progressSubmit.visibility = if (submitting) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !submitting
    }

    private fun showSubmitError(message: String) {
        binding.textSubmitError.text = message
        binding.textSubmitError.visibility = View.VISIBLE
    }

    private fun hideSubmitError() {
        binding.textSubmitError.visibility = View.GONE
    }

    companion object {
        const val EXTRA_FARM_ID = "extra_farm_id"
        const val EXTRA_FARM_NAME = "extra_farm_name"
        const val EXTRA_FARM_META = "extra_farm_meta"
    }
}
