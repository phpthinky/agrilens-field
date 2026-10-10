package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityFarmerListBinding
import com.agrilens.field.model.FarmerSummary
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException

class FarmerListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFarmerListBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var adapter: FarmerAdapter

    private var barangayId: Long = -1
    private var allFarmers: List<FarmerSummary> = emptyList()
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFarmerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        barangayId = intent.getLongExtra(EXTRA_BARANGAY_ID, -1)
        if (barangayId < 0) {
            finish()
            return
        }

        mode = intent.getStringExtra(FarmListActivity.EXTRA_MODE) ?: FarmListActivity.MODE_TEST_NOW

        sessionStore = SessionStore(this)

        binding.toolbar.title = intent.getStringExtra(EXTRA_BARANGAY_NAME) ?: getString(R.string.farmers_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FarmerAdapter { farmer -> openFarmer(farmer) }
        binding.recyclerFarmers.layoutManager = LinearLayoutManager(this)
        binding.recyclerFarmers.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadFarmers() }

        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        loadFarmers()
    }

    private fun openFarmer(farmer: FarmerSummary) {
        val targetIntent = Intent(this, FarmListActivity::class.java)
        targetIntent.putExtra(FarmListActivity.EXTRA_FARMER_ID, farmer.id)
        targetIntent.putExtra(FarmListActivity.EXTRA_FARMER_NAME, farmer.full_name)
        targetIntent.putExtra(FarmListActivity.EXTRA_MODE, mode)
        startActivity(targetIntent)
    }

    private fun applyFilter(query: String) {
        val filtered = if (query.isBlank()) {
            allFarmers
        } else {
            allFarmers.filter { it.full_name.contains(query, ignoreCase = true) }
        }
        adapter.submitList(filtered)
        binding.textEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun loadFarmers() {
        val showPlainProgress = !binding.swipeRefresh.isRefreshing
        if (showPlainProgress) binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.listFarmers(barangayId)

                if (response.isSuccessful && response.body() != null) {
                    allFarmers = response.body()!!.farmers
                    applyFilter(binding.inputSearch.text?.toString().orEmpty())
                } else {
                    Toast.makeText(this@FarmerListActivity, getString(R.string.farmers_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@FarmerListActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    companion object {
        const val EXTRA_BARANGAY_ID = "extra_barangay_id"
        const val EXTRA_BARANGAY_NAME = "extra_barangay_name"
    }
}
