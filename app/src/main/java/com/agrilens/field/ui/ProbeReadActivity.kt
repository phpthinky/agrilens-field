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
import com.agrilens.field.databinding.ActivityProbeReadBinding
import com.agrilens.field.model.Assignment
import com.agrilens.field.model.CompleteAssignmentRequest
import com.agrilens.field.model.ProbeReading
import com.agrilens.field.network.ApiClient
import com.agrilens.field.serial.ProbeException
import com.agrilens.field.serial.ProbeReader
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class ProbeReadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProbeReadBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var probeReader: ProbeReader

    private var assignmentId: Long = -1
    private var lastReading: ProbeReading? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProbeReadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        assignmentId = intent.getLongExtra(EXTRA_ASSIGNMENT_ID, -1)
        if (assignmentId < 0) {
            finish()
            return
        }

        sessionStore = SessionStore(this)
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        probeReader = ProbeReader(usbManager)

        binding.btnConnect.setOnClickListener { performRead() }
        binding.btnRead.setOnClickListener { performRead() }
        binding.btnSubmit.setOnClickListener { submitReading() }
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

        loadAssignmentDetail()
    }

    /** Submit is allowed once N/P/K are filled — whether they came from a probe read or were typed in by hand. */
    private fun updateSubmitEnabled() {
        val n = binding.inputNitrogen.text?.toString()?.toDoubleOrNull()
        val p = binding.inputPhosphorus.text?.toString()?.toDoubleOrNull()
        val k = binding.inputPotassium.text?.toString()?.toDoubleOrNull()
        binding.btnSubmit.isEnabled = n != null && p != null && k != null
    }

    private fun loadAssignmentDetail() {
        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.getAssignment(assignmentId)
                if (response.isSuccessful && response.body() != null) {
                    bindAssignment(response.body()!!.assignment)
                } else {
                    Toast.makeText(this@ProbeReadActivity, "Could not load assignment detail.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@ProbeReadActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindAssignment(assignment: Assignment) {
        binding.textFarmName.text = assignment.farm.name
        binding.textFarmMeta.text = "${assignment.farm.farmer_name} — Barangay ${assignment.farm.barangay}"
    }

    private fun performRead() {
        setReadingInProgress(true)
        setStatus(getString(R.string.probe_status_connecting))
        binding.btnOpenSettings.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val reading = probeReader.readProbe(this@ProbeReadActivity)
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

    private fun submitReading() {
        val nitrogen = binding.inputNitrogen.text?.toString()?.toDoubleOrNull()
        val phosphorus = binding.inputPhosphorus.text?.toString()?.toDoubleOrNull()
        val potassium = binding.inputPotassium.text?.toString()?.toDoubleOrNull()
        val ph = binding.inputPh.text?.toString()?.toDoubleOrNull()
        val probeModel = binding.inputProbeModel.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }

        if (nitrogen == null || phosphorus == null || potassium == null) {
            showSubmitError("Nitrogen, Phosphorus, and Potassium are required.")
            return
        }

        hideSubmitError()
        setSubmitting(true)

        val request = CompleteAssignmentRequest(
            probe_id = "RS485-${System.currentTimeMillis()}",
            probe_model = probeModel,
            ph_level = ph,
            nitrogen_level = nitrogen,
            phosphorus_level = phosphorus,
            potassium_level = potassium,
        )

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.completeAssignment(assignmentId, request)

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    Toast.makeText(
                        this@ProbeReadActivity,
                        getString(R.string.probe_submit_success, body.fertility_score, body.recommended_crop ?: "—"),
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                } else {
                    showSubmitError("${getString(R.string.probe_submit_error)} (${response.code()})")
                }
            } catch (e: IOException) {
                showSubmitError(getString(R.string.login_error_network))
            } finally {
                setSubmitting(false)
            }
        }
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
        const val EXTRA_ASSIGNMENT_ID = "extra_assignment_id"
    }
}
