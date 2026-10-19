package com.lumora.photoeditor.engine

/**
 * Training-data license policy for the AI Pro Retouch Step 2 predictor
 * (the [AiProRetouch.WeightPredictor] seam).
 *
 * Step 1 (shipped) is a documented transparent heuristic over a bank of the
 * app's own curated pro recipes — no external data involved. Step 2 swaps
 * in a trained predictor, and training needs before/after pairs. This policy
 * is the machine-readable version of that decision: datasets on the block
 * list must never be downloaded, trained on, or shipped — including their
 * derived data (a trained model counts as derived data under PPR10K's
 * agreement).
 *
 * The block list is pinned by SelfTest check 63 so a future automated
 * worker cannot silently "unblock" a dataset. To add or remove an entry,
 * update [BLOCKED] AND the SelfTest pin together, with the verification
 * source cited in the entry's [verified] field.
 *
 * Zero Android imports on purpose: pure Kotlin, JVM-harnessable like
 * AiProRetouch.
 */
object AiTrainingPolicy {

    /** One blocked training-data source. */
    data class BlockedSource(
        /** Canonical dataset name, matched case-insensitively. */
        val name: String,
        /** Why it is blocked, in one sentence. */
        val reason: String,
        /** When + where the license was verified (evidence trail). */
        val verified: String,
    )

    val BLOCKED: List<BlockedSource> = listOf(
        BlockedSource(
            name = "PPR10K",
            reason = "Author agreement: all dataset files are for non-commercial " +
                "research purposes only, and the agreement explicitly covers " +
                "\"any portion of derived data\" — a trained model is derived " +
                "data, so neither the images nor a model trained on them may " +
                "ship in a commercial app.",
            verified = "2026-10-18: official repo README \"Agreement\" section " +
                "(github.com/csjliang/PPR10K): \"All files in the PPR10K " +
                "dataset are available for non-commercial research purposes " +
                "only.\" + no-commercial-exploitation clause including " +
                "derived data. Repo code itself is Apache-2.0, but the " +
                "dataset and derived data are not.",
        ),
        BlockedSource(
            name = "MIT-Adobe FiveK",
            reason = "Research-only license (no commercial use); weights stay " +
                "R&D/prototype only and must never ship in the app.",
            verified = "2026-10-17: established during the Step 1 vs Step 2 " +
                "tradeoff review (decision recorded in project memory).",
        ),
    )

    /** Aliases that resolve to the same blocked datasets. */
    private val ALIASES: Map<String, String> = mapOf(
        "fivek" to "MIT-Adobe FiveK",
        "mit adobe fivek" to "MIT-Adobe FiveK",
        "mit-adobe-fivek" to "MIT-Adobe FiveK",
        "ppr10k" to "PPR10K",
    )

    /**
     * True when [datasetName] (case-insensitive, aliases resolved) is on the
     * block list and must not be used for training or shipping.
     */
    fun isBlocked(datasetName: String): Boolean {
        val key = datasetName.trim().lowercase()
        // The alias values are display-case ("MIT-Adobe FiveK"); lowercase
        // them too, or every aliased lookup (including the exact canonical
        // names "PPR10K"/"FiveK") compares "ppr10k" == "PPR10K" and wrongly
        // reports the dataset as NOT blocked.
        val canonical = (ALIASES[key] ?: key).lowercase()
        return BLOCKED.any { it.name.lowercase() == canonical }
    }

    /** The blocked entry for [datasetName], or null when not blocked. */
    fun blockedEntry(datasetName: String): BlockedSource? {
        val key = datasetName.trim().lowercase()
        val canonical = (ALIASES[key] ?: key).lowercase()
        return BLOCKED.firstOrNull { it.name.lowercase() == canonical }
    }
}
