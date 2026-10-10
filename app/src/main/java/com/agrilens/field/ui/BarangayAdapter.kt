package com.agrilens.field.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.agrilens.field.databinding.ItemBarangayBinding
import com.agrilens.field.model.BarangaySummary

class BarangayAdapter(
    private val onClick: (BarangaySummary) -> Unit,
) : RecyclerView.Adapter<BarangayAdapter.ViewHolder>() {

    private val items = mutableListOf<BarangaySummary>()

    fun submitList(newItems: List<BarangaySummary>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBarangayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemBarangayBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(barangay: BarangaySummary) {
            binding.textName.text = barangay.name
            binding.textCounts.text = "${barangay.farmers_count} farmers · ${barangay.farms_count} farms"
            binding.textTaskCounts.text =
                "${barangay.pending_tasks_count} pending · ${barangay.completed_count} completed"
            binding.root.setOnClickListener { onClick(barangay) }
        }
    }
}
