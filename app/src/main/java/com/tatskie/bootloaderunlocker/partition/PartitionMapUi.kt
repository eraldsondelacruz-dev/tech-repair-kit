package com.tatskie.bootloaderunlocker.partition

import android.app.AlertDialog
import android.content.Context

/**
 * Small UI helpers shared by the Fastboot Flasher and the EDL Firehose screens
 * so both can browse a parsed partition map the same way.
 */
object PartitionMapUi {

    fun humanBytes(bytes: Long): String = when {
        bytes >= 1073741824L -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1048576L -> "%.2f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "${bytes} B"
    }

    fun describeEntry(entry: PartitionEntry): String = buildString {
        append(entry.partitionName)
        append(" → ")
        append(entry.fileName ?: "(no image)")
        val size = entry.sizeBytes
        if (size != null && size > 0) append("  ·  ${humanBytes(size)}")
        when (entry.source) {
            PartitionMapSource.QC_RAWPROGRAM -> {
                entry.startSector?.let { append("  ·  sector $it") }
                if (entry.physicalPartition != 0) append("  ·  phys ${entry.physicalPartition}")
            }
            PartitionMapSource.MTK_SCATTER -> {
                entry.startAddress?.let { append("  ·  0x%08X".format(it)) }
            }
            else -> {}
        }
    }

    /**
     * Shows every parsed partition in a choice dialog and calls back the picked entry.
     */
    fun showPicker(context: Context, entries: List<PartitionEntry>, onPicked: (PartitionEntry) -> Unit) {
        if (entries.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(context.getString(com.tatskie.bootloaderunlocker.R.string.uf_map_title))
                .setMessage(context.getString(com.tatskie.bootloaderunlocker.R.string.uf_map_empty))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val items = entries.map { describeEntry(it) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(context.getString(com.tatskie.bootloaderunlocker.R.string.uf_map_title))
            .setItems(items) { _, which -> onPicked(entries[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
