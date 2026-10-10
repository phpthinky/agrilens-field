package com.agrilens.field.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.agrilens.field.databinding.ItemFarmerBinding
import com.agrilens.field.model.FarmerSummary

class FarmerAdapter(
    private val onClick: (FarmerSummary) -> Unit,
) : RecyclerView.Adapter<FarmerAdapter.ViewHolder>() {

    private val items = mutableListOf<FarmerSummary>()

    fun submitList(newItems: List<FarmerSummary>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFarmerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemFarmerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(farmer: FarmerSummary) {
            binding.textName.text = farmer.full_name
            binding.textCounts.text =
                "${farmer.farms_count} farms · ${farmer.pending_tasks_count} pending · ${farmer.completed_count} completed"
            binding.root.setOnClickListener { onClick(farmer) }
        }
    }
}
