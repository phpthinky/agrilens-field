package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityAssignmentDetailBinding
import com.agrilens.field.model.Assignment
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import com.google.gson.Gson

class AssignmentDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssignmentDetailBinding
    private lateinit var sessionStore: SessionStore

    private var assignmentId: Long = -1

    private val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outputFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssignmentDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        assignmentId = intent.getLongExtra(EXTRA_ASSIGNMENT_ID, -1)
        if (assignmentId < 0) {
            finish()
            return
        }

        sessionStore = SessionStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnBegin.setOnClickListener { beginTesting() }
    }

    private fun loadAssignment() {
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.getAssignment(assignmentId)

                if (response.isSuccessful && response.body() != null) {
                    bind(response.body()!!.assignment)
                } else {
                    Toast.makeText(this@AssignmentDetailActivity, getString(R.string.assignment_detail_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@AssignmentDetailActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun bind(assignment: Assignment) {
        binding.textFarmName.text = assignment.farm.name
        binding.textFarmMeta.text = "${assignment.farm.farmer_name} — Barangay ${assignment.farm.barangay}"
        binding.textScheduledDate.text = "Scheduled: ${formatDate(assignment.scheduled_date)}"

        binding.textStatus.text = assignment.status.replace('_', ' ').uppercase(Locale.US)
        val colorRes = when (assignment.status) {
            "completed" -> R.color.agrilens_green
            "in_progress" -> R.color.agrilens_amber
            "overdue", "cancelled" -> R.color.agrilens_red
            else -> R.color.text_muted
        }
        binding.textStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, colorRes)
        )

        if (assignment.notes.isNullOrBlank()) {
            binding.textNotes.visibility = View.GONE
        } else {
            binding.textNotes.visibility = View.VISIBLE
            binding.textNotes.text = "Notes: ${assignment.notes}"
        }

        if (assignment.lock_message != null) {
            binding.cardLockMessage.visibility = View.VISIBLE
            binding.textLockMessage.text = assignment.lock_message
        } else {
            binding.cardLockMessage.visibility = View.GONE
        }

        binding.btnBegin.isEnabled = assignment.status != "completed" &&
            assignment.status != "cancelled" &&
            assignment.can_start
        binding.btnBegin.visibility = if (assignment.status == "completed" || assignment.status == "cancelled") {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun formatDate(raw: String): String {
        val parsed = runCatching { inputFormat.parse(raw) }.getOrNull()
        return parsed?.let { outputFormat.format(it) } ?: raw
    }

    private fun beginTesting() {
        binding.btnBegin.isEnabled = false

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.startAssignment(assignmentId)

                if (response.isSuccessful) {
                    val intent = Intent(this@AssignmentDetailActivity, ProbeReadActivity::class.java)
                    intent.putExtra(ProbeReadActivity.EXTRA_ASSIGNMENT_ID, assignmentId)
                    startActivity(intent)
                } else {
                    val message = runCatching {
                        response.errorBody()?.string()?.let { Gson().fromJson(it, Map::class.java)["message"] as? String }
                    }.getOrNull() ?: getString(R.string.assignment_start_error)
                    Toast.makeText(this@AssignmentDetailActivity, message, Toast.LENGTH_LONG).show()
                    loadAssignment()
                }
            } catch (e: IOException) {
                Toast.makeText(this@AssignmentDetailActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
                binding.btnBegin.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from ProbeReadActivity after completing (or cancelling) the
        // test should refresh the status badge/lock state shown here.
        if (assignmentId >= 0) loadAssignment()
    }

    companion object {
        const val EXTRA_ASSIGNMENT_ID = "extra_assignment_id"
    }
}
