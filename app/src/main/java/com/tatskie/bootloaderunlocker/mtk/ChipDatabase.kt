package com.tatskie.bootloaderunlocker.mtk

object ChipDatabase {

    private val HW_CODE_NAMES = mapOf(
        0x0321 to "MT8321",
        0x0335 to "MT6735",
        0x0337 to "MT6737",
        0x0339 to "MT6739",
        0x0657 to "MT6572",
        0x0658 to "MT6582",
        0x0659 to "MT6592",
        0x0651 to "MT6571",
        0x6735 to "MT6735",
        0x6737 to "MT6737",
        0x6739 to "MT6739",
        0x6752 to "MT6752",
        0x6753 to "MT6753",
        0x6755 to "MT6755",
        0x6757 to "MT6757",
        0x6758 to "MT6758",
        0x6761 to "MT6761",
        0x6763 to "MT6763",
        0x6765 to "MT6765",
        0x6768 to "MT6768",
        0x6771 to "MT6771",
        0x6785 to "MT6785",
        0x6833 to "MT6833",
        0x6885 to "MT6885",
        0x6580 to "MT6580",
        0x6582 to "MT6582",
        0x6592 to "MT6592",
        0x6571 to "MT6571",
        0x6572 to "MT6572",
        0x6590 to "MT6590",
        0x8127 to "MT8127",
        0x8163 to "MT8163",
        0x8173 to "MT8173",
        0x8176 to "MT8176",
        0x8183 to "MT8183",
        0x8765 to "MT8765",
        0x8768 to "MT8768"
    )

    fun nameFor(hwCode: Int): String = HW_CODE_NAMES[hwCode] ?: "Unknown (0x%04X)".format(hwCode)
}
