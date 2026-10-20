package com.tatskie.bootloaderunlocker.qualcomm

/**
 * Static lookup tables that turn raw Qualcomm EDL identifiers into human readable
 * names.
 *
 *  - The USB product ID (VID 0x05C6) tells us which EDL flavour the device booted into.
 *  - The Sahara MSM_HW_ID (read via the EXECUTE command, cmd 0x02) identifies the SoC.
 *
 * The HW ID is a little-endian 64-bit value; the platform identifier is the 24-bit
 * field formed from hex characters 2..7 of its 16-digit representation, which is the
 * same convention the reference Sahara tooling uses.
 */
object QualcommEdlDatabase {

    const val QUALCOMM_VENDOR_ID = 0x05C6

    fun edlModeForProductId(productId: Int): String = when (productId) {
        0x9008 -> "Firehose (EDL)"
        0x900E -> "Crash dump"
        0x901D -> "EDL (alternate)"
        0x90DB -> "Diag / dump mode"
        else -> "Unknown"
    }

    /** Extract the 24-bit MSM/platform identifier from a raw 64-bit Sahara HW ID. */
    fun hwIdToMsmId(hwId: Long): Int {
        val hex = "%016x".format(hwId)
        return hex.substring(2, 8).toInt(16)
    }

    fun platformForHwId(hwId: Long): String? = platformForMsmId(hwIdToMsmId(hwId))

    fun platformForMsmId(msmId: Int): String? = PLATFORMS[msmId]

