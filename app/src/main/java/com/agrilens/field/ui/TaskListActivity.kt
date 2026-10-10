package com.agrilens.field.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.agrilens.field.R
import com.agrilens.field.databinding.ActivityTaskListBinding
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.SessionStore
import kotlinx.coroutines.launch
import java.io.IOException

/** Completed Analyses — "Pending Tasks" lives in AssignmentListActivity instead. */
class TaskListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskListBinding
    private lateinit var sessionStore: SessionStore
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionStore = SessionStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.textEmpty.text = getString(R.string.tasks_empty_completed)

        adapter = TaskAdapter { }
        binding.recyclerTasks.layoutManager = LinearLayoutManager(this)
        binding.recyclerTasks.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadTasks() }
    }

    override fun onResume() {
        super.onResume()
        loadTasks()
    }

    private fun loadTasks() {
        val showPlainProgress = !binding.swipeRefresh.isRefreshing
        if (showPlainProgress) binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val api = ApiClient.create(sessionStore)
                val response = api.listTasks("completed")

                if (response.isSuccessful && response.body() != null) {
                    val tasks = response.body()!!.tasks
                    adapter.submitList(tasks)
                    binding.textEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                } else {
                    Toast.makeText(this@TaskListActivity, getString(R.string.tasks_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Toast.makeText(this@TaskListActivity, getString(R.string.login_error_network), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }
}
