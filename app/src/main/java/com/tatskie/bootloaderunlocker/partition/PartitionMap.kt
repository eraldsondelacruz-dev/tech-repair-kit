package com.tatskie.bootloaderunlocker.partition

/**
 * Universal partition-map engine for the repair workstation.
 *
 * Understands the two text formats technicians meet every day:
 *
 *  [MTK_SCATTER]     MediaTek scatter-loading files (MT6xxx_android_scatter.txt) as
 *                    used by SP Flash Tool. Key/value blocks, hex addresses, and a
 *                    file_name entry per flashable partition.
 *
 *  [QC_RAWPROGRAM]   Qualcomm rawprogram*.xml as used by QPST / firehose tools.
 *                    XML <program> elements with start_sector / num_partition_sectors
 *                    and a filename attribute per image.
 *
 * The parser is intentionally dependency-free XML/KV parsing so it can be unit
 * tested on the JVM and reused by both the fastboot flasher and the EDL firehose
 * writer.
 */

enum class PartitionMapSource(val label: String) {
    MTK_SCATTER("MTK scatter"),
    QC_RAWPROGRAM("Qualcomm rawprogram"),
    UNKNOWN("Unknown")
}

data class PartitionEntry(
    val partitionName: String,
    val fileName: String?,
    /** Byte offset, when the map exposes a byte-addressable start (scatter). */
    val startAddress: Long?,
    /** Sector offset, when the map exposes a sector-based start (rawprogram). */
    val startSector: Long?,
    /** Total number of sectors for sector-based layouts. */
    val numSectors: Long?,
    /** Size in bytes, when known. */
    val sizeBytes: Long?,
    val physicalPartition: Int,
    val sectorSize: Int,
    val upgradable: Boolean,
    val hasImage: Boolean,
    val source: PartitionMapSource
) {
    val displayName: String
        get() = if (hasImage && fileName != null && fileName != partitionName) {
            "$partitionName ($fileName)"
        } else {
            partitionName
        }

    val startByteApprox: Long?
        get() = when {
            startAddress != null -> startAddress
            startSector != null && sectorSize > 0 -> startSector * sectorSize
            else -> null
        }
}

object PartitionMapParser {

    private const val QC_DEFAULT_SECTOR_SIZE = 512

    /** Detects which dialect a dropped-in text file speaks. */
    fun sniff(text: String): PartitionMapSource = when {
        text.contains("<program") -> PartitionMapSource.QC_RAWPROGRAM
        text.contains("partition_index") || text.contains("partition_name") ->
            PartitionMapSource.MTK_SCATTER
        else -> PartitionMapSource.UNKNOWN
    }

    /** Parses any supported map text, auto-detecting the dialect. */
    fun parse(text: String): List<PartitionEntry> = when (val source = sniff(text)) {
        PartitionMapSource.QC_RAWPROGRAM -> parseRawProgram(text)
        PartitionMapSource.MTK_SCATTER -> parseScatter(text)
        PartitionMapSource.UNKNOWN -> throw IllegalArgumentException(
            "Unrecognized partition map. Expect an MTK scatter .txt or a Qualcomm rawprogram*.xml."
        )
    }

    // ---------------------------------------------------------------------
    // MTK scatter format
    // ---------------------------------------------------------------------

