package com.example.streamnest

/** 0.9.7 (addonFirst): which copy belongs to which lane, and the default Quick Play list. */
object AddonFirst {
    /** Quick Play default (Rules › Quick Play, one per line): the direct HLS plugins (Netflix / Prime / Hotstar type,
     *  0.3–0.5 s) first, then every CNCVerse addon (Anish for MovieBox, Salvatore as the HLS backup). */
    val QUICK: List<String> = listOf(OTT_ANY, CNC_ANY)

    fun isCncName(n: String) = n.contains("cncverse", true) || n.contains("cnc verse", true)
    fun isHdHubName(n: String) = n.contains("hdhub", true)

    /** A quick-sources list as the flows use it: CNC_ANY becomes the labels of the CNCVerse addons switched on. The
     *  untouched default without any CNCVerse addon is empty (= as before: the usual Quick Start). */
    fun expand(vm: AppViewModel?, l: List<String>): List<String> {
        if (CNC_ANY !in l) return l
        val cnc = vm?.activeAddons().orEmpty().filter { isCncName(it.label()) || isCncName(it.manifest.name) }.map { it.label() }
        if (cnc.isEmpty() && l == QUICK) return emptyList()
        return l.flatMap { if (it == CNC_ANY) cnc else listOf(it) }.distinct()
    }

    private fun blob(s: StreamItem): String =
        listOfNotNull(s.stream.name, s.stream.title, s.stream.description, s.stream.behaviorHints?.filename, s.stream.url).joinToString(" ").lowercase()
    fun isCnc(s: StreamItem) = isCncName(s.addonName)
    /** CNCVerse's Rocky = its VegaMovies / RogMovies files (the link's name / description / file name / address say so;
     *  isVegamovies() reads the same fields). Never matched on "rocky" (a film can be called that). */
    fun isRocky(s: StreamItem): Boolean = s.isVegamovies() || "rogmovies" in blob(s)
    fun isHdHub(s: StreamItem) = isHdHubName(s.addonName)
    /** Quality-lane copies: they never take the start (they come later through the lane, or as backup). */
    fun qualityOnly(s: StreamItem) = isHdHub(s) || (isCnc(s) && isRocky(s))

    /** Backup tie-break when Rules › Backup is empty (the backup still asks every source). Lower = earlier. */
    fun backupRank(name: String): Int = when {
        isCncName(name) -> 0
        isHdHubName(name) -> 1
        name.contains("moviebox", true) && name.endsWith("IN") -> 2   // MovieBoxIN: the MovieBox backup
        name.contains("moviebox", true) -> 4                          // the other MovieBox plugins: last
        else -> 3
    }
}

/** 0.9.7 (addonFirst): Rules item = every CNCVerse addon (its label can be anything that names CNCVerse). */
const val CNC_ANY = "Addon · *cncverse*"
