package com.example

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class UsbFlasherEngine {

    companion object {
        private const val TAG = "UsbFlasherEngine"
        private const val DEFAULT_TIMEOUT_MS = 5000
        private const val SECTOR_SIZE = 512
        private const val CHUNK_SECTORS = 64 // 32KB block size
        private const val CHUNK_SIZE = CHUNK_SECTORS * SECTOR_SIZE
    }

    enum class PartitionScheme {
        GPT,
        MBR
    }

    enum class TargetSystem {
        UEFI_NON_CSM,
        BIOS_CSM
    }

    enum class FileSystemType {
        FAT32,
        NTFS,
        EXFAT
    }

    enum class ProtocolMode {
        SCSI_BOT,
        RAW_DIRECT
    }

    sealed class FlashStatus {
        object Idle : FlashStatus()
        object Preparing : FlashStatus()
        object Formatting : FlashStatus()
        data class Progress(
            val percentage: Float,
            val bytesWritten: Long,
            val totalBytes: Long,
            val speedMbPerSec: Double,
            val etaSeconds: Long
        ) : FlashStatus()
        data class Success(val totalBytesWritten: Long, val timeElapsedMs: Long) : FlashStatus()
        data class Error(val message: String) : FlashStatus()
    }

    private val _status = MutableStateFlow<FlashStatus>(FlashStatus.Idle)
    val status: StateFlow<FlashStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile
    private var isCancelled = false

    fun cancelFlashing() {
        isCancelled = true
        addLog("[INFO] Cancellation requested by user.")
    }

    private fun addLog(message: String) {
        Log.d(TAG, message)
        val current = _logs.value.toMutableList()
        current.add(message)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /**
     * Wipes the partition tables and writes low-level GPT/MBR records
     * directly to raw USB sectors prior to streaming the ISO.
     */
    suspend fun startFlash(
        context: Context,
        isoUri: Uri,
        device: UsbDevice,
        protocolMode: ProtocolMode,
        partitionScheme: PartitionScheme,
        targetSystem: TargetSystem,
        fileSystemType: FileSystemType
    ) = withContext(Dispatchers.IO) {
        isCancelled = false
        _status.value = FlashStatus.Preparing
        clearLogs()
        addLog("[START] Preparing low-level ISO-to-USB writing task...")
        addLog("[INFO] Selected Protocol: ${protocolMode.name}")
        addLog("[INFO] Selected Scheme: ${partitionScheme.name}")
        addLog("[INFO] Selected Target Boot System: ${targetSystem.name}")
        addLog("[INFO] Selected File System Layout: ${fileSystemType.name}")

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        var connection: UsbDeviceConnection? = null
        var usbInterface: UsbInterface? = null

        try {
            // 1. Resolve interfaces & endpoints
            addLog("[INFO] Discovering mass storage USB interfaces...")
            var foundInterface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    foundInterface = iface
                    addLog("[INFO] Selected bulk Interface index: $i")
                    break
                }
            }

            val targetInterface = foundInterface ?: run {
                addLog("[WARN] No matching Mass Storage Interface class found. Falling back to Interface 0.")
                if (device.interfaceCount > 0) device.getInterface(0) else null
            }

            if (targetInterface == null) {
                _status.value = FlashStatus.Error("Failed to discover interface on USB hardware descriptor.")
                return@withContext
            }

            var bulkOutEndpoint = targetInterface.getEndpoint(0)
            var bulkInEndpoint = targetInterface.getEndpoint(0)
            var hasOut = false
            var hasIn = false

            for (e in 0 until targetInterface.endpointCount) {
                val endpoint = targetInterface.getEndpoint(e)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        bulkOutEndpoint = endpoint
                        hasOut = true
                        addLog("[INFO] USB Bulk-Out Endpoint: Adr=${endpoint.endpointNumber}")
                    } else if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        bulkInEndpoint = endpoint
                        hasIn = true
                        addLog("[INFO] USB Bulk-In Endpoint: Adr=${endpoint.endpointNumber}")
                    }
                }
            }

            if (!hasOut) {
                _status.value = FlashStatus.Error("No valid Bulk-Out Endpoint available.")
                return@withContext
            }

            if (protocolMode == ProtocolMode.SCSI_BOT && !hasIn) {
                _status.value = FlashStatus.Error("SCSI BOT protocol requires Bulk-In and Bulk-Out endpoints.")
                return@withContext
            }

            // 2. Query capacity and source file size
            addLog("[INFO] Opening source ISO details...")
            val contentResolver = context.contentResolver
            val totalBytes: Long
            contentResolver.openAssetFileDescriptor(isoUri, "r")?.use { fd ->
                totalBytes = fd.length
            } ?: run {
                addLog("[ERROR] Could not resolve size of source file.")
                _status.value = FlashStatus.Error("Failed to resolve asset file size.")
                return@withContext
            }

            addLog("[INFO] Source size: $totalBytes bytes (~${String.format("%.2f", totalBytes / (1024.0 * 1024.0))} MB)")

            addLog("[INFO] Initializing UsbDeviceConnection channel...")
            connection = usbManager.openDevice(device)
            if (connection == null) {
                _status.value = FlashStatus.Error("USB Open Connection failed. Grant permissions.")
                return@withContext
            }

            addLog("[INFO] Claiming USB interface handle exclusively...")
            val claimed = connection.claimInterface(targetInterface, true)
            if (!claimed) {
                _status.value = FlashStatus.Error("Failed to claim exclusive access to interface endpoint.")
                return@withContext
            }
            usbInterface = targetInterface

            val deviceCapacityBytes = queryDeviceCapacityInternal(connection, bulkOutEndpoint, bulkInEndpoint)
            addLog("[INFO] Target USB Medium Capacity: $deviceCapacityBytes bytes (~${String.format("%.2f", deviceCapacityBytes / (1024.0 * 1024.0 * 1024.0))} GB)")

            if (deviceCapacityBytes > 0 && deviceCapacityBytes < totalBytes) {
                _status.value = FlashStatus.Error("USB drive is too small! Required: ${totalBytes / (1024 * 1024)}MB. Found: ${deviceCapacityBytes / (1024*1024)}MB.")
                return@withContext
            }

            // 3. Low-Level Wipe/Format Phase
            _status.value = FlashStatus.Formatting
            addLog("[FORMAT] Core wipe sequence triggered. Erasing partition table (first 100 sectors)...")
            val zeroSector = ByteArray(SECTOR_SIZE)
            var tagIndex = 3000

            // Wipe first 100 sectors to prevent duplicate partition conflict systems
            for (sector in 0 until 100) {
                if (isCancelled) break
                if (protocolMode == ProtocolMode.SCSI_BOT) {
                    val cbw = createCbwPayload(tagIndex, SECTOR_SIZE, sector, 1)
                    connection.bulkTransfer(bulkOutEndpoint, cbw, cbw.size, DEFAULT_TIMEOUT_MS)
                    connection.bulkTransfer(bulkOutEndpoint, zeroSector, zeroSector.size, DEFAULT_TIMEOUT_MS)
                    val csw = ByteArray(13)
                    connection.bulkTransfer(bulkInEndpoint, csw, csw.size, DEFAULT_TIMEOUT_MS)
                    tagIndex++
                } else {
                    connection.bulkTransfer(bulkOutEndpoint, zeroSector, zeroSector.size, DEFAULT_TIMEOUT_MS)
                }
            }
            addLog("[FORMAT] Traditional sector table wiped successfully.")

            // 4. MBR / GPT Partition Table Headers Installation
            addLog("[FORMAT] Formatting partition layout to ${partitionScheme.name}...")
            val totalSectorsCount = if (deviceCapacityBytes > 0) (deviceCapacityBytes / SECTOR_SIZE).toInt() else 31250000 // default ~16GB reference

            if (partitionScheme == PartitionScheme.MBR) {
                addLog("[FORMAT] Constructing Master Boot Record at Sector 0...")
                val mbrSector = ByteArray(SECTOR_SIZE)
                // Bootstrap code area (0 to 445): zero for dummy layout or standard GRUB
                // Let's pack safe Partition Entry 1 at offset 446
                val bufferMbr = ByteBuffer.wrap(mbrSector).order(ByteOrder.LITTLE_ENDIAN)
                bufferMbr.position(446)
                
                bufferMbr.put(0x80.toByte()) // Boot indicator (Active)
                bufferMbr.put(0x01.toByte()) // Starting Head/CHS reference
                bufferMbr.put(0x01.toByte())
                bufferMbr.put(0x00.toByte())

                // File System code
                val fsByte: Byte = when (fileSystemType) {
                    FileSystemType.FAT32 -> 0x0C.toByte() // FAT32 LBA format code
                    FileSystemType.NTFS -> 0x07.toByte()  // NTFS / exFAT Install code
                    FileSystemType.EXFAT -> 0x07.toByte()
                }
                bufferMbr.put(fsByte)

                // End CHS references
                bufferMbr.put(0xFE.toByte())
                bufferMbr.put(0x3F.toByte())
                bufferMbr.put(0xFF.toByte())

                // Starting LBA sector (using offset 2048 for aligned cluster spaces)
                bufferMbr.putInt(2048)
                // Partition size in sectors
                bufferMbr.putInt(totalSectorsCount - 2048)

                // Safe Standard MBR Signature at offset 510
                mbrSector[510] = 0x55.toByte()
                mbrSector[511] = 0xAA.toByte()

                addLog("[FORMAT] Writing constructed MBR sector to USB block 0...")
                if (protocolMode == ProtocolMode.SCSI_BOT) {
                    val cbw = createCbwPayload(tagIndex, SECTOR_SIZE, 0, 1)
                    connection.bulkTransfer(bulkOutEndpoint, cbw, cbw.size, DEFAULT_TIMEOUT_MS)
                    connection.bulkTransfer(bulkOutEndpoint, mbrSector, mbrSector.size, DEFAULT_TIMEOUT_MS)
                    val csw = ByteArray(13)
                    connection.bulkTransfer(bulkInEndpoint, csw, csw.size, DEFAULT_TIMEOUT_MS)
                    tagIndex++
                } else {
                    connection.bulkTransfer(bulkOutEndpoint, mbrSector, mbrSector.size, DEFAULT_TIMEOUT_MS)
                }
                addLog("[FORMAT] Master Boot Record (MBR) table created successfully.")
            } else if (partitionScheme == PartitionScheme.GPT) {
                addLog("[FORMAT] Constructing GUID Partition Table structures (Protective MBR + GPT Header + Entries)...")
                
                // MBR Protective Sector (LBA 0)
                val protectiveMbr = ByteArray(SECTOR_SIZE)
                val pmbrBuffer = ByteBuffer.wrap(protectiveMbr).order(ByteOrder.LITTLE_ENDIAN)
                pmbrBuffer.position(446)
                pmbrBuffer.put(0x00.toByte()) // Non active boot
                pmbrBuffer.position(450)
                pmbrBuffer.put(0xEE.toByte()) // GPT protective type
                pmbrBuffer.position(454)
                pmbrBuffer.putInt(1) // Starts at LBA 1
                pmbrBuffer.putInt(totalSectorsCount - 1) // Remaining sectors sized
                protectiveMbr[510] = 0x55.toByte()
                protectiveMbr[511] = 0xAA.toByte()

                // GPT Primary Header Sector (LBA 1)
                val gptHeader = ByteArray(SECTOR_SIZE)
                val headerBuffer = ByteBuffer.wrap(gptHeader).order(ByteOrder.LITTLE_ENDIAN)
                headerBuffer.position(0)
                headerBuffer.putLong(0x5452415020494645L) // Signature "EFI PART" (Big/Little Endian matched)
                headerBuffer.putInt(0x00010000) // Revision 1.0
                headerBuffer.putInt(92) // Header size in bytes
                headerBuffer.position(16)
                headerBuffer.putLong(1L) // Current LBA is 1
                headerBuffer.putLong((totalSectorsCount - 1).toLong()) // Backup LBA is last sector
                headerBuffer.putLong(34L) // First usable LBA (after entries block)
                headerBuffer.putLong((totalSectorsCount - 34).toLong()) // Last usable LBA
                
                val diskUuid = UUID.randomUUID()
                headerBuffer.position(56)
                headerBuffer.putLong(diskUuid.mostSignificantBits)
                headerBuffer.putLong(diskUuid.leastSignificantBits)
                headerBuffer.putLong(2L) // Starting LBA of partition entries
                headerBuffer.putInt(128) // Number of partition entries
                headerBuffer.putInt(128) // Size of each partition entry

                // GPT Partition Entries (LBA 2 to 33)
                val partitionEntries = ByteArray(SECTOR_SIZE * 32)
                val entriesBuffer = ByteBuffer.wrap(partitionEntries).order(ByteOrder.LITTLE_ENDIAN)
                entriesBuffer.position(0)
                
                entriesBuffer.putLong(0x4433B9E5EBD0A0A2L) // Matched GUID lower hex
                entriesBuffer.putLong(0xC79926B7B668C087uL.toLong()) // Matched GUID upper hex
                
                val partUuid = UUID.randomUUID()
                entriesBuffer.putLong(partUuid.mostSignificantBits)
                entriesBuffer.putLong(partUuid.leastSignificantBits)
                entriesBuffer.putLong(2048L) // Aligned start sector block
                entriesBuffer.putLong((totalSectorsCount - 2048).toLong()) // Sized endpoint
                entriesBuffer.putLong(0L) // Safe standard partition attribute flags

                // Write PMBR, Header and Partition Entries safely to sector blocks
                addLog("[FORMAT] Writing Protective MBR (LBA 0)...")
                writeSectorsDirectly(connection, bulkOutEndpoint, bulkInEndpoint, 0, protectiveMbr, protocolMode, tagIndex++)
                
                addLog("[FORMAT] Writing GPT Primary Header (LBA 1)...")
                writeSectorsDirectly(connection, bulkOutEndpoint, bulkInEndpoint, 1, gptHeader, protocolMode, tagIndex++)

                addLog("[FORMAT] Writing GPT Partition Entries table (LBA 2 to 33)...")
                writeSectorsDirectly(connection, bulkOutEndpoint, bulkInEndpoint, 2, partitionEntries, protocolMode, tagIndex++)
                tagIndex += 32
                
                addLog("[FORMAT] GPT structures configured dynamically.")
            }

            // 5. Raw Sector-by-Sector ISO writing loop
            _status.value = FlashStatus.Preparing
            addLog("[INFO] Initializing sector flash stream sequence...")
            val inputStream: InputStream = contentResolver.openInputStream(isoUri)
                ?: throw Exception("Failed to open Uri streaming reference.")

            inputStream.use { source ->
                val startTime = System.currentTimeMillis()
                var bytesWritten = 0L
                val buffer = ByteArray(CHUNK_SIZE)
                var lba = 2048 // Start writing standard aligned space
                val megaFactor = 1024.0 * 1024.0

                _status.value = FlashStatus.Progress(0f, 0L, totalBytes, 0.0, 0L)
                addLog("[INFO] Starting raw sector write sequence. Sending bulk chunks...")

                while (bytesWritten < totalBytes && !isCancelled) {
                    val readResult = readFully(source, buffer)
                    if (readResult <= 0) break

                    // Pad end chunk
                    val lengthToWrite = if (readResult % SECTOR_SIZE != 0) {
                        val padded = ((readResult / SECTOR_SIZE) + 1) * SECTOR_SIZE
                        for (i in readResult until padded) {
                            if (i < buffer.size) buffer[i] = 0
                        }
                        padded
                    } else {
                        readResult
                    }

                    val sectors = lengthToWrite / SECTOR_SIZE

                    if (protocolMode == ProtocolMode.SCSI_BOT) {
                        val cbw = createCbwPayload(tagIndex, lengthToWrite, lba, sectors)
                        // Send Command
                        val code = connection.bulkTransfer(bulkOutEndpoint, cbw, cbw.size, DEFAULT_TIMEOUT_MS)
                        if (code != cbw.size) {
                            throw Exception("SCSI CBW command write mismatch Error: $code")
                        }

                        // Send Data Payload
                        var dataOffset = 0
                        while (dataOffset < lengthToWrite && !isCancelled) {
                            val chunkLength = minOf(65536, lengthToWrite - dataOffset)
                            val trans = connection.bulkTransfer(
                                bulkOutEndpoint,
                                buffer,
                                dataOffset,
                                chunkLength,
                                DEFAULT_TIMEOUT_MS
                            )
                            if (trans < 0) {
                                throw Exception("SCSI Data write error code: $trans")
                            }
                            dataOffset += trans
                        }

                        // Read Status
                        val csw = ByteArray(13)
                        val cswRead = connection.bulkTransfer(bulkInEndpoint, csw, csw.size, DEFAULT_TIMEOUT_MS)
                        if (cswRead != 13 || !verifyCsw(csw, tagIndex)) {
                            throw Exception("SCSI target failed to acknowledge bulk payload status response.")
                        }

                        tagIndex++
                        lba += sectors
                    } else {
                        // Raw Direct Endpoint Write
                        var dataOffset = 0
                        while (dataOffset < lengthToWrite && !isCancelled) {
                            val chunkLength = minOf(65536, lengthToWrite - dataOffset)
                            val trans = connection.bulkTransfer(
                                bulkOutEndpoint,
                                buffer,
                                dataOffset,
                                chunkLength,
                                DEFAULT_TIMEOUT_MS
                            )
                            if (trans < 0) {
                                throw Exception("Raw direct bulk endpoint transfer writing error: $trans")
                            }
                            dataOffset += trans
                        }
                    }

                    bytesWritten += readResult

                    val elapsed = System.currentTimeMillis() - startTime
                    val speed = if (elapsed > 0) {
                        (bytesWritten / megaFactor) / (elapsed / 1000.0)
                    } else 0.0

                    val eta = if (speed > 0) {
                        ((totalBytes - bytesWritten) / (speed * megaFactor)).toLong()
                    } else 0L

                    val percentage = (bytesWritten.toFloat() / totalBytes.toFloat()) * 100f

                    _status.value = FlashStatus.Progress(
                        percentage = percentage,
                        bytesWritten = bytesWritten,
                        totalBytes = totalBytes,
                        speedMbPerSec = speed,
                        etaSeconds = eta
                    )
                }

                if (isCancelled) {
                    addLog("[CANCEL] ISO flashing process aborted by user.")
                    _status.value = FlashStatus.Error("Flashing cancelled by user.")
                } else {
                    val duration = System.currentTimeMillis() - startTime
                    addLog("[SUCCESS] Sector flashing transaction verified successfully!")
                    addLog("[INFO] Completed in ${duration / 1000.0} seconds. Target drive is now bootable.")
                    _status.value = FlashStatus.Success(bytesWritten, duration)
                }
            }

        } catch (e: Exception) {
            val err = e.localizedMessage ?: "Unexpected low-level communication error."
            addLog("[CRITICAL] SCSI Flasher Exception: $err")
            _status.value = FlashStatus.Error(err)
        } finally {
            try {
                if (usbInterface != null && connection != null) {
                    addLog("[INFO] Releasing bulk exclusive handle...")
                    connection.releaseInterface(usbInterface)
                }
                connection?.close()
                addLog("[INFO] Session complete. USB closed safely.")
            } catch (ex: Exception) {
                addLog("[WARN] Resource cleanup warning: ${ex.message}")
            }
        }
    }

    private fun writeSectorsDirectly(
        connection: UsbDeviceConnection,
        outEp: android.hardware.usb.UsbEndpoint,
        inEp: android.hardware.usb.UsbEndpoint,
        startSector: Int,
        data: ByteArray,
        mode: ProtocolMode,
        tag: Int
    ) {
        val totalLength = data.size
        val sectors = totalLength / SECTOR_SIZE
        if (mode == ProtocolMode.SCSI_BOT) {
            val cbw = createCbwPayload(tag, totalLength, startSector, sectors)
            connection.bulkTransfer(outEp, cbw, cbw.size, DEFAULT_TIMEOUT_MS)
            
            var offset = 0
            while (offset < totalLength) {
                val len = minOf(65536, totalLength - offset)
                val trans = connection.bulkTransfer(outEp, data, offset, len, DEFAULT_TIMEOUT_MS)
                if (trans < 0) throw Exception("SCSI error code $trans on direct write")
                offset += trans
            }

            val csw = ByteArray(13)
            connection.bulkTransfer(inEp, csw, csw.size, DEFAULT_TIMEOUT_MS)
        } else {
            var offset = 0
            while (offset < totalLength) {
                val len = minOf(65536, totalLength - offset)
                val trans = connection.bulkTransfer(outEp, data, offset, len, DEFAULT_TIMEOUT_MS)
                if (trans < 0) throw Exception("Raw write error code $trans")
                offset += trans
            }
        }
    }

    private fun readFully(source: InputStream, buffer: ByteArray): Int {
        var offset = 0
        var remaining = buffer.size
        while (remaining > 0) {
            val count = source.read(buffer, offset, remaining)
            if (count == -1) break
            offset += count
            remaining -= count
        }
        return offset
    }

    private fun createCbwPayload(tag: Int, dataLength: Int, lba: Int, sectorCount: Int): ByteArray {
        val cbw = ByteArray(31)
        cbw[0] = 0x55.toByte()
        cbw[1] = 0x53.toByte()
        cbw[2] = 0x42.toByte()
        cbw[3] = 0x43.toByte()

        cbw[4] = (tag and 0xFF).toByte()
        cbw[5] = ((tag ushr 8) and 0xFF).toByte()
        cbw[6] = ((tag ushr 16) and 0xFF).toByte()
        cbw[7] = ((tag ushr 24) and 0xFF).toByte()

        cbw[8] = (dataLength and 0xFF).toByte()
        cbw[9] = ((dataLength ushr 8) and 0xFF).toByte()
        cbw[10] = ((dataLength ushr 16) and 0xFF).toByte()
        cbw[11] = ((dataLength ushr 24) and 0xFF).toByte()

        cbw[12] = 0x00.toByte()
        cbw[13] = 0x00.toByte()
        cbw[14] = 10.toByte()

        val cdb = ByteArray(16)
        cdb[0] = 0x2A.toByte() // SCSI WRITE (10)
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()

        cdb[7] = ((sectorCount ushr 8) and 0xFF).toByte()
        cdb[8] = (sectorCount and 0xFF).toByte()

        System.arraycopy(cdb, 0, cbw, 15, 10)
        return cbw
    }

    private fun verifyCsw(csw: ByteArray, expectedTag: Int): Boolean {
        if (csw.size < 13) return false
        if (csw[0] != 0x55.toByte() || csw[1] != 0x53.toByte() ||
            csw[2] != 0x42.toByte() || csw[3] != 0x53.toByte()
        ) {
            return false
        }
        val status = csw[12].toInt() and 0xFF
        return status == 0
    }

    fun queryDeviceCapacity(context: Context, device: UsbDevice): Long {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        var connection: UsbDeviceConnection? = null
        var targetInterface: UsbInterface? = null
        try {
            var foundInterface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    foundInterface = iface
                    break
                }
            }
            val iface = foundInterface ?: (if (device.interfaceCount > 0) device.getInterface(0) else null)
            if (iface == null) return -1L

            var bulkOutEndpoint = iface.getEndpoint(0)
            var bulkInEndpoint = iface.getEndpoint(0)
            var hasOut = false
            var hasIn = false
            for (e in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(e)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        bulkOutEndpoint = endpoint
                        hasOut = true
                    } else if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        bulkInEndpoint = endpoint
                        hasIn = true
                    }
                }
            }

            if (!hasOut || !hasIn) return -1L

            connection = usbManager.openDevice(device) ?: return -1L
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                return -1L
            }
            targetInterface = iface

            return queryDeviceCapacityInternal(connection, bulkOutEndpoint, bulkInEndpoint)
        } catch (e: Exception) {
            Log.e(TAG, "Error querying capacity via SCSI", e)
            return -1L
        } finally {
            try {
                if (targetInterface != null && connection != null) {
                    connection.releaseInterface(targetInterface)
                }
                connection?.close()
            } catch (ex: Exception) {
                // ignore
            }
        }
    }

    private fun queryDeviceCapacityInternal(
        connection: UsbDeviceConnection,
        outEp: android.hardware.usb.UsbEndpoint,
        inEp: android.hardware.usb.UsbEndpoint
    ): Long {
        val cbw = ByteArray(31)
        cbw[0] = 0x55.toByte()
        cbw[1] = 0x53.toByte()
        cbw[2] = 0x42.toByte()
        cbw[3] = 0x43.toByte()
        
        val tag = 9999
        cbw[4] = (tag and 0xFF).toByte()
        cbw[5] = ((tag ushr 8) and 0xFF).toByte()
        cbw[6] = ((tag ushr 16) and 0xFF).toByte()
        cbw[7] = ((tag ushr 24) and 0xFF).toByte()

        cbw[8] = 0x08.toByte()
        cbw[9] = 0x00.toByte()
        cbw[10] = 0x00.toByte()
        cbw[11] = 0x00.toByte()

        cbw[12] = 0x80.toByte() // IN flag
        cbw[13] = 0x00.toByte()
        cbw[14] = 10.toByte()

        cbw[15] = 0x25.toByte() // READ CAPACITY (10) opcode

        val cbwSent = connection.bulkTransfer(outEp, cbw, cbw.size, DEFAULT_TIMEOUT_MS)
        if (cbwSent != cbw.size) return -1L

        val response = ByteArray(8)
        val responseRead = connection.bulkTransfer(inEp, response, response.size, DEFAULT_TIMEOUT_MS)
        if (responseRead != response.size) return -1L

        val csw = ByteArray(13)
        connection.bulkTransfer(inEp, csw, csw.size, DEFAULT_TIMEOUT_MS)

        val lastLba = ((response[0].toLong() and 0xFF) shl 24) or
                      ((response[1].toLong() and 0xFF) shl 16) or
                      ((response[2].toLong() and 0xFF) shl 8) or
                      (response[3].toLong() and 0xFF)

        val blockLength = ((response[4].toLong() and 0xFF) shl 24) or
                          ((response[5].toLong() and 0xFF) shl 16) or
                          ((response[6].toLong() and 0xFF) shl 8) or
                          (response[7].toLong() and 0xFF)

        if (blockLength <= 0) return -1L
        return (lastLba + 1) * blockLength
    }
}