    private val PLATFORMS: Map<Int, String> = mapOf(
        // Snapdragon 8-series (modern SM)
        0x0A50E1 to "Snapdragon 855 (SM8150)",
        0x0A60E1 to "Snapdragon 855+ (SM8150p)",
        0x0E80E1 to "Snapdragon 855 (SA8155)",
        0x0E90E1 to "Snapdragon 855 (SA8155p)",
        0x0C30E1 to "Snapdragon 865 (SM8250)",
        0x0CE0E1 to "Snapdragon 865 (SM8250)",
        0x1560E1 to "Snapdragon 865 (SM8250)",
        0x1350E1 to "Snapdragon 888 (SM8350)",
        0x1520E1 to "Snapdragon 888 (SM8350)",
        0x19E0E1 to "Snapdragon 888 (SM8350)",
        0x1420E1 to "Snapdragon 888+ (SM8350)",
        0x0B80E1 to "Snapdragon 8cx (SC8180X)",
        0x14A0E1 to "Snapdragon 8cx Gen 3 (SC8280X)",
        0x14B0E1 to "Snapdragon SA8295P",
        0x14C0E1 to "Snapdragon SA8540P",

        // Snapdragon 7-series
        0x0E60E1 to "Snapdragon 730 (SM7150)",
        0x0E70E1 to "Snapdragon 730G (SM7150p)",
        0x1630E1 to "Snapdragon 750G (SM7250)",
        0x11E0E1 to "Snapdragon 765G (SM7250)",
        0x1430E1 to "Snapdragon 765G (SM7250)",
        0x1920E1 to "Snapdragon 778G (SM7325)",
        0x12A0E1 to "Snapdragon 720G (SM7125)",
        0x12B0E1 to "Snapdragon 720G (SM7125)",
        0x1490E1 to "Snapdragon 720G (SM7125)",
        0x12C0E1 to "Snapdragon 7c (SC7180)",
        0x1930E1 to "Snapdragon 7c (SC7280)",
        0x1940E1 to "Snapdragon (SC7295)",
        0x1970E1 to "Snapdragon QCM6490",
        0x1980E1 to "Snapdragon QCS6490",

        // Snapdragon 6-series
        0x0950E1 to "Snapdragon 675 (SM6150)",
        0x0EC0E1 to "Snapdragon 675 (SM6150p)",
        0x0F50E1 to "Snapdragon (SM6155)",
        0x0CC0E1 to "Snapdragon 636 (SDM636)",
        0x0AC0E1 to "Snapdragon 630 (SDM630)",
        0x0EF0E1 to "Snapdragon 660 (SDM660)",
        0x08C0E1 to "Snapdragon 660 (SDM660)",
        0x0BA0E1 to "Snapdragon 632 (SDM632)",
        0x0BB0E1 to "Snapdragon 632 (SDA632)",
        0x0BC0E1 to "Snapdragon 630 (SDA630)",
        0x14D0E1 to "Snapdragon 662 (SDM662)",
        0x0B90E1 to "Snapdragon 450 (SDA450)",
        0x09A0E1 to "Snapdragon 450 (SDM450)",
        0x0BE0E1 to "Snapdragon 429 (SDM429)",
        0x0BF0E1 to "Snapdragon 439 (SDM439)",
        0x0460E1 to "Snapdragon 625/636 (MSM8953)",
        0x08D0E1 to "Snapdragon 658 (SDM658)",
        0x09D0E1 to "Snapdragon 658 (SDA658)",
        0x0910E1 to "Snapdragon 670 (SDM670)",
        0x0930E1 to "Snapdragon 670 (SDA670)",
        0x0DB0E1 to "Snapdragon 710 (SDM710)",
        0x10A0E1 to "Snapdragon 695 (nicobar)",
        0x1750E1 to "Snapdragon 695 (nicobar IoT)",
        0x1760E1 to "Snapdragon 695 (nicobar IoT)",

        // Snapdragon 4-series / low-end SM
        0x1B80E1 to "Snapdragon 680 (SM6225)",
        0x1360E1 to "Snapdragon 460 (kamorta)",
        0x1370E1 to "Snapdragon 460 (kamorta)",
        0x1730E1 to "Snapdragon 460 (kamorta IoT)",
        0x1740E1 to "Snapdragon 460 (kamorta IoT)",
        0x1C70E1 to "Snapdragon 460 (kamorta QRB)",
        0x16F0E1 to "Snapdragon 480 5G (mannar)",
        0x16E0E1 to "Snapdragon 480 5G (mannar)",
        0x13F0E1 to "Snapdragon 690 (bitra)",
        0x1410E1 to "Snapdragon 690 (bitra)",
        0x15A0E1 to "Snapdragon QRB5165",
        0x13D0E1 to "Snapdragon QCM2150",
        0x1060E1 to "Snapdragon QM215",

        // Snapdragon 8-series (older SDM/MSM)
        0x05E0E1 to "Snapdragon 835 (MSM8998)",
        0x08B0E1 to "Snapdragon 845 (SDM845)",
        0x08E0E1 to "Snapdragon 845 (SDA845)",
        0x0B70E1 to "Snapdragon 850 (SDM850)",
        0x9470E1 to "Snapdragon 820 (MSM8996)",
        0x05F0E1 to "Snapdragon 821 (MSM8996Pro)",
        0x06C0E1 to "Snapdragon (MSM8997)",
        0x9690E1 to "Snapdragon 808 (MSM8992)",
        0x9640E1 to "Snapdragon 808 (MSM8992)",
        0x9400E1 to "Snapdragon 808 (MSM8994)",
        0x9410E1 to "Snapdragon 810 (APQ8094)",
        0x0630E1 to "Snapdragon (MSM8996AU)",
        0x06F0E1 to "Snapdragon (MSM8996AU)",
        0x0620E1 to "Snapdragon (APQ8098)",
        0x0640E1 to "Snapdragon (APQ8096SG)",

        // Snapdragon 6xx / 4xx classic
        0x9720E1 to "Snapdragon 652 (MSM8952)",
        0x9900E1 to "Snapdragon 652 (MSM8976)",
        0x9820E1 to "Snapdragon 652 (MSM8976)",
        0x9B00E1 to "Snapdragon 652 (MSM8956)",
        0x9F00E1 to "Snapdragon 650 (APQ8056)",
        0x9770E1 to "Snapdragon (APQ8052)",
        0x90B0E1 to "Snapdragon 615 (MSM8939)",
        0x9570E1 to "Snapdragon 615 (MSM8239)",
        0x90D0E1 to "Snapdragon 615 (APQ8039)",
        0x90C0E1 to "Snapdragon (APQ8036)",
        0x90F0E1 to "Snapdragon (APQ8037)",
        0x0500E1 to "Snapdragon (APQ8037)",
        0x04F0E1 to "Snapdragon (MSM8937)",
        0x06B0E1 to "Snapdragon (MSM8940)",
        0x0560E1 to "Snapdragon 425 (MSM8917)",
        0x0860E1 to "Snapdragon (MSM8920)",
        0x05A0E1 to "Snapdragon 425 (MSM8617)",
        0x91B0E1 to "Snapdragon 415 (MSM8929)",
        0x0550E1 to "Snapdragon (APQ8017)",
        0x7050E1 to "Snapdragon 410 (MSM8916)",
        0x7060E1 to "Snapdragon (APQ8016)",
        0x0660E1 to "Snapdragon (APQ8053)",
        0x8040E1 to "Snapdragon (APQ8026)",
        0x9600E1 to "Snapdragon 210 (MSM8909)",
        0x9680E1 to "Snapdragon (APQ8009)",
        0x0940E1 to "Snapdragon (MSM8905)",
        0x9000E1 to "Snapdragon (APQ8084)",
        0x9010E1 to "Snapdragon 805 (APQ8084)",
        0x8100E1 to "Snapdragon (APQ806x)",
        0x9D00E1 to "Snapdragon (APQ8076)",
        0x08A0E1 to "Snapdragon (APQ807x)",
        0x9630E1 to "Snapdragon (APQ8092)",
        0x9300E1 to "Snapdragon (APQ8092)",
        0x0480E1 to "Snapdragon (MDM9207)",
        0x04E0E1 to "Snapdragon (APQ8096AU)",

        // Snapdragon X / MDM modems
        0x0960E1 to "Snapdragon X24 (SDX24)",
        0x0970E1 to "Snapdragon X24 (SDX24M)",
        0x0E50E1 to "Snapdragon X55 (SDX55)",
        0x0CF0E1 to "Snapdragon X55 (SDX55M)",
        0x07B0E1 to "Snapdragon X50 (SDX50M)",
        0x07D0E1 to "Snapdragon X20 (MDM9x60)",
        0x07F0E1 to "Snapdragon X24 (MDM9x65)",
        0x1250E1 to "Snapdragon (SA515M)",
        0x0F20E1 to "Snapdragon (SA4155P)",
        0x1510E1 to "Snapdragon (SA2150P)",
        0x1230E1 to "Snapdragon (SA8189P)",

        // Networking / Wi-Fi / IoT
        0x9440E1 to "Qualcomm QDF2432",
        0x9780E1 to "Qualcomm IPQ4018",
        0x9790E1 to "Qualcomm IPQ4019",
        0x1260E1 to "Qualcomm IPQ6018",
        0x1530E1 to "Qualcomm IPQ5018",
        0x0160E1 to "Qualcomm QCA4020",
        0x0AB0E1 to "Qualcomm QCA6290",
        0x0D90E1 to "Qualcomm QCA6390",
        0x1310E1 to "Qualcomm QCA6480",
        0x12E0E1 to "Qualcomm QCA6481",
        0x12D0E1 to "Qualcomm QCA6491",
        0x0D70E1 to "Qualcomm QCA6595",
        0x1A60E1 to "Qualcomm WCN7850",
        0x1A70E1 to "Qualcomm WCN7851",
        0x0AA0E1 to "Qualcomm QCS605",
        0x0EA0E1 to "Qualcomm SXR1130",
        0x0ED0E1 to "Qualcomm SXR1120",
        0x1860E1 to "Qualcomm QCS2290",
        0x1450E1 to "Qualcomm (agatti)",
        0x14F0E1 to "Qualcomm (agatti)",
        0x1850E1 to "Qualcomm (agatti IoT)",
        0x1590E1 to "Qualcomm (cedros)",
        0x1470E1 to "Qualcomm (moselle)",
        0x1440E1 to "Qualcomm (chitwan)",
        0x18A0E1 to "Qualcomm (fraser)",
        0x18B0E1 to "Qualcomm (qtang2)",
        0x1A90E1 to "Qualcomm (strait)",
        0x1A40E1 to "Qualcomm (Vordonisi)",
        0x1990E1 to "Qualcomm (OlympicLE)"
    )
}
