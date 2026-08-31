package com.badmintontracker.analysis.compare

import com.badmintontracker.analysis.result.AnalysisResult
import java.io.File

/**
 * The desktop venue for the A/B comparator (section 5.7).
 *
 * Lives in jvmMain because it needs file I/O, which commonMain is kept free of
 * so :analysis stays testable without a device or a network. The comparison
 * itself is in commonMain; this is argument parsing and two file reads, so the
 * in-app venue runs identical logic.
 *
 * Usage: ComparatorCli <local-results.json> <cloud-results.json>
 */
object ComparatorCli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("usage: ComparatorCli <local-results.json> <cloud-results.json>")
            kotlin.system.exitProcess(2)
        }
        val local = readResult(args[0]) ?: kotlin.system.exitProcess(2)
        val cloud = readResult(args[1]) ?: kotlin.system.exitProcess(2)
        println(compare(local = local, cloud = cloud).toJson())
    }

    private fun readResult(path: String): AnalysisResult? {
        val file = File(path)
        if (!file.isFile) {
            System.err.println("not a file: $path")
            return null
        }
        return try {
            AnalysisResult.fromJson(file.readText())
        } catch (e: Exception) {
            // Loud and specific: a malformed results.json read as an empty
            // one would report perfect disagreement as though it were a
            // measurement.
            System.err.println("could not parse $path as results.json: ${e.message}")
            null
        }
    }
}
