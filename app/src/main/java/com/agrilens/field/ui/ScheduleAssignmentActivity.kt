package com.agrilens.field.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityScheduleAssignmentBinding
import com.agrilens.field.model.ScheduleAssignmentRequest
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleAssignmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleAssignmentBinding
    private lateinit var sessionStore: SessionStore

    private var farmId: Long = -1
    private var selectedDate: Calendar? = null

    private val requestFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleAssignmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        farmId = intent.getLongExtra(EXTRA_FARM_ID, -1)
        if (farmId < 0) {
            finish()
            return
        }

        sessionStore = SessionStore(this)

        binding.textFarmName.text = intent.getStringExtra(EXTRA_FARM_NAME) ?: ""
        binding.textFarmMeta.text = intent.getStringExtra(EXTRA_FARM_META) ?: ""

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.inputDate.setOnClickListener { showDatePicker() }
        binding.btnSubmit.setOnClickListener { submit() }
    }

    private fun showDatePicker() {
        val today = Calendar.getInstance()
        val initial = selectedDate ?: today

        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance()
                picked.set(year, month, dayOfMonth)
                selectedDate = picked
                binding.inputDate.setText(displayFormat.format(picked.time))
                binding.btnSubmit.isEnabled = true
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH),
        )
        // Server enforces the real Asia/Manila calendar-date rule; this just keeps
        // the technician from picking an obviously-past date on their device.
        dialog.datePicker.minDate = today.timeInMillis - 1000
        dialog.show()
    }

    private fun submit() {
        val date = selectedDate ?: return
        hideSubmitError()
        setSubmitting(true)

        val request = ScheduleAssignmentRequest(
            scheduled_date = requestFormat.format(date.time),
            notes = binding.inputNotes.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() },
        )

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.scheduleAssignment(farmId, request)

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    Toast.makeText(
                        this@ScheduleAssignmentActivity,
                        getString(R.string.schedule_submit_success, displayFormat.format(date.time)),
                        Toast.LENGTH_LONG,
                    ).show()
                    returnToDashboard()
                } else {
                    val serverMessage = runCatching {
                        response.errorBody()?.string()
                            ?.let { com.google.gson.Gson().fromJson(it, Map::class.java)["message"] as? String }
                    }.getOrNull()
                    showSubmitError(serverMessage ?: getString(R.string.schedule_submit_error))
                }
            } catch (e: IOException) {
                showSubmitError(getString(R.string.login_error_network))
            } finally {
                setSubmitting(false)
            }
        }
    }

    private fun returnToDashboard() {
        val intent = android.content.Intent(this, DashboardActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
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
