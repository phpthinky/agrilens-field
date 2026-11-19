package com.agrilens.field.colorimetry

import com.agrilens.field.model.MobileColorimetricSyncResponse
import com.agrilens.field.network.ApiService
import com.agrilens.field.storage.ConfigStore
import com.agrilens.field.storage.TestStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Downloads the colour charts and uploads finished tests. */
class ColorimetricRepository(
    private val api: ApiService,
    private val configStore: ConfigStore,
    private val testStore: TestStore,
) {

    private val gson = Gson()

    /** Download the charts and save them on the phone. Throws IOException when offline or the server refuses. */
    suspend fun downloadConfig(): StoredConfig {
        val response = api.getColorimetricConfig()
        val config = response.body()
        if (!response.isSuccessful || config == null || config.ph_charts == null || config.npk_charts == null) {
            throw IOException(serverMessage(response.errorBody()?.string()) ?: "Could not download the colour charts (${response.code()}).")
        }
        return withContext(Dispatchers.IO) { configStore.save(config) }
    }

    /** Fresh charts when the server can be reached, otherwise the copy already on the phone (null if none). */
    suspend fun freshOrSavedConfig(): StoredConfig? =
        runCatching { downloadConfig() }.getOrNull() ?: withContext(Dispatchers.IO) { configStore.load() }

    /** Upload a finished test with its photos. Marks it synced on success; a retry is safe (client_uuid). */
    suspend fun sync(test: MobileTest, config: ColorimetricConfig): MobileColorimetricSyncResponse {
        check(MobileColorimetry.isComplete(test)) { "Finish all pH, N, P and K captures before syncing." }

        val levels = MobileColorimetry.levels(test, config)
        val ph = test.ph
        val payload = mapOf(
            "client_uuid" to test.uuid,
            "sample_name" to test.sample_name,
            "config_version" to test.config_version,
            "levels" to levels,
            "ph" to mapOf(
                "step1" to ph.step1.map(::readingJson),
                "step2_solution" to ph.step2_solution.takeIf { ph.step2.isNotEmpty() },
                "step2" to ph.step2.map(::readingJson),
                "final_ph" to ph.final_ph,
            ),
            "nutrients" to NUTRIENTS.associateWith { key -> test.nutrient(key).map(::readingJson) },
        )

        val photos = withContext(Dispatchers.IO) {
            (ph.step1 + ph.step2 + NUTRIENTS.flatMap { test.nutrient(it) }).mapNotNull { reading ->
                val file = testStore.photoFile(test.uuid, reading.photo)
                if (!file.exists()) null
                else MultipartBody.Part.createFormData("photos[${reading.photo}]", file.name, file.asRequestBody(JPEG))
            }
        }

        val response = try {
            api.syncMobileColorimetric(test.farm_id, gson.toJson(payload).toRequestBody(JSON), photos)
        } catch (e: IOException) {
            recordError(test, "No connection — the test is saved on this phone. Try again when online.")
            throw e
        }
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            val message = serverMessage(response.errorBody()?.string()) ?: "Sync failed (${response.code()})."
            recordError(test, message)
            throw IOException(message)
        }

        test.synced_sample_id = body.sample_id
        test.synced_at = System.currentTimeMillis()
        test.sync_error = null
        withContext(Dispatchers.IO) { testStore.save(test) }
        return body
    }

    private suspend fun recordError(test: MobileTest, message: String) {
        test.sync_error = message
        withContext(Dispatchers.IO) { testStore.save(test) }
    }

    private fun readingJson(r: CaptureReading): Map<String, Any?> = mapOf(
        "photo" to r.photo,
        "hex" to r.hex,
        "r" to r.r,
        "g" to r.g,
        "b" to r.b,
        "computed_value" to r.computed_value,
        "confidence_pct" to r.confidence_pct,
        "chart_ph" to r.chart_ph,
        "zone" to r.zone,
    )

    /** Laravel's {"message": ...}, plus the first validation error when there is one. */
    private fun serverMessage(errorBody: String?): String? = runCatching {
        val map = gson.fromJson(errorBody, Map::class.java) ?: return null
        val message = map["message"] as? String
        val firstError = (map["errors"] as? Map<*, *>)?.values?.firstOrNull()?.let { (it as? List<*>)?.firstOrNull() as? String }
        listOfNotNull(message, firstError?.takeIf { it != message }).joinToString(" ").ifBlank { null }
    }.getOrNull()

    companion object {
        // A plain form field: PHP only treats parts with a filename as uploads.
        private val JSON = "text/plain; charset=utf-8".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
    }
}
