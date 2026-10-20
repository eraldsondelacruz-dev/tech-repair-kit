import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ApplicationWindow {
    id: window

    visible: true
    width: 420
    height: 800
    title: "BootloaderUnlocker"

    color: "#0b1020"

    property string message: "Ready. Check your device before proceeding."

    ScrollView {
        anchors.fill: parent
        clip: true

        ColumnLayout {
            width: window.width
            spacing: 16

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 125
                color: "#151e35"

                Column {
                    anchors.centerIn: parent
                    spacing: 8

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: "BOOTLOADER"
                        color: "#60a5fa"
                        font.pixelSize: 13
                        font.letterSpacing: 4
                    }

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: "UNLOCKER"
                        color: "white"
                        font.pixelSize: 28
                        font.bold: true
                    }

                    Text {
                        anchors.horizontalCenter: parent.horizontalCenter
                        text: "ANDROID TECHNICIAN TOOLKIT"
                        color: "#94a3b8"
                        font.pixelSize: 10
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                implicitHeight: deviceInfo.implicitHeight + 32
                radius: 14
                color: "#151e35"
                border.color: "#293854"

                ColumnLayout {
                    id: deviceInfo
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    Text {
                        text: "DEVICE INFORMATION"
                        color: "#60a5fa"
                        font.bold: true
                        font.pixelSize: 14
                    }

                    Text {
                        text: "System: " + deviceManager.operatingSystem()
                        color: "white"
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                    }

                    Text {
                        text: "Architecture: " + deviceManager.architecture()
                        color: "#cbd5e1"
                    }

                    Text {
                        text: "Bootloader: " + deviceManager.bootloaderStatus()
                        color: "#fbbf24"
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                    }

                    Text {
                        text: "Note: The device model may require native Android integration for accurate identification."
                        color: "#94a3b8"
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                        font.pixelSize: 12
                    }
                }
            }

            Text {
                Layout.leftMargin: 20
                text: "TECHNICIAN TOOLS"
                color: "#94a3b8"
                font.bold: true
                font.pixelSize: 13
            }

            Button {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                text: "Check Unlock Requirements"

                onClicked: {
                    window.message =
                        "1. Identify the exact phone model.\n\n" +
                        "2. Check the manufacturer's official bootloader-unlock policy.\n\n" +
                        "3. Back up important data.\n\n" +
                        "4. Enable OEM unlocking only if the device and owner authorize it.\n\n" +
                        "5. Verify the supported unlock method before executing any command."
                }
            }

            Button {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                text: "USB OTG / Fastboot Guide"

                onClicked: {
                    window.message =
                        "USB OTG guide:\n\n" +
                        "• Use a compatible USB host adapter.\n" +
                        "• Connect the target phone in the appropriate mode.\n" +
                        "• Grant USB access when Android asks.\n" +
                        "• USB detection alone does not mean Fastboot is available.\n\n" +
                        "This starter app does not yet communicate with Fastboot devices."
                }
            }

            Button {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                text: "Wireless ADB Guide"

                onClicked: {
                    window.message =
                        "Wireless ADB guide:\n\n" +
                        "• Enable Developer options on the authorized target device.\n" +
                        "• Enable Wireless debugging if supported.\n" +
                        "• Pair using Android's official pairing workflow.\n" +
                        "• Do not assume this app already has an ADB connection."
                }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                implicitHeight: resultText.implicitHeight + 32
                radius: 12
                color: "#111a2d"
                border.color: "#34435e"

                Text {
                    id: resultText
                    anchors.fill: parent
                    anchors.margins: 16
                    text: window.message
                    color: "#e2e8f0"
                    wrapMode: Text.Wrap
                    font.pixelSize: 14
                }
            }

            Button {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                Layout.bottomMargin: 24
                text: "Data Safety Warning"

                onClicked: {
                    window.message = deviceManager.safetyMessage()
                }
            }
        }
    }
}
