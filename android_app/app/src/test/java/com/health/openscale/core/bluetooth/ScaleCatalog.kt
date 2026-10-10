/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth

import android.util.SparseArray
import com.health.openscale.core.bluetooth.scales.DeviceCapability
import com.health.openscale.core.bluetooth.scales.LinkMode
import com.health.openscale.core.bluetooth.scales.MiScaleHandler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID

/**
 * The one place a supported scale is written down.
 *
 * [fixtures] pairs a synthetic advertisement with the handler that must claim it, and serves two
 * jobs at once:
 *
 *  - `ScaleFactoryTest` asserts that the real registry — [ScaleFactory.createHandlers] in its real
 *    order, first match wins — still routes each device to its own driver.
 *  - `ScaleCatalogTest` asks the winning handler for its
 *    [com.health.openscale.core.bluetooth.scales.DeviceSupport] and renders the wiki page
 *    "Supported scales in openScale" from it.
 *
 * So adding a scale is one line here on top of the handler itself: the regression test and the
 * published table follow automatically. Nothing about a device is written down twice — display
 * name, link mode and capabilities all come from `supportFor`.
 *
 * Advertisements are built from the names and fingerprints documented in the handlers themselves,
 * never from personal captures.
 *
 * Human-written notes for the table's "Remarks" column live in
 * `src/test/resources/scale_catalog_remarks.txt`, the photo gallery in `scale_catalog_gallery.md`.
 *
 * Besides the catalog itself this object owns the shared building blocks every scale test needs —
 * [uuid16], [device], [advertisement], [manufacturerData] and [hex]. Import them instead of
 * redeclaring a private copy, so a captured frame reads the same way in every test:
 *
 *     import com.health.openscale.core.bluetooth.ScaleCatalog.hex
 *
 * The one deliberate exception is `HuaweiAhCh100HandlerTest`, whose `hex` delegates to the
 * handler's own `hexToBytes` because that conversion is production code under test.
 */
object ScaleCatalog {

    // --- Fixtures ---------------------------------------------------------------------------

    /** An advertisement together with the handler that has to claim it. */
    data class Fixture(
        val device: ScannedDeviceInfo,
        val handler: Class<out ScaleDeviceHandler>,
    )

    fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    /** A connectable device: a name and, optionally, the services it advertises. */
    fun device(name: String, vararg services: UUID) = ScannedDeviceInfo(
        name = name,
        address = "C0:FF:EE:12:34:56",
        rssi = -50,
        serviceUuids = services.toList(),
        manufacturerData = null,
    )

    /**
     * A captured frame written as hex, e.g. `hex("5a 0a 26 10")` or `hex("5A0A2610")`.
     * Whitespace and an optional `0x` prefix are ignored, so a payload can be pasted verbatim
     * from nRF Connect or a BTSnoop dump.
     */
    fun hex(value: String): ByteArray = value
        .removePrefix("0x")
        .filterNot { it.isWhitespace() }
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    /** Manufacturer records keyed by company id, shaped the way `ScanRecord` hands them over. */
    fun manufacturerData(vararg entries: Pair<Int, ByteArray>): SparseArray<ByteArray> =
        SparseArray<ByteArray>().apply { entries.forEach { (id, data) -> put(id, data) } }

    /** A broadcast advertisement: manufacturer records keyed by company id, as `ScanRecord` has them. */
    fun advertisement(
        name: String = "",
        services: List<UUID> = emptyList(),
        manufacturerData: List<Pair<Int, ByteArray>> = emptyList(),
    ) = ScannedDeviceInfo(
        name = name,
        address = "C0:FF:EE:12:34:56",
        rssi = -50,
        serviceUuids = services,
        manufacturerData = manufacturerData(*manufacturerData.toTypedArray()),
    )

    /** Service 0xFFB0 — the LeFu-style service shared by a whole family of unrelated scales. */
    private val SERVICE_FFB0 = uuid16(0xFFB0)

    /** Etekcity's company id; with service 0xFFD0 the only fingerprint of the nameless Fit 8S. */
    private const val ETEKCITY_COMPANY_ID = 0x06D0

    /** Not a registered company id at all: AiLink packs its CID (0x01) and VID (0x03) here. */
    private const val AILINK_COMPANY_ID = 0x0301

