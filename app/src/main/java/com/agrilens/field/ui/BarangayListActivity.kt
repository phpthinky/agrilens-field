package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityBarangayListBinding
import com.agrilens.field.model.BarangaySummary
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException

class BarangayListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBarangayListBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var adapter: BarangayAdapter
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBarangayListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(FarmListActivity.EXTRA_MODE) ?: FarmListActivity.MODE_TEST_NOW

        sessionStore = SessionStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = BarangayAdapter { barangay -> openBarangay(barangay) }
        binding.recyclerBarangays.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerBarangays.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadBarangays() }

        loadBarangays()
    }

    private fun openBarangay(barangay: BarangaySummary) {
        val targetIntent = Intent(this, FarmerListActivity::class.java)
        targetIntent.putExtra(FarmerListActivity.EXTRA_BARANGAY_ID, barangay.id)
        targetIntent.putExtra(FarmerListActivity.EXTRA_BARANGAY_NAME, barangay.name)
        targetIntent.putExtra(FarmListActivity.EXTRA_MODE, mode)
        startActivity(targetIntent)
    }

    private fun loadBarangays() {
        val showPlainProgress = !binding.swipeRefresh.isRefreshing
        if (showPlainProgress) binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.listBarangays()

                if (response.isSuccessful && response.body() != null) {
                    val barangays = response.body()!!.barangays
                    adapter.submitList(barangays)
                    binding.textEmpty.visibility = if (barangays.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(this@BarangayListActivity, getString(R.string.barangays_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@BarangayListActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }
}
