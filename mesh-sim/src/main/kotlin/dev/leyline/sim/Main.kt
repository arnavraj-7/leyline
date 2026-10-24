package dev.leyline.sim

import java.io.File
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * `./gradlew :mesh-sim:run --args="all"`, or scenario names, optionally `--seed N`.
 * Prints markdown to stdout and writes the same to build/reports/sim-report.md.
 */
fun main(args: Array<String>) {
    var seed = 1L
    val names = mutableListOf<String>()
    val rest = args.iterator()
    while (rest.hasNext()) {
        when (val arg = rest.next()) {
            "--seed" -> seed = rest.next().toLong()
            else -> names += arg
        }
    }
    if (names.isEmpty() || "all" in names) names.apply { clear(); addAll(SCENARIOS.keys) }
    val unknown = names.filter { it !in SCENARIOS }
    if (unknown.isNotEmpty()) {
        System.err.println("unknown scenario ${unknown.joinToString()}; pick from: all, ${SCENARIOS.keys.joinToString()}")
        exitProcess(2)
    }

    val sections = names.map { name ->
        val started = System.nanoTime()
        SCENARIOS.getValue(name)(seed).also {
            System.err.println("$name: ${(System.nanoTime() - started) / 1_000_000} ms wall time")
        }
    }
    val report = buildString {
        appendLine("# Leyline mesh simulator report")
        appendLine()
        appendLine("Seed $seed. Virtual time; every number below is reproducible with the same seed.")
        appendLine("Links: 180 B frames unless noted, 15 ± 10 ms per frame, one GATT op in flight per link.")
        appendLine("MeshConfig defaults: TTL 4, forward jitter 150 ms (SOS 20 ms), resync 15 s, queue 256.")
        appendLine("\"Redundant rx\" = copies a router dropped as duplicate, echo or loop. \"Receipts\" = SOS/unicast delivery receipts that reached their origin.")
        sections.forEach {
            appendLine()
            append(it.toMarkdown())
        }
    }
    // The report has en dashes and ±; the Windows console default charset would mangle them.
    PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8).print(report)
    val file = File("build/reports/sim-report.md")
    file.parentFile.mkdirs()
    file.writeText(report)
    System.err.println("report written to ${file.absolutePath}")
}
