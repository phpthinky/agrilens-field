package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityDashboardBinding
import com.agrilens.field.databinding.ItemRecentActivityBinding
import com.agrilens.field.model.RecentActivity
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var sessionStore: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)

        binding.toolbar.inflateMenu(R.menu.menu_dashboard)
        binding.toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }

        binding.btnNewAnalysis.setOnClickListener {
            startActivity(Intent(this, NewAnalysisChoiceActivity::class.java))
        }
        binding.btnPendingTasks.setOnClickListener {
            startActivity(Intent(this, AssignmentListActivity::class.java))
        }
        binding.cardCompleted.setOnClickListener {
            startActivity(Intent(this, TaskListActivity::class.java))
        }

        binding.swipeRefresh.setOnRefreshListener { loadDashboard() }
    }

    override fun onResume() {
        super.onResume()
        loadDashboard()
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_logout) {
            logout()
            return true
        }
        return false
    }

    private fun loadDashboard() {
        val showPlainProgress = !binding.swipeRefresh.isRefreshing
        if (showPlainProgress) binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.getDashboard()

                if (response.isSuccessful && response.body() != null) {
                    bind(response.body()!!)
                } else if (response.code() == 401) {
                    logout()
                } else {
                    Toast.makeText(this@DashboardActivity, getString(R.string.dashboard_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@DashboardActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun bind(data: com.agrilens.field.model.DashboardResponse) {
        binding.textStatBarangays.text = data.assigned_barangays_count.toString()
        binding.textStatFarmers.text = data.registered_farmers_count.toString()
        binding.textStatPending.text = data.pending_tasks_count.toString()
        binding.textStatToday.text = data.today_scheduled_count.toString()
        binding.textStatOverdue.text = data.overdue_count.toString()
        binding.textStatCompleted.text = data.completed_analyses_count.toString()

        binding.containerRecentActivity.removeAllViews()
        for (activity in data.recent_activity) {
            binding.containerRecentActivity.addView(buildRecentActivityRow(activity))
        }
        binding.textRecentActivityEmpty.visibility = if (data.recent_activity.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildRecentActivityRow(activity: RecentActivity): View {
        val rowBinding = ItemRecentActivityBinding.inflate(LayoutInflater.from(this), binding.containerRecentActivity, false)
        rowBinding.textSampleName.text = activity.sample_name ?: "Soil Analysis"
        val score = activity.fertility_score?.let { " · Fertility $it%" } ?: ""
        rowBinding.textMeta.text = "${activity.farmer_name ?: "—"} — ${activity.farm_name ?: "—"}$score"
        return rowBinding.root
    }

    private fun logout() {
        lifecycleScope.launch {
            runCatching { ApiClient.create(sessionStore).logout() }
            sessionStore.clear()
            startActivity(Intent(this@DashboardActivity, LoginActivity::class.java))
            finish()
        }
    }
}
