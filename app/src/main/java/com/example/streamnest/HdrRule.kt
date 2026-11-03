package com.example.streamnest

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** "No HDR" in your picture filters is kept everywhere: first pick, swaps, upgrades and the Quality menu. */
object HdrRule {
    fun banned(vm: AppViewModel): Boolean {
        val r = effectiveRules(vm.texts.value["devTagRules"], vm.texts.value["tagRules"])
        return r["hdr"] == TagRule.Exclude || r["dv"] == TagRule.Exclude || r["sdr"] == TagRule.Forced
    }
    /** HDR / Dolby Vision by its name. */
    fun named(s: StreamItem): Boolean = s.tags().let { "hdr" in it || "dv" in it }
    /** HDR / Dolby Vision by the real picture (names don't always say it). */
    fun isHdr(f: Format?): Boolean {
        val t = f?.colorInfo?.colorTransfer
        return t == C.COLOR_TRANSFER_ST2084 || t == C.COLOR_TRANSFER_HLG || f?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
    }
}
