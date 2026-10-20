# Tech Repair Kit — Bootloader Workstation

A phone-as-workstation app for mobile technicians: turn any USB-OTG-capable Android
phone into the repair host that normally requires a PC. Check bootloader state,
unlock/relock over fastboot-OTG, dissect OTA payloads, drive EDL/miTool flows
and flash partitions — no computer needed.

## Screens

| Screen | Purpose |
|---|---|
| **Main** | Device info + bootloader status (verified boot state, OEM unlock) |
| **Should I unlock?** | Decision checklist with risks |
| **Unlock / Relock guide** | Step-by-step with copyable commands |
| **Extract boot.img (MediaTek)** | MTK BROM/USB bridge chip identify + boot image extraction |
| **Payload Dumper** | Open an OTA `payload.bin` and extract partitions (boot, system, vendor …) |
| **Detect Qualcomm EDL** | 9008 Sahara identify: platform (from MSM HW ID), serial, PK hash |
| **Root & Privileges** | KernelSU/APatch/Magisk detection + privileged inspection |
| **Fastboot over OTG** | Unlock, relock and flash across the OTG cable |
| **Universal Flasher** ⭐ | Load an **MTK `scatter.txt`** or **Qualcomm `rawprogram*.xml`**, browse the partition table, then FLASH / ERASE / FETCH any partition |
| **EDL Firehose Flasher** ⭐ | Boot a firehose programmer (`.elf`/`.mbn`) into a 9008 phone via Sahara, then NOP / CONFIGURE / PROGRAM / ERASE / **READBACK** / RESET — QPST without QPST |

## Typical workflows

### Unlock a second phone (the classic mission)
1. Check the target's bootloader status from *Main*.
2. Enable **OEM unlocking** (+ Realme/Oppo Deep Testing) on the target.
3. Target in fastboot mode → *Fastboot over OTG* → `flashing unlock`.
4. Confirm on the target screen, reconnect, flash Magisk-patched `boot.img` via
   *Universal Flasher*.

### Bootless-brick rescue (Qualcomm)
1. Target powered off → plug the OTG cable holding **Vol Up + Vol Down**
   (EDL/9008).
2. *EDL Firehose Flasher* → pick your `firehose.elf`/`.mbn` → **Boot programmer**.
3. Load `rawprogram*.xml` → pick a partition (sector/length filled for you).
4. **READBACK** a partition to a backup file *before* touching it.
5. **PROGRAM** a good image, or **ERASE** a corrupt one, then **RESET**.

### Payload surgery
*Extract OTA payload (payload.bin)* → select a full OTA zip's `payload.bin` →
choose an output folder → dump `boot`, `vbmeta`, whatever you need.

## Build

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

CI builds every push to `main` / `dev` and uploads the APK as an artifact;
when you tag a release, the pipeline also deposits the APK and posts a notice
if `TELEGRAM_BOT_TOKEN` + `TELEGRAM_CHAT_ID` secrets are present.

## Install notes (technician)

* Install app-debug (debug-signed) builds only after uninstalling any
  conflict-signed copy of this package (`com.tatskie.bootloaderunlocker`).
* Flashing / unlocking wipes the target phone. Backup partitions with
  READBACK before ERASE/PROGRAM. Never relock a modified firmware.
* Android users must disable Play-Protect "scan apps" warnings if your work
  phone blocks sideloads.

## Credits

Sahara/EDL tables, scatter and firehose parsing rewritten for on-device use —
inspired by the operations field technicians already know from SPFT / QPST /
fh_loader.
