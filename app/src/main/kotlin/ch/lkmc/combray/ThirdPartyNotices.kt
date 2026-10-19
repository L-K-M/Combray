package ch.lkmc.combray

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One piece of third-party software in the APK and the license texts it is shown with. */
data class Notice(val name: String, val version: String?, val license: String, val texts: List<Int>)

data class Notices(val components: List<Notice>, val texts: List<String>) {
    companion object {
        /**
         * Joins the engine's notices (scripts/generate-notices.py) with the Java/Kotlin libraries
         * Licensee lists at build time. Licensee gives only SPDX identifiers; their texts come from
         * the engine file. A license without a text is an error, not an entry without one.
         */
        fun parse(native: String, jvm: String): Notices {
            val engine = Json.parseToJsonElement(native).jsonObject
            val texts = engine.getValue("texts").jsonArray.map { it.jsonPrimitive.content }
            val spdx = engine.getValue("spdx").jsonObject.mapValues { it.value.jsonPrimitive.int }

            val nativeComponents = engine.getValue("components").jsonArray.map { element ->
                val component = element.jsonObject
                Notice(
                    name = component.string("name")!!,
                    version = component.string("version"),
                    license = component.string("license")!!,
                    texts = component.getValue("texts").jsonArray.map { it.jsonPrimitive.int },
                )
            }
            val jvmComponents = Json.parseToJsonElement(jvm).jsonArray.map { element ->
                val artifact = element.jsonObject
                val licenses = artifact.getValue("spdxLicenses").jsonArray.map { it.jsonObject.string("identifier")!! }
                require(licenses.isNotEmpty()) { "no license for ${artifact.string("artifactId")}" }
                Notice(
                    name = "${artifact.string("groupId")}:${artifact.string("artifactId")}",
                    version = artifact.string("version"),
                    license = licenses.joinToString(" AND "),
                    texts = licenses.map { spdx[it] ?: throw IllegalArgumentException("no text for $it") },
                )
            }

            val components = (nativeComponents + jvmComponents).sortedBy { it.name.lowercase() }
            require(components.all { notice -> notice.texts.isNotEmpty() && notice.texts.all { it in texts.indices } }) {
                "a notice refers to a missing text"
            }
            return Notices(components, texts)
        }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
    }
}

/** Reads the notices bundled in the APK once; they are large, so only when asked. */
class ThirdPartyNotices(private val assets: AssetManager) {
    private val lock = Mutex()
    private var loaded: Notices? = null

    /** Null if the bundled files are missing or malformed: a build defect, shown as such. */
    suspend fun load(): Notices? = lock.withLock {
        loaded ?: withContext(Dispatchers.IO) { read() }?.also { loaded = it }
    }

    private fun read(): Notices? = runCatching {
        val native = assets.open(NATIVE_NOTICES).bufferedReader().use { it.readText() }
        val jvm = assets.open(JVM_NOTICES).bufferedReader().use { it.readText() }
        Notices.parse(native, jvm)
    }.onFailure { Log.e(LOG_TAG, "third-party notices unreadable", it) }.getOrNull()

    private companion object {
        const val LOG_TAG = "CombrayNotices"
        const val NATIVE_NOTICES = "notices/native.json"
        /** Licensee's default bundled report path. */
        const val JVM_NOTICES = "app/cash/licensee/artifacts.json"
    }
}
