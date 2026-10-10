package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityFarmListBinding
import com.agrilens.field.model.FarmSummary
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException

class FarmListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFarmListBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var adapter: FarmAdapter

    private var farmerId: Long = -1
    private var farmerName: String = ""
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFarmListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        farmerId = intent.getLongExtra(EXTRA_FARMER_ID, -1)
        if (farmerId < 0) {
            finish()
            return
        }
        farmerName = intent.getStringExtra(EXTRA_FARMER_NAME) ?: ""
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TEST_NOW

        sessionStore = SessionStore(this)

        binding.toolbar.title = farmerName.ifEmpty { getString(R.string.farms_title) }
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FarmAdapter { farm -> openFarm(farm) }
        binding.recyclerFarms.layoutManager = LinearLayoutManager(this)
        binding.recyclerFarms.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadFarms() }

        loadFarms()
    }

    private fun openFarm(farm: FarmSummary) {
        val farmMeta = "$farmerName — Barangay ${farm.barangay}"
        val targetIntent = if (mode == MODE_SCHEDULE) {
            Intent(this, ScheduleAssignmentActivity::class.java).apply {
                putExtra(ScheduleAssignmentActivity.EXTRA_FARM_ID, farm.id)
                putExtra(ScheduleAssignmentActivity.EXTRA_FARM_NAME, farm.name)
                putExtra(ScheduleAssignmentActivity.EXTRA_FARM_META, farmMeta)
            }
        } else {
            Intent(this, CreateAnalysisActivity::class.java).apply {
                putExtra(CreateAnalysisActivity.EXTRA_FARM_ID, farm.id)
                putExtra(CreateAnalysisActivity.EXTRA_FARM_NAME, farm.name)
                putExtra(CreateAnalysisActivity.EXTRA_FARM_META, farmMeta)
            }
        }
        startActivity(targetIntent)
    }

    private fun loadFarms() {
        val showPlainProgress = !binding.swipeRefresh.isRefreshing
        if (showPlainProgress) binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.listFarms(farmerId)

                if (response.isSuccessful && response.body() != null) {
                    val farms = response.body()!!.farms
                    adapter.submitList(farms)
                    binding.textEmpty.visibility = if (farms.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(this@FarmListActivity, getString(R.string.farms_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@FarmListActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    companion object {
        const val EXTRA_FARMER_ID = "extra_farmer_id"
        const val EXTRA_FARMER_NAME = "extra_farmer_name"
        const val EXTRA_MODE = "extra_mode"
        const val MODE_TEST_NOW = "test_now"
        const val MODE_SCHEDULE = "schedule"
    }
}