    /**
     * An AiLink advertisement carrying a completed measurement. Its payload is encrypted and
     * checksummed, so unlike most fixtures here it cannot be a zero-filled placeholder — the
     * handler only claims records that actually decrypt. Synthesised with the protocol's own
     * cipher over an invented reading; see `AiLinkLibTest`.
     */
    private val AILINK_RECORD = byteArrayOf(
        0x01,                                                     // PID
        0x5F, 0x4E, 0x3D, 0x2C, 0x1B, 0x0A,                       // MAC, little-endian
        0x8F.toByte(),                                            // checksum of the payload
        0x10, 0xE6.toByte(), 0x9C.toByte(), 0xF7.toByte(),        // TEA-encrypted payload
        0xCB.toByte(), 0x45, 0x2C, 0xCC.toByte(),
        0xFF.toByte(), 0xFF.toByte(),
    )

    private infix fun ScannedDeviceInfo.claimedBy(handler: Class<out ScaleDeviceHandler>) =
        Fixture(this, handler)

    /**
     * One entry per supported device.
     */
    val fixtures: List<Fixture> = listOf(
        device("MIBFS") claimedBy MiScaleHandler::class.java,
        device("MIBCS") claimedBy MiScaleHandler::class.java,
        device("MI SCALE2") claimedBy MiScaleHandler::class.java,
        device("Xiaomi Body Composition Scale 2", MiScaleHandler.SERVICE_MI_CFG) claimedBy MiScaleHandler::class.java,
    )

    // --- Registry queries -------------------------------------------------------------------

    /**
     * The handler [ScaleFactory.createCommunicator] would pick for [device], or null.
     *
     * A fresh registry per call: several handlers stash state in `supportFor` (e.g.
     * StandardBeurerSanitasHandler remembers the matched model), so a shared instance would let one
     * fixture bleed into the next.
     */
    fun winner(device: ScannedDeviceInfo): ScaleDeviceHandler? =
        ScaleFactory.createHandlers().firstOrNull { it.supportFor(device) != null }

    /** Every handler that claims [device] — more than one means two drivers overlap. */
    fun claimants(device: ScannedDeviceInfo): List<ScaleDeviceHandler> =
        ScaleFactory.createHandlers().filter { it.supportFor(device) != null }

    // --- Catalog ----------------------------------------------------------------------------

    /** One published row: what the winning handler reports for one fixture. */
    data class Row(
        val displayName: String,
        val handler: String,
        val linkMode: LinkMode,
        val capabilities: Set<DeviceCapability>,
        val implemented: Set<DeviceCapability>,
    )

    /** Runs every fixture through the registry and collects what the winning handler reports. */
    fun rows(): List<Row> =
        fixtures.mapNotNull { fixture ->
            ScaleFactory.createHandlers().firstNotNullOfOrNull { handler ->
                handler.supportFor(fixture.device)?.let { support ->
                    Row(
                        displayName = support.displayName,
                        handler = handler.javaClass.simpleName,
                        linkMode = support.linkMode,
                        capabilities = support.capabilities,
                        implemented = support.implemented,
                    )
                }
            }
        }
            .distinctBy { it.displayName }
            .sortedBy { it.displayName.lowercase() }

    // --- Rendering --------------------------------------------------------------------------

    /** Where a handler's source file lives, relative to the repository root. */
    fun sourcePath(handler: String): String =
        "android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/$handler.kt"

    private const val SOURCE_BASE_URL = "https://github.com/oliexdev/openScale/blob/master"

    /** A literal pipe in a display name or remark would split the markdown cell. */
    private fun cell(text: String): String = text.replace("|", "\\|")

    private fun mark(row: Row, capability: DeviceCapability): String = when {
        capability in row.implemented -> "&#10003;"   // ✓ supported in openScale
        capability in row.capabilities -> "o"         // offered by the scale, not implemented yet
        else -> "n/a"                                 // not available on the scale
    }

    private fun linkLabel(mode: LinkMode): String = when (mode) {
        LinkMode.CONNECT_GATT -> "BLE GATT"
        LinkMode.BROADCAST_ONLY -> "BLE Broadcast"
        // SPP runs over RFCOMM on Bluetooth Classic (BR/EDR), not over BLE — the distinction
        // matters to users because those scales pair differently.
        LinkMode.CLASSIC_SPP -> "Classic SPP"
    }

    /** The capability columns, in the order they appear in the table. */
    private val CAPABILITY_COLUMNS = listOf(
        DeviceCapability.BODY_COMPOSITION to "Body metrics",
        DeviceCapability.HISTORY_READ to "History data",
        DeviceCapability.LIVE_WEIGHT_STREAM to "Live weight",
        DeviceCapability.TIME_SYNC to "Time sync",
        DeviceCapability.USER_SYNC to "User sync",
        DeviceCapability.UNIT_CONFIG to "Unit config",
        DeviceCapability.BATTERY_LEVEL to "Battery",
    )

