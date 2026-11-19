package com.agrilens.field.storage

import android.content.Context
import com.agrilens.field.colorimetry.ColorimetricConfig
import com.agrilens.field.colorimetry.MobileTest
import com.agrilens.field.colorimetry.StoredConfig
import com.google.gson.Gson
import java.io.File

/**
 * The downloaded colour charts, saved on the phone with the time they were
 * downloaded, so colorimetric tests work with no signal.
 */
class ConfigStore(context: Context) {

    private val file = File(context.filesDir, "colorimetric_config.json")
    private val gson = Gson()

    fun load(): StoredConfig? = runCatching {
        if (!file.exists()) null else gson.fromJson(file.readText(), StoredConfig::class.java)
    }.getOrNull()?.takeIf { it.config.ph_charts != null && it.config.npk_charts != null }

    fun save(config: ColorimetricConfig, downloadedAt: Long = System.currentTimeMillis()): StoredConfig {
        val stored = StoredConfig(config, downloadedAt)
        writeAtomically(file, gson.toJson(stored))
        return stored
    }
}

/**
 * Mobile colorimetric tests kept on the phone until synced:
 * files/mobile_tests/<uuid>/test.json plus one <photo>.jpg per capture.
 */
class TestStore(context: Context) {

    private val root = File(context.filesDir, "mobile_tests")
    private val gson = Gson()

    fun list(): List<MobileTest> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.created_at }

    fun load(uuid: String): MobileTest? = runCatching {
        val file = File(dir(uuid), "test.json")
        if (!file.exists()) null else gson.fromJson(file.readText(), MobileTest::class.java)?.normalized()
    }.getOrNull()

    fun save(test: MobileTest) {
        writeAtomically(File(dir(test.uuid).apply { mkdirs() }, "test.json"), gson.toJson(test))
    }

    fun photoFile(uuid: String, photo: String): File = File(dir(uuid).apply { mkdirs() }, "$photo.jpg")

    fun deletePhotos(uuid: String, photos: List<String>) {
        photos.forEach { photoFile(uuid, it).delete() }
    }

    fun delete(uuid: String) {
        dir(uuid).deleteRecursively()
    }

    private fun dir(uuid: String): File {
        require(uuid.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "Bad test id." }
        return File(root, uuid)
    }

    /** Gson skips Kotlin defaults, so fill in any collection missing from older files. */
    @Suppress("SENSELESS_COMPARISON")
    private fun MobileTest.normalized(): MobileTest {
        if (ph == null) ph = com.agrilens.field.colorimetry.PhProgress()
        if (ph.step1 == null) ph.step1 = mutableListOf()
        if (ph.step2 == null) ph.step2 = mutableListOf()
        if (ph.state == null) ph.state = com.agrilens.field.colorimetry.PhProgress.STATE_STEP1
        if (nutrients == null) nutrients = mutableMapOf()
        return this
    }
}

private fun writeAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) {
        target.writeText(text)
        tmp.delete()
    }
}
