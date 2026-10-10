package com.agrilens.field.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.agrilens.field.databinding.ItemFarmBinding
import com.agrilens.field.model.FarmSummary

class FarmAdapter(
    private val onClick: (FarmSummary) -> Unit,
) : RecyclerView.Adapter<FarmAdapter.ViewHolder>() {

    private val items = mutableListOf<FarmSummary>()

    fun submitList(newItems: List<FarmSummary>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemFarmBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(farm: FarmSummary) {
            binding.textName.text = farm.name
            binding.textMeta.text = listOfNotNull(
                farm.farm_type,
                farm.area_hectares?.let { "$it ha" },
            ).joinToString(" · ").ifEmpty { "—" }
            binding.textAddress.text = farm.address ?: farm.barangay
            binding.root.setOnClickListener { onClick(farm) }
        }
    }
}
