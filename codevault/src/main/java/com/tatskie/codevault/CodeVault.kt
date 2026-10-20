package com.tatskie.codevault

data class Code(val brand: String, val title: String, val dial: String, val danger: Boolean = false)

object CodeVault {
    val codes: List<Code> = listOf(
        // General / Android
        Code("General", "Phone info (signal/battery)", "*#*#4636#*#*"),
        Code("General", "IMEI", "*#06#"),
        Code("General", "Service menu", "*#*#7378423#*#*"),
        Code("General", "Service mode (rooted)", "*#*#197328640#*#*"),
        Code("General", "Wireless LAN test", "*#*#232339#*#*"),
        Code("General", "Touchscreen test", "*#*#2664#*#*", false),
        Code("General", "LCD test", "*#*#0*#*#*"),
        Code("General", "Audio loopback test", "*#*#0673#*#*"),
        Code("General", "Vibration + backlight test", "*#*#0842#*#*"),
        Code("General", "Bluetooth test", "*#*#232331#*#*"),
        Code("General", "GPS quick test", "*#*#1472365#*#*"),
        Code("General", "GPS other test", "*#*#1575#*#*"),
        Code("General", "RAM version", "*#*#3264#*#*"),
        Code("General", "Device build info", "*#*#44336#*#*"),
        Code("General", "FOTA/app version", "*#*#2222#*#*"),
        Code("General", "HSD/DSN debug", "*#*#528#*#*"),
        Code("General", "Factory reset (data)", "##7780##", true),
        Code("General", "FULL WIPE — factory image reset", "*2767*3855#", true),
        // Samsung
        Code("Samsung", "Hardware test menu", "*#0#*"),
        Code("Samsung", "Battery status (ADC)", "*#0228#"),
        Code("Samsung", "Firmware version", "*#1234#"),
        Code("Samsung", "SW/HW info", "*#12580*369#"),
        Code("Samsung", "Quick test menu", "*#7353#"),
        Code("Samsung", "SysDump menu", "*#9900#"),
        Code("Samsung", "Firmware update check", "*#2663#"),
        Code("Samsung", "USB 12C control", "*#7284#"),
        Code("Samsung", "Touchscreen version", "*#2666#"),
        Code("Samsung", "Bluetooth address", "*#232337#"),
        Code("Samsung", "Band selection", "*#2263#"),
        Code("Samsung", "Wi-Fi MAC", "*#*#232338#*#*"),
        Code("Samsung", "DRM status", "*#3424#"),
        Code("Samsung", "HSD control", "*#301279#"),
        // Xiaomi / MIUI
        Code("Xiaomi", "Hardware test (CIT)", "*#*#64663#*#*"),
        Code("Xiaomi", "Hardware test alt", "*#*#6484#*#*"),
        Code("Xiaomi", "Phone info", "*#*#4636#*#*"),
        Code("Xiaomi", "Mi account check", "*#*#555#*#*"),
        Code("Xiaomi", "Version info", "*#*#77461#*#*"),
        // OPPO / realme (ColorOS)
        Code("Oppo/Realme", "Engineering mode", "*#899#"),
        Code("Oppo/Realme", "Software version menu", "*#6776#"),
        Code("Oppo/Realme", "Firmware version", "*#1234#"),
        Code("Oppo/Realme", "MTK engineer mode", "*#*#36446337#*#*"),
        Code("Oppo/Realme", "Qualcomm test menu", "*#808#"),
        // Vivo (Funtouch)
        Code("Vivo", "Engineering test menu", "*#558#"),
        Code("Vivo", "Version info", "*#*#4838#*#*"),
        // MediaTek (universal)
        Code("MTK", "Engineer mode", "*#*#3646633#*#*"),
        Code("MTK", "MTK engineer mode v2", "*#*#36446337#*#*"),
        Code("MTK", "MTK log mode", "*#*#3649515#*#*"),
        Code("MTK", "MTK test/sensor", "#*#6484#*#*"),
        // Asus
        Code("Asus", "Specs + warranty info", "*#07#"),
        Code("Asus", "Service menu", "*#*#7378423#*#*"),
        // Qualcomm (universal)
        Code("Qualcomm", "Service menu", "*#*#7378423#*#*"),
        Code("Qualcomm", "Engineering test", "*#808#"),
        Code("Qualcomm", "Band lock menu", "*#*#2263#*#*")
    )

    fun brands(): List<String> =
        listOf("All") + codes.map { it.brand }.distinct()
}
