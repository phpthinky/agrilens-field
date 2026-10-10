package com.agrilens.field.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.agrilens.field.databinding.ItemTaskBinding
import com.agrilens.field.model.TaskItem
import java.text.SimpleDateFormat
import java.util.Locale

class TaskAdapter(
    private val onClick: (TaskItem) -> Unit,
) : RecyclerView.Adapter<TaskAdapter.ViewHolder>() {

    private val items = mutableListOf<TaskItem>()

    private val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outputFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    fun submitList(newItems: List<TaskItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemTaskBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(task: TaskItem) {
            binding.textFarmName.text = task.farm_name ?: "—"
            binding.textMeta.text = "${task.farmer_name ?: "—"} — Barangay ${task.barangay ?: "—"}"

            binding.textStatus.text = if (task.status == "completed") {
                val score = task.fertility_score?.let { " · Fertility $it%" } ?: ""
                "Completed ${formatDate(task.date)}$score"
            } else {
                "Scheduled ${formatDate(task.scheduled_date)}"
            }

            binding.root.setOnClickListener { onClick(task) }
        }

        private fun formatDate(raw: String?): String {
            if (raw == null) return "—"
            val parsed = runCatching { inputFormat.parse(raw) }.getOrNull()
            return parsed?.let { outputFormat.format(it) } ?: raw
        }
    }
}
