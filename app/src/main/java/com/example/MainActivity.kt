package com.example

import android.app.PendingIntent
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.usb.USB_PERMISSION"
    }

    private val flasher = UsbFlasherEngine()
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String = intent.action ?: return
            when (action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            usbDevice?.let {
                                Toast.makeText(context, "USB Access Approved: ${it.deviceName}", Toast.LENGTH_SHORT).show()
                                onUsbPermissionApproved(it)
                            }
                        } else {
                            Toast.makeText(context, "USB Access Denied.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                    }
                    if (usbDevice != null && selectedUsbDeviceState.value?.deviceId == usbDevice.deviceId) {
                        selectedUsbDeviceState.value = null
                        Toast.makeText(context, "Selected USB device disconnected", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private var selectedUsbDeviceState = mutableStateOf<UsbDevice?>(null)

    private fun onUsbPermissionApproved(device: UsbDevice) {
        selectedUsbDeviceState.value = device
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Register receiver utilizing export flag for compatibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setContent {
            MyApplicationTheme(darkTheme = true) { // Force clean Premium Obsidian dark theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FlasherScreen(
                        flasher = flasher,
                        selectedUsbDevice = selectedUsbDeviceState.value,
                        onSelectUsbDevice = { device ->
                            requestUsbPermission(device)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            selectedUsbDeviceState.value = device
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlasherScreen(
    flasher: UsbFlasherEngine,
    selectedUsbDevice: UsbDevice?,
    onSelectUsbDevice: (UsbDevice) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // SAF File Picker implementation
    var isoUri by remember { mutableStateOf<Uri?>(null) }
    var isoName by remember { mutableStateOf<String?>(null) }
    var isoSizeFormatted by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            isoUri = it
            // Attempt to resolve display name and size from SAF
            val (name, size) = resolveUriDetails(context, it)
            isoName = name
            isoSizeFormatted = size
        }
    }

    // Advanced Formatting Options States
    var selectedPartitionScheme by remember { mutableStateOf(UsbFlasherEngine.PartitionScheme.GPT) }
    var selectedTargetSystem by remember { mutableStateOf(UsbFlasherEngine.TargetSystem.UEFI_NON_CSM) }
    var selectedFileSystem by remember { mutableStateOf(UsbFlasherEngine.FileSystemType.FAT32) }

    // USB device discovery state
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    var discoveredDevices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }
    var selectProtocolMode by remember { mutableStateOf(UsbFlasherEngine.ProtocolMode.SCSI_BOT) }

    // Read flasher state flows
    val flashStatus by flasher.status.collectAsState()
    val consoleLogs by flasher.logs.collectAsState()

    // Helper to refresh connected list
    fun refreshUsbDevices() {
        val list = usbManager.deviceList.values.toList()
        discoveredDevices = list
    }

    // Refresh initially and observe dynamic state changes
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                    action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    refreshUsbDevices()
                }
            }
        }
        val innerFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, innerFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, innerFilter)
        }
        
        refreshUsbDevices()

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    var usbCapacityBytes by remember(selectedUsbDevice) { mutableStateOf<Long?>(null) }
    var isCheckingCapacity by remember(selectedUsbDevice) { mutableStateOf(false) }

    LaunchedEffect(selectedUsbDevice) {
        if (selectedUsbDevice != null) {
            isCheckingCapacity = true
            usbCapacityBytes = withContext(Dispatchers.IO) {
                flasher.queryDeviceCapacity(context, selectedUsbDevice)
            }
            isCheckingCapacity = false
        } else {
            usbCapacityBytes = null
        }
    }

    fun formatCapacity(bytes: Long): String {
        if (bytes <= 0) return "Unknown"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return if (gb >= 1.0) {
            String.format("%.2f GB", gb)
        } else if (mb >= 1.0) {
            String.format("%.2f MB", mb)
        } else {
            String.format("%.2f KB", kb)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFD0BCFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF381E72),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "FlashEngine",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE6E1E5)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF1C1B1F)
                ),
                actions = {
                    IconButton(onClick = { /* Settings context */ }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFFE6E1E5)
                        )
                    }
                }
            )
        },
        bottomBar = {
            // Elegant navigation matching HTML layout
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                color = Color(0xFF1C1B1F),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49454F))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // TAB 1: FLASH (Active)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { /* Active context */ }
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF4F378B))
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Active Flash",
                                tint = Color(0xFFEADDFF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "FLASH",
                            color = Color(0xFFD0BCFF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // TAB 2: STATS
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            Toast.makeText(context, "Statistics log is currently empty.", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Stats",
                            tint = Color(0xFF938F99),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "STATS",
                            color = Color(0xFF938F99),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // TAB 3: HISTORY
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            Toast.makeText(context, "No flashing history recorded.", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "History",
                            tint = Color(0xFF938F99),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "HISTORY",
                            color = Color(0xFF938F99),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF1C1B1F) // Theme dark background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Is Flashing Guard State
            val isFlashing = flashStatus is UsbFlasherEngine.FlashStatus.Progress ||
                    flashStatus is UsbFlasherEngine.FlashStatus.Preparing ||
                    flashStatus is UsbFlasherEngine.FlashStatus.Formatting

            // CARD 1: SOURCE ISO SELECTION
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(
                    containerColor = Color(0xFF2B2930)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49454F)),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "SOURCE IMAGE",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFD0BCFF),
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = isoName ?: "None selected",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFE6E1E5),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isoSizeFormatted != null) "Size: $isoSizeFormatted • Verified OK" else "Select ISO file using file browser",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF938F99)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (!isFlashing) filePickerLauncher.launch(
                                    arrayOf(
                                        "application/octet-stream",
                                        "application/x-iso9660-image",
                                        "*/*"
                                    )
                                )
                            },
                            enabled = !isFlashing,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF381E72))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Browse ISO",
                                tint = Color(0xFFD0BCFF)
                            )
                        }
                    }
                }
            }

            // CARD 2: USB DESTINATION DRIVE
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(
                    containerColor = Color(0xFF2B2930)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49454F)),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    var dropdownExpanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "TARGET DEVICE",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFD0BCFF),
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (selectedUsbDevice != null) {
                                    "${selectedUsbDevice.manufacturerName ?: "Generic"} ${selectedUsbDevice.productName ?: "USB Drive"}"
                                } else {
                                    "No device selected"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFFE6E1E5),
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (selectedUsbDevice != null) {
                                    "${selectedUsbDevice.deviceName} • Class SCSI"
                                } else {
                                    "Plug USB storage OTG drive to system"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF938F99)
                            )
                            if (selectedUsbDevice != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                val vidHex = String.format("0x%04X", selectedUsbDevice.vendorId)
                                val pidHex = String.format("0x%04X", selectedUsbDevice.productId)
                                Text(
                                    text = "Vendor ID: $vidHex • Product ID: $pidHex",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD0BCFF),
                                    fontWeight = FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isCheckingCapacity) {
                                        "Querying volume capacity..."
                                    } else {
                                        "Capacity: ${formatCapacity(usbCapacityBytes ?: -1L)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE6E1E5),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Active status dot animation if device connected
                            if (selectedUsbDevice != null) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color(0xFFB2EEB1))
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (!isFlashing) {
                                        refreshUsbDevices()
                                        dropdownExpanded = true
                                    }
                                },
                                enabled = !isFlashing,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF49454F))
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "Expand USB dropdown list",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .background(Color(0xFF2B2930))
                            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                    ) {
                        if (discoveredDevices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No USB OTG devices detected", color = Color(0xFF938F99)) },
                                onClick = { dropdownExpanded = false }
                            )
                        } else {
                            discoveredDevices.forEach { dev ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "${dev.manufacturerName ?: "Unknown"} ${dev.productName ?: "USB Drive"}",
                                                color = Color(0xFFE6E1E5),
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                "Path: ${dev.deviceName} | ID: ${dev.deviceId}",
                                                color = Color(0xFF938F99),
                                                fontSize = 12.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSelectUsbDevice(dev)
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // CARD 3: ADVANCED FORMATTING OPTIONS
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(
                    containerColor = Color(0xFF2B2930)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49454F)),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "ADVANCED FORMATTING OPTIONS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFD0BCFF),
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )

                    var schemeExpanded by remember { mutableStateOf(false) }
                    var systemExpanded by remember { mutableStateOf(false) }
                    var fsExpanded by remember { mutableStateOf(false) }

                    // Partition Scheme Selection
                    Column {
                        Text(
                            "Partition Scheme",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF938F99)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                                .clickable(enabled = !isFlashing) { schemeExpanded = true }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    selectedPartitionScheme.name,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = schemeExpanded,
                                onDismissRequest = { schemeExpanded = false },
                                modifier = Modifier.background(Color(0xFF2B2930))
                            ) {
                                UsbFlasherEngine.PartitionScheme.values().forEach { scheme ->
                                    DropdownMenuItem(
                                        text = { Text(scheme.name, color = Color.White) },
                                        onClick = {
                                            selectedPartitionScheme = scheme
                                            schemeExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Target System Selection
                    Column {
                        Text(
                            "Target System",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF938F99)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                                .clickable(enabled = !isFlashing) { systemExpanded = true }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val systemLabel = when (selectedTargetSystem) {
                                    UsbFlasherEngine.TargetSystem.UEFI_NON_CSM -> "UEFI (non-CSM)"
                                    UsbFlasherEngine.TargetSystem.BIOS_CSM -> "BIOS (or UEFI-CSM)"
                                }
                                Text(
                                    systemLabel,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = systemExpanded,
                                onDismissRequest = { systemExpanded = false },
                                modifier = Modifier.background(Color(0xFF2B2930))
                            ) {
                                UsbFlasherEngine.TargetSystem.values().forEach { target ->
                                    val label = when (target) {
                                        UsbFlasherEngine.TargetSystem.UEFI_NON_CSM -> "UEFI (non-CSM)"
                                        UsbFlasherEngine.TargetSystem.BIOS_CSM -> "BIOS (or UEFI-CSM)"
                                    }
                                    DropdownMenuItem(
                                        text = { Text(label, color = Color.White) },
                                        onClick = {
                                            selectedTargetSystem = target
                                            systemExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // File System Selection
                    Column {
                        Text(
                            "File System",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF938F99)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1C1B1F))
                                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(8.dp))
                                .clickable(enabled = !isFlashing) { fsExpanded = true }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    selectedFileSystem.name,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                            DropdownMenu(
                                expanded = fsExpanded,
                                onDismissRequest = { fsExpanded = false },
                                modifier = Modifier.background(Color(0xFF2B2930))
                            ) {
                                UsbFlasherEngine.FileSystemType.values().forEach { fs ->
                                    DropdownMenuItem(
                                        text = { Text(fs.name, color = Color.White) },
                                        onClick = {
                                            selectedFileSystem = fs
                                            fsExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // SELECTION CARD 3: PROTOCOL SETTING MODE (Compact style matching theme)
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(
                    containerColor = Color(0xFF2B2930)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49454F)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1.0f)) {
                        Text(
                            "PROTOCOL MODE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD0BCFF),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (selectProtocolMode == UsbFlasherEngine.ProtocolMode.SCSI_BOT) "SCSI Mass Storage BOT" else "Direct Byte-Stream Write",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE6E1E5)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1C1B1F))
                    ) {
                        IconButton(
                            onClick = { if (!isFlashing) selectProtocolMode = UsbFlasherEngine.ProtocolMode.SCSI_BOT },
                            modifier = Modifier.background(if (selectProtocolMode == UsbFlasherEngine.ProtocolMode.SCSI_BOT) Color(0xFF4F378B) else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "SCSI",
                                tint = if (selectProtocolMode == UsbFlasherEngine.ProtocolMode.SCSI_BOT) Color(0xFFD0BCFF) else Color(0xFF938F99)
                            )
                        }
                        IconButton(
                            onClick = { if (!isFlashing) selectProtocolMode = UsbFlasherEngine.ProtocolMode.RAW_DIRECT },
                            modifier = Modifier.background(if (selectProtocolMode == UsbFlasherEngine.ProtocolMode.RAW_DIRECT) Color(0xFF4F378B) else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Direct",
                                tint = if (selectProtocolMode == UsbFlasherEngine.ProtocolMode.RAW_DIRECT) Color(0xFFD0BCFF) else Color(0xFF938F99)
                            )
                        }
                    }
                }
            }

            // SAFETY ERASE DESTRUCTIVE WARNING PANEL
            if (selectedUsbDevice != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF311111))
                        .border(1.dp, Color(0xFF8C1D18), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = "Destructive Erase Alert Icon",
                        tint = Color(0xFFF2B8B5),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Flashing will permanently erase all files on the destination USB storage drive. Please backup your data safely before proceeding.",
                        color = Color(0xFFF2B8B5),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // PROGRESS CARD OR SUCCESS STATUS SPANS
            AnimatedVisibility(
                visible = isFlashing || flashStatus is UsbFlasherEngine.FlashStatus.Success || flashStatus is UsbFlasherEngine.FlashStatus.Error
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (val currentStatus = flashStatus) {
                        is UsbFlasherEngine.FlashStatus.Formatting -> {
                            Text(
                                "Wiping and partitioning USB target drive...",
                                color = Color(0xFFD0BCFF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFFFFB74D),
                                trackColor = Color(0xFF49454F)
                            )
                        }

                        is UsbFlasherEngine.FlashStatus.Preparing -> {
                            Text(
                                "Staging flash sequence buffers...",
                                color = Color(0xFFD0BCFF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFFD0BCFF),
                                trackColor = Color(0xFF49454F)
                            )
                        }

                        is UsbFlasherEngine.FlashStatus.Progress -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Flashing progress",
                                    color = Color(0xFFE6E1E5),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${String.format("%.1f", currentStatus.percentage)}%",
                                    color = Color(0xFFD0BCFF),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            LinearProgressIndicator(
                                progress = { currentStatus.percentage / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = Color(0xFFD0BCFF),
                                trackColor = Color(0xFF49454F)
                            )

                            Text(
                                text = "WRITING: ${String.format("%.1f", (currentStatus.bytesWritten / (1024.0 * 1024.0 * 1024.0)))}GB / ${String.format("%.1f", (currentStatus.totalBytes / (1024.0 * 1024.0 * 1024.0)))}GB @ ${String.format("%.1f", currentStatus.speedMbPerSec)} MB/s",
                                color = Color(0xFF938F99),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        is UsbFlasherEngine.FlashStatus.Success -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E3524))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFFB2EEB1))
                                Text(
                                    "FLASH SUCCESSFUL! Image written context safely.",
                                    color = Color(0xFFB2EEB1),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        is UsbFlasherEngine.FlashStatus.Error -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF311111))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF2B8B5))
                                Text(
                                    currentStatus.message,
                                    color = Color(0xFFF2B8B5),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }

            // CONSOLE LOG COMPARTMENT
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF151518))
                    .border(1.dp, Color(0xFF2B2930), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                val logListState = rememberLazyListState()
                LaunchedEffect(consoleLogs.size) {
                    if (consoleLogs.isNotEmpty()) {
                        logListState.animateScrollToItem(consoleLogs.size - 1)
                    }
                }

                if (consoleLogs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Diagnostic logger engine waiting...",
                            color = Color(0xFF49454F),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(consoleLogs) { logLine ->
                            Text(
                                text = logLine,
                                color = when {
                                    logLine.contains("[START]") || logLine.contains("[SUCCESS]") -> Color(0xFF00E676)
                                    logLine.contains("[ERROR]") || logLine.contains("[CRITICAL]") -> Color(0xFFF2B8B5)
                                    logLine.contains("[WARN]") -> Color(0xFFFFD600)
                                    else -> Color(0xFF938F99)
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }
            }

            // PRIMARY BIG TRIGGER BUTTON
            if (isFlashing) {
                Button(
                    onClick = { flasher.cancelFlashing() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8C1D18),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Icon(imageVector = Icons.Filled.Clear, contentDescription = "Abort")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ABORT FLASHING PROCESS", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                Button(
                    onClick = {
                        if (isoUri != null && selectedUsbDevice != null) {
                            coroutineScope.launch {
                                flasher.startFlash(
                                    context = context,
                                    isoUri = isoUri!!,
                                    device = selectedUsbDevice,
                                    protocolMode = selectProtocolMode,
                                    partitionScheme = selectedPartitionScheme,
                                    targetSystem = selectedTargetSystem,
                                    fileSystemType = selectedFileSystem
                                )
                            }
                        }
                    },
                    enabled = isoUri != null && selectedUsbDevice != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD0BCFF),
                        disabledContainerColor = Color(0xFF2B2930),
                        contentColor = Color(0xFF381E72),
                        disabledContentColor = Color(0xFF49454F)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "START FLASHING",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    accentColor: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
    }
}

/**
 * Extracts human-readable filename and size formatting from a SAF Uri.
 */
private fun resolveUriDetails(context: Context, uri: Uri): Pair<String, String> {
    var fileName = "Selected File"
    var sizeLabel = "Unknown Size"
    val contentResolver = context.contentResolver

    var cursor: Cursor? = null
    try {
        cursor = contentResolver.query(uri, null, null, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                fileName = cursor.getString(nameIndex) ?: "Selected File"
            }
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1) {
                val sizeBytes = cursor.getLong(sizeIndex)
                sizeLabel = if (sizeBytes > 0) {
                    val mb = sizeBytes / (1024.0 * 1024.0)
                    if (mb >= 1024.0) {
                        String.format("%.2f GB", mb / 1024.0)
                    } else {
                        String.format("%.2f MB", mb)
                    }
                } else "Unknown"
            }
        }
    } catch (e: Exception) {
        Log.e("SAF", "Error resolving SAF document attributes", e)
    } finally {
        cursor?.close()
    }

    return Pair(fileName, sizeLabel)
}
