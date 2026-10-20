package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.utils.ExtractorApi

// Readers written by the helper (download-only hosts — 1fichier, Indishare, ClickNUpload, GDTot — left out on purpose).
val extraExtractors: List<ExtractorApi> = listOf(
    DoodStream(), DoodWs(), DoodSh(), DoodPm(), DoodWf(), DoodRe(), DoodYt(), DoodCx(), DoodSo(),
    DoodTo(), DoodWatch(), DoodLi(), DoodStreamCom(), DoodsPro(), Dooood(), D0000d(), D0o0d(),
    Ds2Play(), Ds2Video(),
    MixDrop(), MixDropTo(), MixDropSx(), MixDropBz(), MixDropCh(), MixDropAg(), MixDropGl(),
    MixDropClub(), MixDropPs(), MixDrpCo(), MixDrpTo(),
    Upstream(), Gofile(), Waaw(), StreamSB(), MultiUp(),
    StreamTape(), StreamTapeCc(), StreamTapeTo(), StreamTapeNet(), StreamTapeXyz(), StreamTapeSite(),
    StreamTapeOnline(), StreamTapeAdBlock(), StrTapeCloud(), StrTapeSite(), StrTapeTech(), StrTapeCom(),
    StrTapeNet(), StrTapeAdBlock(), ShaveTape(), StapeFun(), StreamTaPe(), ScloudOnline()
)