    /**
     * Renders the wiki page. [remarks] is keyed by display name, falling back to the handler class
     * name so one note can cover every product a driver reports.
     */
    fun renderMarkdown(rows: List<Row>, remarks: Map<String, String>): String = buildString {
        appendLine("## Scale support overview")
        appendLine()
        appendLine("<!--")
        appendLine("  GENERATED FILE — do not edit the table by hand.")
        appendLine("  Produced by ScaleCatalogTest from the handler registry (ScaleFactory.createHandlers).")
        appendLine("  CI regenerates it on every push to master; edits made here are overwritten.")
        appendLine("  To change a row, change the handler's supportFor(); to change a remark or the")
        appendLine("  photo gallery, edit android_app/app/src/test/resources/scale_catalog_remarks.txt")
        appendLine("  or scale_catalog_gallery.md. To render it locally:")
        appendLine("    SCALE_CATALOG_OUT=<wiki>/Supported-scales-in-openScale.md ./gradlew testDebugUnitTest --tests '*ScaleCatalogTest'")
        appendLine("-->")
        appendLine()
        appendLine("> [!IMPORTANT]")
        appendLine("> - I do **not own** every scale.")
        appendLine("> - openScale is an **open-source, community-driven project**.")
        appendLine("> - Some scales may be **incompatible**, and I cannot guarantee that all features will work.")
        appendLine("> - Please understand that this project is maintained **voluntarily**, and there is **no official warranty or support**.")
        appendLine("> - Users should have **realistic expectations**: not every scale will work perfectly, and encountering issues is normal.")
        appendLine("> - Bluetooth protocols can **change at any time**, and some cheap scales are **poorly implemented by the manufacturer**.")
        appendLine("> - openScale relies on user contributions. If you can help test scales, improve support, or fix issues, **pull requests are very welcome**.")
        appendLine()
        appendLine("> [!NOTE]")
        appendLine("> If you want to help support your Bluetooth scale, please read [How to support a new scale](How-to-support-a-new-scale) for further information.")
        appendLine()

        val header = listOf("Scale", "Handler", "Connection") +
            CAPABILITY_COLUMNS.map { it.second } +
            listOf("Remarks")
        appendLine(header.joinToString(" | ", prefix = "| ", postfix = " |"))
        appendLine(header.joinToString("|", prefix = "|", postfix = "|") { "---" })

        for (row in rows) {
            val remark = remarks[row.displayName] ?: remarks[row.handler] ?: "-"
            val cells = listOf(
                cell(row.displayName),
                "[${row.handler}]($SOURCE_BASE_URL/${sourcePath(row.handler)})",
                linkLabel(row.linkMode),
            ) + CAPABILITY_COLUMNS.map { (capability, _) -> mark(row, capability) } +
                listOf(cell(remark))
            appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " |"))
        }

        appendLine()
        appendLine("&#10003; : supported in openScale <br>")
        appendLine("o : supported by the scale but still needs to be reverse engineered<br>")
        appendLine("n/a : not available on the scale")
        appendLine()
        appendLine("_${rows.size} scales, generated from ${ScaleFactory.createHandlers().size} registered handlers._")

        // The photo gallery is hand-curated (there is nothing in the code to derive a picture
        // from), so it is carried through verbatim rather than regenerated.
        loadGallery()?.let {
            appendLine()
            append(it)
        }
    }

    /**
     * Reads the hand-written remarks. Format is one `displayName = remark` per line; `#` starts a
     * comment. Deliberately not JSON — the file is edited by hand, and a stray comma should not
     * cost a maintainer a test run.
     */
    fun loadRemarks(): Map<String, String> {
        val stream = javaClass.classLoader?.getResourceAsStream(REMARKS_RESOURCE) ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()
        }
    }

    /**
     * The hand-curated "Detailed information" photo gallery, appended below the generated table.
     * Edit it as plain markdown; it is copied through untouched.
     */
    fun loadGallery(): String? =
        javaClass.classLoader?.getResourceAsStream(GALLERY_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }

    const val REMARKS_RESOURCE = "scale_catalog_remarks.txt"

    const val GALLERY_RESOURCE = "scale_catalog_gallery.md"
}