    fun parseScatter(text: String): List<PartitionEntry> {
        val records = ArrayList<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "partition_index" -> {
                    current = LinkedHashMap<String, String>()
                    records.add(current)
                }
                "platform", "project_id", "hw_ver", "sw_ver", "beginner", "layout" -> {
                    // Global preamble - ignore.
                }
                else -> current?.put(key, value)
            }
        }
        return records.mapNotNull { fields ->
            val name = fields["partition_name"].takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val rawFile = fields["file_name"].takeUnless { it.isNullOrBlank() }
            val hasImage = rawFile != null && !rawFile.equals("none", ignoreCase = true) &&
                !rawFile.equals("", ignoreCase = true)
            val sizeBytes = firstNonNull(
                hexLong(fields["partition_size"]),
                hexLong(fields["size"]),
                hexLong(fields["begin_length"])
            )
            val start = firstNonNull(
                hexLong(fields["start_addr"]),
                hexLong(fields["start_address"]),
                hexLong(fields["physical_start_addr"]),
                hexLong(fields["begin_address"])
            )
            val physical = fields["physical_partition_number"]?.trim()?.toIntOrNull() ?: 0
            val region = fields["region"]?.lowercase().orEmpty()
            val physicalForRegion = when {
                region.contains("boot1") && physical == 0 -> 0
                region.contains("boot2") && physical == 0 -> 0
                else -> physical
            }
            PartitionEntry(
                partitionName = name,
                fileName = if (hasImage) rawFile else null,
                startAddress = start,
                startSector = null,
                numSectors = null,
                sizeBytes = sizeBytes,
                physicalPartition = physicalForRegion,
                sectorSize = 1,
                upgradable = fields["is_upgradable"]?.equals("true", ignoreCase = true)
                    ?: fields["is_download"]?.equals("true", ignoreCase = true) ?: true,
                hasImage = hasImage && sizeBytes != null,
                source = PartitionMapSource.MTK_SCATTER
            )
        }
    }

    // ---------------------------------------------------------------------
    // Qualcomm rawprogram format
    // ---------------------------------------------------------------------

    fun parseRawProgram(text: String): List<PartitionEntry> {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Throwable) {
        }
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        } catch (_: Throwable) {
        }
        val document = factory.newDocumentBuilder().parse(
            org.xml.sax.InputSource(java.io.StringReader(text))
        )
        val nodes = document.getElementsByTagName("program")
        val result = ArrayList<PartitionEntry>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val label = el.getAttribute("label").trim()
            val fileName = el.getAttribute("filename").trim().takeIf { it.isNotEmpty() }
            val sectorSize = el.getAttribute("SECTOR_SIZE_IN_BYTES").trim().toIntOrNull()
                ?: QC_DEFAULT_SECTOR_SIZE
            val startSector = hexLong(el.getAttribute("start_sector"))?.let { it }
            val numSectors = hexLong(el.getAttribute("num_partition_sectors"))
            val physical = el.getAttribute("physical_partition_number").trim().toIntOrNull() ?: 0
            val sparse = el.getAttribute("sparse").equals("true", ignoreCase = true)
            result.add(
                PartitionEntry(
                    partitionName = label.ifBlank { fileName ?: "unnamed" },
                    fileName = fileName,
                    startAddress = null,
                    startSector = startSector,
                    numSectors = numSectors,
                    sizeBytes = numSectors?.let { it * sectorSize },
                    physicalPartition = physical,
                    sectorSize = sectorSize,
                    upgradable = true,
                    hasImage = fileName != null && !sparse,
                    source = PartitionMapSource.QC_RAWPROGRAM
                )
            )
        }
        return result
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** Parses "0x1A2B" / "4194304" / "4K" style size tokens; null when absent. */
    private fun hexLong(raw: String?): Long? {
        if (raw == null) return null
        val value = raw.trim()
        if (value.isEmpty() || value.equals("none", ignoreCase = true)) return null
        var base = value
        var multiplier = 1L
        when (base.lastOrNull()?.lowercaseChar()) {
            'k' -> {
                multiplier = 1024L
                base = base.dropLast(1)
            }
            'm' -> {
                multiplier = 1024L * 1024
                base = base.dropLast(1)
            }
            'g' -> {
                multiplier = 1024L * 1024 * 1024
                base = base.dropLast(1)
            }
        }
        val core = base.trim()
        return try {
            if (core.startsWith("0x", ignoreCase = true)) {
                core.substring(2).toLong(16) * multiplier
            } else {
                core.toLong(10) * multiplier
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun firstNonNull(vararg values: Long?): Long? {
        for (v in values) if (v != null) return v
        return null
    }
}
