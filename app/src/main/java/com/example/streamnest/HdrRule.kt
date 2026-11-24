package com.example.streamnest

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** "No HDR" in your picture filters is kept everywhere: first pick, swaps, upgrades and the Quality menu. */
object HdrRule {
    /** 0.9.76 (phones): the HDR switch (quality menu / Settings › Playback). Off (default) = strictly no HDR anywhere;
     *  on = HDR copies are offered and played without any restriction. TV keeps the picture-filter rule. */
    fun on(vm: AppViewModel): Boolean = vm.texts.value["hdrOn"] == "on"
    fun banned(vm: AppViewModel): Boolean {
        if (!BuildConfig.IS_TV) return !on(vm)
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
