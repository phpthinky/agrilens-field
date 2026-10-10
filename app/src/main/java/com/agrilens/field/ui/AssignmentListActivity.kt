package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityAssignmentListBinding
import com.agrilens.field.model.Assignment
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException

class AssignmentListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssignmentListBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var adapter: AssignmentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssignmentListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)

        binding.toolbar.title = getString(R.string.assignments_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_assignments)
        binding.toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }

        adapter = AssignmentAdapter { assignment -> openAssignment(assignment) }
        binding.recyclerAssignments.layoutManager = LinearLayoutManager(this)
        binding.recyclerAssignments.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadAssignments() }
    }

    override fun onResume() {
        super.onResume()
        loadAssignments()
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_logout) {
            logout()
            return true
        }
        return false
    }

    private fun openAssignment(assignment: Assignment) {
        val intent = Intent(this, AssignmentDetailActivity::class.java)
        intent.putExtra(AssignmentDetailActivity.EXTRA_ASSIGNMENT_ID, assignment.id)
        startActivity(intent)
    }

    private fun loadAssignments() {
        // If the user pulled-to-refresh, SwipeRefreshLayout is already showing its own
        // spinner; only show the plain progress bar for the initial/onResume load.
        val showPlainProgress = !binding.swipeRefresh.isRefreshing
        if (showPlainProgress) binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.listAssignments()

                if (response.isSuccessful && response.body() != null) {
                    val assignments = response.body()!!.assignments
                    adapter.submitList(assignments)
                    binding.textEmpty.visibility = if (assignments.isEmpty()) View.VISIBLE else View.GONE
                } else if (response.code() == 401) {
                    logout()
                } else {
                    Toast.makeText(this@AssignmentListActivity, getString(R.string.assignments_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@AssignmentListActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            runCatching { ApiClient.create(sessionStore).logout() }
            sessionStore.clear()
            startActivity(Intent(this@AssignmentListActivity, LoginActivity::class.java))
            finish()
        }
    }
}
