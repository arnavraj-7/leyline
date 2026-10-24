package dev.leyline.sim

import java.util.Locale

/** A markdown table. */
class Table(val header: List<String>, val rows: List<List<String>>) {
    fun toMarkdown(): String = buildString {
        appendLine(header.joinToString(" | ", "| ", " |"))
        appendLine(header.joinToString(" | ", "| ", " |") { "---" })
        rows.forEach { appendLine(it.joinToString(" | ", "| ", " |")) }
    }
}

/** What a scenario hands back: tables for the report, raw runs for the tests. */
class ScenarioResult(
    val name: String,
    val title: String,
    val notes: List<String>,
    val tables: List<Table>,
    val runs: Map<String, RunResult>,
) {
    fun toMarkdown(): String = buildString {
        appendLine("## $name: $title")
        appendLine()
        notes.forEach { appendLine("- $it") }
        val repeats = runs.values.sumOf { it.repeatDeliveries }
        val crashes = runs.values.sumOf { it.crashes }
        val malformed = runs.values.sumOf { it.malformed }
        appendLine("- Integrity: $repeats repeat deliveries, $crashes node crashes, $malformed malformed packets.")
        tables.forEach {
            appendLine()
            append(it.toMarkdown())
        }
    }
}

internal val RUN_HEADER = listOf(
    "Run", "Delivered", "Latency p50 / p95 / max", "Frames", "Packet writes", "of which receipts", "Writes / delivery",
    "Redundant rx", "Failed writes", "Queue drops", "Receipts",
)

internal fun RunResult.cells(): List<String> = listOf(
    label,
    delivery(),
    latency.format(),
    frames.toString(),
    writes.toString(),
    receiptWrites.toString(),
    if (writesPerDelivery.isNaN()) "—" else fmt("%.2f", writesPerDelivery),
    redundant.toString(),
    failedWrites.toString(),
    queueDrops.toString(),
    receipts.toString(),
)

internal fun runTable(runs: Collection<RunResult>) = Table(RUN_HEADER, runs.map { it.cells() })

internal fun RunResult.delivery(): String = "${percent(deliveryRatio)} ($delivered/$expected)"

internal fun Percentiles.format(): String = if (count == 0) "—" else "${ms(p50)} / ${ms(p95)} / ${ms(max)}"

internal fun ms(value: Long?): String = when {
    value == null -> "—"
    value < 10_000 -> "$value ms"
    value < 600_000 -> fmt("%.1f s", value / 1000.0)
    else -> fmt("%.1f min", value / 60_000.0)
}

internal fun hops(n: Int): String = if (n == 1) "1 hop" else "$n hops"

internal fun percent(ratio: Double): String = fmt("%.1f%%", ratio * 100)

internal fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.ROOT, pattern, *args)
