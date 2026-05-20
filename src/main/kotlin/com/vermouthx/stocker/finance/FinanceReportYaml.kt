package com.vermouthx.stocker.finance

/**
 * Minimal YAML extractor for finance/ project reports.
 *
 * Each report ends with a ```yaml ... ``` block whose top key is `judgment_snapshot:`.
 * We do NOT bring in SnakeYAML (would conflict with platform classpath).
 *
 * Supported features (sufficient for position-risk-monitor / market-research / flow-monitor):
 *   - scalar key: value
 *   - nested map by 2-space indent
 *   - list items starting with "- "
 *   - list of inline-map "- { symbol: ..., trend: ... }"
 *
 * Caller should use [extractLastYamlBlock] then [parseSimpleYaml] then walk the resulting
 * tree using [yamlList], [yamlMap], etc.
 */
internal object FinanceReportYaml {

    private val YAML_FENCE_RE = Regex("""```yaml\s*\n([\s\S]*?)\n```""")
    private val INLINE_MAP_KV_RE = Regex("""(\w[\w_]*)\s*:\s*("[^"]*"|'[^']*'|[^,}\s]+)""")

    fun extractLastYamlBlock(mdText: String): String? {
        val matches = YAML_FENCE_RE.findAll(mdText).toList()
        return matches.lastOrNull()?.groupValues?.getOrNull(1)
    }

    /** Returns Map<String, Any>; values may be String, Map, List<Map>, List<String>, or null. */
    fun parseSimpleYaml(yaml: String): Map<String, Any?> {
        val lines = yaml.lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        if (lines.isEmpty()) return emptyMap()
        val root = LinkedHashMap<String, Any?>()
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(indent = -1, container = root, lastKey = null))

        for (raw in lines) {
            val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val line = raw.substring(indent)
            // Pop frames whose indent >= current
            while (stack.last().indent >= indent && stack.size > 1) {
                stack.removeLast()
            }
            val frame = stack.last()

            if (line.startsWith("- ")) {
                // List item
                val rest = line.substring(2).trim()
                @Suppress("UNCHECKED_CAST")
                val list = ensureList(frame, rest)
                if (rest.startsWith("{") && rest.endsWith("}")) {
                    // inline map
                    val map = parseInlineMap(rest.substring(1, rest.length - 1))
                    list.add(map)
                } else if (rest.contains(": ")) {
                    // start of a block-style map item: "- symbol: 688981" then continuation lines
                    val map = LinkedHashMap<String, Any?>()
                    val (k, v) = splitKv(rest)
                    map[k] = parseScalar(v)
                    list.add(map)
                    // push a frame so subsequent deeper-indent lines populate this map
                    stack.addLast(Frame(indent = indent, container = map, lastKey = null))
                } else {
                    list.add(parseScalar(rest))
                }
            } else if (line.contains(":")) {
                val (k, vRaw) = splitKv(line)
                if (vRaw.isEmpty()) {
                    // open a new nested map (or list, decided when next line comes)
                    val child = LinkedHashMap<String, Any?>()
                    appendToFrame(frame, k, child)
                    stack.addLast(Frame(indent = indent, container = child, lastKey = k))
                } else if (vRaw.startsWith("[") && vRaw.endsWith("]")) {
                    // inline list  "target_zone: [150, 180]"
                    val items = vRaw.substring(1, vRaw.length - 1)
                        .split(",")
                        .map { parseScalar(it.trim()) }
                    appendToFrame(frame, k, items)
                } else if (vRaw.startsWith("{") && vRaw.endsWith("}")) {
                    appendToFrame(frame, k, parseInlineMap(vRaw.substring(1, vRaw.length - 1)))
                } else {
                    appendToFrame(frame, k, parseScalar(vRaw))
                }
            }
            // else: silently ignore malformed lines
        }
        return root
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private data class Frame(val indent: Int, val container: Any, val lastKey: String?)

    @Suppress("UNCHECKED_CAST")
    private fun ensureList(frame: Frame, hintFirstItem: String): MutableList<Any?> {
        val parent = frame.container
        // The "list parent" is whatever container the previous map-key opened.
        // If frame.container is a Map and the last key's current value is null,
        // replace it with a fresh list.
        if (parent is MutableMap<*, *>) {
            val m = parent as MutableMap<String, Any?>
            val k = frame.lastKey
            if (k != null) {
                val existing = m[k]
                if (existing is MutableList<*>) return existing as MutableList<Any?>
                if (existing == null) {
                    val list = ArrayList<Any?>()
                    m[k] = list
                    return list
                }
            }
            // fall through: stash under "__list__" anonymous key
            val list = ArrayList<Any?>()
            m["__list__"] = list
            return list
        }
        if (parent is MutableList<*>) {
            return parent as MutableList<Any?>
        }
        return ArrayList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendToFrame(frame: Frame, key: String, value: Any?) {
        val parent = frame.container
        if (parent is MutableMap<*, *>) {
            (parent as MutableMap<String, Any?>)[key] = value
        } else if (parent is MutableList<*>) {
            // list of maps; create a new map item
            val map = LinkedHashMap<String, Any?>()
            map[key] = value
            (parent as MutableList<Any?>).add(map)
        }
    }

    private fun parseInlineMap(body: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        INLINE_MAP_KV_RE.findAll(body).forEach { m ->
            val k = m.groupValues[1]
            val v = m.groupValues[2]
            out[k] = parseScalar(v)
        }
        return out
    }

    private fun splitKv(line: String): Pair<String, String> {
        val idx = line.indexOf(':')
        if (idx < 0) return line to ""
        return line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }

    private fun parseScalar(raw: String): Any? {
        val v = raw.trim()
        if (v.isEmpty()) return ""
        if (v == "null" || v == "~") return null
        if (v == "true") return true
        if (v == "false") return false
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length - 1)
        }
        v.toIntOrNull()?.let { return it }
        v.toDoubleOrNull()?.let { return it }
        return v
    }

    // ── typed accessors ────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    fun mapAt(root: Map<String, Any?>, vararg path: String): Map<String, Any?>? {
        var cur: Any? = root
        for (p in path) {
            if (cur !is Map<*, *>) return null
            cur = (cur as Map<String, Any?>)[p]
        }
        return cur as? Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    fun listAt(root: Map<String, Any?>, vararg path: String): List<Any?>? {
        var cur: Any? = root
        for (p in path) {
            if (cur !is Map<*, *>) return null
            cur = (cur as Map<String, Any?>)[p]
        }
        return cur as? List<Any?>
    }
}
