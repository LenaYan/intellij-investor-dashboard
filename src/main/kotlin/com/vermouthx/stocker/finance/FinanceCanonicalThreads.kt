package com.vermouthx.stocker.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Monitors `main_thread` values across today's reports for **naming drift** — the
 * root cause of the 5/22 "AI 算力 vs AI算力 vs 算力链" incident (CLAUDE.md red line #3).
 *
 * Strategy:
 *   1. Walk every report in `reports/{today}/{agent}.md`, extract the trailing YAML's
 *      `judgment_snapshot.main_thread` scalar.
 *   2. Group reports by **normalized** thread name (whitespace stripped + lowercase
 *      Chinese punctuation removed). If a group has ≥2 distinct raw spellings, flag
 *      it as drift.
 *   3. (Optional) Cross-check against the `docs/canonical-threads.md` registry: any
 *      raw spelling not in the registry → "未注册主线" warning.
 *
 * The result is consumed by FinanceMainThreadHeader (yellow badge) and the
 * THREAD_NAME_DRIFT notifier (one-shot per drift-set per day).
 */
data class CanonicalDrift(
    val normalizedKey: String,
    val spellings: List<String>,
    val sources: Map<String, List<String>>,  // raw spelling → list of agent names
)

internal object FinanceCanonicalThreads {

    /**
     * Returns drift groups detected in today's reports. Empty list = no drift.
     */
    fun detect(financeDir: Path, today: LocalDate = FinanceReportLocator.today()): List<CanonicalDrift> {
        val seenByNorm = LinkedHashMap<String, MutableMap<String, MutableList<String>>>()
        for (b in 0..2) {
            val d = today.minusDays(b.toLong())
            val dir = financeDir.resolve("reports").resolve(d.toString())
            if (!Files.isDirectory(dir)) continue
            try {
                Files.list(dir).use { stream ->
                    stream.forEach { p ->
                        val name = p.fileName.toString()
                        if (!name.endsWith(".md")) return@forEach
                        val agent = name.removeSuffix(".md")
                        val md = try { Files.readString(p) } catch (_: Exception) { return@forEach }
                        val yaml = FinanceReportYaml.extractLastYamlBlock(md) ?: return@forEach
                        val tree = FinanceReportYaml.parseSimpleYaml(yaml)
                        val snap = FinanceReportYaml.mapAt(tree, "judgment_snapshot") ?: tree
                        val mt = (snap["main_thread"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
                        val norm = normalize(mt)
                        val bucket = seenByNorm.getOrPut(norm) { LinkedHashMap() }
                        bucket.getOrPut(mt) { ArrayList() }.add(agent)
                    }
                }
            } catch (_: Exception) {
                continue
            }
            if (seenByNorm.isNotEmpty()) break
        }

        return seenByNorm
            .filter { (_, bucket) -> bucket.keys.size >= 2 }
            .map { (norm, bucket) ->
                CanonicalDrift(
                    normalizedKey = norm,
                    spellings = bucket.keys.toList(),
                    sources = bucket.mapValues { it.value.toList() },
                )
            }
    }

    /** Normalize a thread name for drift comparison. */
    fun normalize(s: String): String {
        return s.replace(Regex("""\s+"""), "")            // strip whitespace
            .replace("·", "")
            .replace("-", "")
            .replace("·", "")
            .replace("：", ":")
            .lowercase()
    }
}
