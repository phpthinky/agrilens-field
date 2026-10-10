package com.agrilens.field.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrilens.field.databinding.ActivityNewAnalysisChoiceBinding

class NewAnalysisChoiceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewAnalysisChoiceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewAnalysisChoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.cardTestNow.setOnClickListener { openBarangays(FarmListActivity.MODE_TEST_NOW) }
        binding.cardSchedule.setOnClickListener { openBarangays(FarmListActivity.MODE_SCHEDULE) }
    }

    private fun openBarangays(mode: String) {
        val intent = Intent(this, BarangayListActivity::class.java)
        intent.putExtra(FarmListActivity.EXTRA_MODE, mode)
        startActivity(intent)
    }
}
