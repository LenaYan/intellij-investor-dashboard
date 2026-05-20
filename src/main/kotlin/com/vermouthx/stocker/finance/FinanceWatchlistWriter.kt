package com.vermouthx.stocker.finance

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Appends or updates an entry in ~/Claude/finance/watchlist.json while preserving
 * the surrounding keys (schema_version, notes, etc.).
 *
 * Idempotent on symbol: passing an existing symbol replaces its entry rather than
 * duplicating. Atomic on disk (write to .tmp then move).
 */
internal object FinanceWatchlistWriter {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    data class NewEntry(
        val symbol: String,
        val name: String,
        val sector: String?,
        val thesis: String?,
        val trigger: String?,
        val targetLow: Double?,
        val targetHigh: Double?,
        val invalidation: String?,
        val refPrice: Double?,
    )

    /**
     * Add or replace an entry. Returns true on success.
     * Caller must call FinanceBridgeService.reloadNow() after a successful write.
     */
    fun upsert(financeDir: Path, entry: NewEntry): Boolean {
        val watchlistPath = financeDir.resolve("watchlist.json")
        val root: JsonObject = if (Files.isRegularFile(watchlistPath)) {
            try {
                JsonParser.parseString(Files.readString(watchlistPath)).asJsonObject
            } catch (_: Exception) {
                seedRoot()
            }
        } else seedRoot()

        val items = (root.get("items") as? JsonArray) ?: JsonArray().also { root.add("items", it) }
        // Remove existing entry with same symbol (case-insensitive)
        val targetSym = entry.symbol.trim()
        val targetKey = FinanceSymbol.normalize(targetSym)
        val keep = JsonArray()
        items.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val sym = el.asJsonObject.get("symbol")?.asString
            if (sym == null || FinanceSymbol.normalize(sym) != targetKey) {
                keep.add(el)
            }
        }
        keep.add(toJson(entry))
        root.add("items", keep)
        root.addProperty("as_of", LocalDate.now().toString())

        // Atomic write
        return try {
            val tmp = watchlistPath.resolveSibling("watchlist.json.tmp")
            Files.writeString(tmp, gson.toJson(root))
            Files.move(tmp, watchlistPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: Exception) {
            try {
                Files.writeString(watchlistPath, gson.toJson(root))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun toJson(e: NewEntry): JsonObject = JsonObject().apply {
        addProperty("symbol", e.symbol.trim())
        addProperty("name", e.name.trim())
        e.sector?.takeIf { it.isNotBlank() }?.let { addProperty("sector", it) }
        e.thesis?.takeIf { it.isNotBlank() }?.let { addProperty("thesis", it) }
        e.trigger?.takeIf { it.isNotBlank() }?.let { addProperty("trigger", it) }
        if (e.targetLow != null && e.targetHigh != null) {
            val arr = JsonArray()
            arr.add(e.targetLow)
            arr.add(e.targetHigh)
            add("target_zone", arr)
        }
        e.invalidation?.takeIf { it.isNotBlank() }?.let { addProperty("invalidation", it) }
        addProperty("added_date", LocalDate.now().toString())
        e.refPrice?.let { addProperty("ref_price", it) }
    }

    private fun seedRoot(): JsonObject = JsonObject().apply {
        addProperty("as_of", LocalDate.now().toString())
        addProperty("schema_version", 2)
        addProperty("notes", "由 Stocker IntelliJ 插件创建")
        add("items", JsonArray())
    }
}
