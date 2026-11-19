package com.agrilens.field.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.agrilens.field.R
import com.agrilens.field.colorimetry.ColorimetricRepository
import com.agrilens.field.colorimetry.MobileColorimetry
import com.agrilens.field.colorimetry.MobileTest
import com.agrilens.field.databinding.ActivityMobileTestsBinding
import com.agrilens.field.databinding.ItemMobileTestBinding
import com.agrilens.field.network.ApiClient
import com.agrilens.field.storage.ConfigStore
import com.agrilens.field.storage.SessionStore
import com.agrilens.field.storage.TestStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Date

/**
 * Mobile colorimetric tests saved on this phone: resume one in progress,
 * sync finished ones, or delete. Long-press a test to delete it.
 */
class MobileTestsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMobileTestsBinding
    private lateinit var testStore: TestStore
    private lateinit var configStore: ConfigStore
    private lateinit var repository: ColorimetricRepository
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMobileTestsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        testStore = TestStore(this)
        configStore = ConfigStore(this)
        repository = ColorimetricRepository(ApiClient.create(SessionStore(this)), configStore, testStore)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnSyncAll.setOnClickListener { syncAll() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val tests = testStore.list()
        val stored = configStore.load()

        binding.textChartsStatus.text = stored?.let {
            getString(
                R.string.charts_status,
                it.config.updated_at?.take(10) ?: getString(R.string.charts_status_unknown_date),
                formatTime(it.downloaded_at),
            )
        } ?: getString(R.string.charts_missing)

        binding.containerTests.removeAllViews()
        binding.textEmpty.visibility = if (tests.isEmpty()) View.VISIBLE else View.GONE

        tests.forEach { test ->
            val item = ItemMobileTestBinding.inflate(layoutInflater, binding.containerTests, true)
            item.textTitle.text = test.sample_name ?: getString(R.string.colorimetric_title)
            item.textMeta.text = listOfNotNull(test.farm_name, formatTime(test.created_at)).joinToString(" · ")

            val (state, color) = when {
                test.isSynced -> getString(R.string.mobile_tests_state_synced, test.synced_sample_id ?: 0L) to R.color.agrilens_green
                MobileColorimetry.isComplete(test) -> getString(R.string.mobile_tests_state_ready) to R.color.agrilens_amber
                else -> getString(R.string.mobile_tests_state_progress, MobileColorimetry.capturesDone(test)) to R.color.text_muted
            }
            item.textState.text = state
            item.textState.setTextColor(ContextCompat.getColor(this, color))
            item.textError.text = test.sync_error
            item.textError.visibility = if (!test.isSynced && !test.sync_error.isNullOrBlank()) View.VISIBLE else View.GONE

            item.root.setOnClickListener { startActivity(ColorimetricActivity.intent(this, test.uuid)) }
            item.root.setOnLongClickListener {
                confirmDelete(test)
                true
            }
        }

        val ready = tests.count { !it.isSynced && MobileColorimetry.isComplete(it) }
        binding.btnSyncAll.isEnabled = !syncing && ready > 0 && stored != null
    }

    private fun confirmDelete(test: MobileTest) {
        AlertDialog.Builder(this)
            .setTitle(R.string.mobile_tests_delete_title)
            .setMessage(R.string.mobile_tests_delete_message)
            .setPositiveButton(R.string.mobile_tests_delete) { _, _ ->
                testStore.delete(test.uuid)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun syncAll() {
        val config = configStore.load()?.config ?: return
        val ready = testStore.list().filter { !it.isSynced && MobileColorimetry.isComplete(it) }
        setSyncing(true)

        lifecycleScope.launch {
            var done = 0
            for (test in ready) {
                try {
                    repository.sync(test, config)
                    done++
                } catch (e: IOException) {
                    // Recorded on the test (sync_error); keep going with the others.
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MobileTestsActivity,
                    getString(R.string.mobile_tests_sync_all_done, done, ready.size),
                    Toast.LENGTH_LONG,
                ).show()
                setSyncing(false)
            }
        }
    }

    private fun setSyncing(value: Boolean) {
        syncing = value
        binding.progressSync.visibility = if (value) View.VISIBLE else View.GONE
        render()
    }

    private fun formatTime(millis: Long): String =
        DateFormat.getMediumDateFormat(this).format(Date(millis)) + ", " + DateFormat.getTimeFormat(this).format(Date(millis))
}
