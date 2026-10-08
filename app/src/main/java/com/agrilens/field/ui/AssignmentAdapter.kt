package com.agrilens.field.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.agrilens.field.databinding.ItemAssignmentBinding
import com.agrilens.field.model.Assignment
import java.text.SimpleDateFormat
import java.util.Locale

class AssignmentAdapter(
    private val onClick: (Assignment) -> Unit,
) : RecyclerView.Adapter<AssignmentAdapter.ViewHolder>() {

    private val items = mutableListOf<Assignment>()

    private val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outputFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    fun submitList(newItems: List<Assignment>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAssignmentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAssignmentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(assignment: Assignment) {
            binding.textFarmName.text = assignment.farm.name
            binding.textFarmerName.text = "Farmer: ${assignment.farm.farmer_name}"
            binding.textBarangay.text = "Barangay ${assignment.farm.barangay}"
            binding.textDate.text = formatDate(assignment.scheduled_date)
            binding.root.setOnClickListener { onClick(assignment) }
        }

        private fun formatDate(raw: String): String {
            val parsed = runCatching { inputFormat.parse(raw) }.getOrNull()
            return parsed?.let { outputFormat.format(it) } ?: raw
        }
    }
}
