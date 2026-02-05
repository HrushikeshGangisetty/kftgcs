package com.example.aerogcsclone.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.aerogcsclone.authentication.AuthViewModel
import com.example.aerogcsclone.database.tlog.FlightEntity
import com.example.aerogcsclone.export.ExportFormat
import com.example.aerogcsclone.viewmodel.TlogViewModel
//import com.example.aerogcsclone.Telemetry.SharedViewModel
import com.example.aerogcsclone.telemetry.SharedViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    navController: NavHostController,
    authViewModel: AuthViewModel,
    telemetryViewModel: SharedViewModel,
    tlogViewModel: TlogViewModel = viewModel()
) {
    val telemetryState by telemetryViewModel.telemetryState.collectAsState()
    val uiState by tlogViewModel.uiState.collectAsState()
    val flights by tlogViewModel.flights.collectAsState(initial = emptyList())

    // UI-only state for export dialogs
    var showExportChoiceDialog by remember { mutableStateOf(false) }
    var showExportSelectDialog by remember { mutableStateOf(false) }

    // Selection state for 'Select Flights' dialog
    val selectedFlightIds = remember { mutableStateListOf<Long>() }
    var selectedFormat by remember { mutableStateOf(ExportFormat.entries.first()) }

    // --- Filter state: allow filtering by exact date or by month ---
    val dateOnlyFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val monthOnlyFormat = remember { SimpleDateFormat("MMM yyyy", Locale.getDefault()) }

    var showFilterOptionsDialog by remember { mutableStateOf(false) }
    var showDateSelectDialog by remember { mutableStateOf(false) }
    var showMonthSelectDialog by remember { mutableStateOf(false) }

    var selectedDate by remember { mutableStateOf<String?>(null) }
    var selectedMonth by remember { mutableStateOf<String?>(null) }

    val displayedFlights by remember(flights, selectedDate, selectedMonth) {
        derivedStateOf {
            when {
                selectedDate != null -> flights.filter { dateOnlyFormat.format(Date(it.startTime)) == selectedDate }
                selectedMonth != null -> flights.filter { monthOnlyFormat.format(Date(it.startTime)) == selectedMonth }
                else -> flights
            }
        }
    }
    // --- end filter state ---

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E27),
                        Color(0xFF1A1F3A),
                        Color(0xFF0F1419)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
                // Modern Header Section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Title with icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF2196F3),
                                            Color(0xFF1976D2)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.FlightTakeoff,
                                contentDescription = "Flight Logs",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        // Title and inline compact stats
                        // Add right padding so the inline stats don't collide with the export button
                        Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Flight Logs",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${displayedFlights.size} missions recorded",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                            }

                            // Compact stats shown inline in the top nav (no logic changes)
                            // Increase spacing between stat items to avoid overlap
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Flight, contentDescription = "Total Flights", tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                                    Text(text = uiState.totalFlights.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "Total", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.AccessTime, contentDescription = "Flight Time", tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                                    Text(text = formatDuration(uiState.totalFlightTime), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(text = "Time", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                                }
                            }
                        }
                    }

                    // Export All Button with modern design
                    Button(
                        onClick = { showExportChoiceDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                        ),
                        enabled = !uiState.isExporting && displayedFlights.isNotEmpty(),
                        modifier = Modifier
                            .height(48.dp)
                            .shadow(8.dp, RoundedCornerShape(24.dp))
                            .background(
                                if (!uiState.isExporting && displayedFlights.isNotEmpty()) {
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF1E88E5),
                                            Color(0xFF1565C0)
                                        )
                                    )
                                } else {
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Gray.copy(alpha = 0.3f),
                                            Color.Gray.copy(alpha = 0.3f)
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(24.dp)
                            ),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        if (uiState.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = "Export All",
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Export All",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

            // Active Flight Status
            if (uiState.isFlightActive) {
                ActiveFlightCard(
                    flightId = uiState.currentFlightId,
                    onEndFlight = { tlogViewModel.endFlight() },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Error Message with modern styling
            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFF5252).copy(alpha = 0.15f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = "Error",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = error,
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { tlogViewModel.clearError() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Export Success Message with modern styling
            uiState.exportMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = message,
                            color = Color(0xFF4CAF50),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { tlogViewModel.clearExportMessage() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Modern Flights List Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = "History",
                    tint = Color(0xFF64B5F6),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recent Flights",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.weight(1f))

                // Show active filter chip and clear button
                if (selectedDate != null || selectedMonth != null) {
                    val label = selectedDate ?: selectedMonth ?: ""
                    Surface(
                        color = Color(0xFF1E293B).copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.FilterList, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = label, color = Color.White, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { selectedDate = null; selectedMonth = null }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear filter", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Filter button to open filter options
                IconButton(onClick = { showFilterOptionsDialog = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = Color(0xFF64B5F6))
                }
            }

            // Flights List with modern cards
            if (displayedFlights.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E2844).copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(48.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFF64B5F6).copy(alpha = 0.3f),
                                            Color.Transparent
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.FlightTakeoff,
                                contentDescription = "No flights",
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No flights recorded yet",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Start a mission to begin logging",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                // Denser list: reduce vertical spacing between items
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    displayedFlights.forEach { flight ->
                        FlightItem(
                            flight = flight,
                            onViewDetails = { /* Navigate to flight details */ },
                            onExport = { format -> tlogViewModel.exportFlight(flight, format) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    // Export choice dialog shown when top export button is clicked (UI-only)
    if (showExportChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showExportChoiceDialog = false },
            title = {
                Text("Export Options", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Text("Would you like to export all flights, or select specific flights to export?", color = Color.White)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Call existing exportAllFlights logic (no logic change)
                        tlogViewModel.exportAllFlights()
                        showExportChoiceDialog = false
                    }
                ) {
                    Text("Export All", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportChoiceDialog = false
                        showExportSelectDialog = true
                    }
                ) {
                    Text("Select", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // Placeholder Select UI (UI-only, no selection logic)
    if (showExportSelectDialog) {
        AlertDialog(
            onDismissRequest = { showExportSelectDialog = false },
            title = { Text("Select Flights to Export", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)) {
                    // Select All / Clear row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent flights", color = Color.White, fontWeight = FontWeight.Medium)
                        TextButton(onClick = {
                            if (selectedFlightIds.size == displayedFlights.size) selectedFlightIds.clear()
                            else {
                                selectedFlightIds.clear()
                                selectedFlightIds.addAll(displayedFlights.map { it.id })
                            }
                        }) {
                            Text(if (selectedFlightIds.size == displayedFlights.size) "Clear" else "Select All", color = Color(0xFF60A5FA))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (displayedFlights.isEmpty()) {
                        Text("No flights available to select.", color = Color.White.copy(alpha = 0.7f))
                    } else {
                        // List of flights with checkboxes
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(items = displayedFlights.reversed(), key = { it.id }) { flight ->
                                val checked = selectedFlightIds.contains(flight.id)
                                Row(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable {
                                        if (checked) selectedFlightIds.remove(flight.id) else selectedFlightIds.add(flight.id)
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = { isChecked ->
                                        if (isChecked) selectedFlightIds.add(flight.id) else selectedFlightIds.remove(flight.id)
                                    })
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(flight.startTime)), color = Color.White)
                                        Text(text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(flight.startTime)), color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Format selection
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Export format", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ExportFormat.entries.forEach { format ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = (selectedFormat == format), onClick = { selectedFormat = format })
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(format.displayName, color = Color.White)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                // Export button: call exportFlight for each selected flight
                TextButton(
                    onClick = {
                        // Call export for each selected flight (uses existing ViewModel method)
                        val selected = displayedFlights.filter { selectedFlightIds.contains(it.id) }
                        selected.forEach { flight ->
                            tlogViewModel.exportFlight(flight, selectedFormat)
                        }
                        selectedFlightIds.clear()
                        showExportSelectDialog = false
                    },
                    enabled = selectedFlightIds.isNotEmpty()
                ) {
                    Text("Export", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedFlightIds.clear()
                    showExportSelectDialog = false
                }) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // --- New: Filter Options Dialog ---
    if (showFilterOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showFilterOptionsDialog = false },
            title = { Text("Filter Flights", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column {
                    Text("Choose how you'd like to filter the recent flights:", color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        // Show all
                        selectedDate = null
                        selectedMonth = null
                        showFilterOptionsDialog = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Show All")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        showFilterOptionsDialog = false
                        showDateSelectDialog = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Filter by Date")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        showFilterOptionsDialog = false
                        showMonthSelectDialog = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Filter by Month")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showFilterOptionsDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Date selection dialog: show unique dates from flights
    if (showDateSelectDialog) {
        val uniqueDatePairs = flights.map { dateOnlyFormat.format(Date(it.startTime)) to it.startTime }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .map { it.first }

        AlertDialog(
            onDismissRequest = { showDateSelectDialog = false },
            title = { Text("Select Date", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                if (uniqueDatePairs.isEmpty()) {
                    Text("No flights available to filter.", color = Color.White)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(uniqueDatePairs) { dateStr ->
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedDate = dateStr
                                    selectedMonth = null
                                    showDateSelectDialog = false
                                }
                                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dateStr, color = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDateSelectDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Month selection dialog: show unique months from flights
    if (showMonthSelectDialog) {
        val uniqueMonthPairs = flights.map { monthOnlyFormat.format(Date(it.startTime)) to it.startTime }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .map { it.first }

        AlertDialog(
            onDismissRequest = { showMonthSelectDialog = false },
            title = { Text("Select Month", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                if (uniqueMonthPairs.isEmpty()) {
                    Text("No flights available to filter.", color = Color.White)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(uniqueMonthPairs) { monthStr ->
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedMonth = monthStr
                                    selectedDate = null
                                    showMonthSelectDialog = false
                                }
                                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(monthStr, color = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMonthSelectDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ActiveFlightCard(
    flightId: Long?,
    onEndFlight: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF10B981).copy(alpha = 0.25f),
                            Color(0xFF059669).copy(alpha = 0.3f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF10B981),
                                    Color(0xFF059669)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.RadioButtonChecked,
                        contentDescription = "Active",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🟢 Flight Active",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Flight ID: $flightId",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Button(
                    onClick = onEndFlight,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .height(44.dp)
                        .shadow(6.dp, RoundedCornerShape(22.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFEF4444),
                                    Color(0xFFDC2626)
                                )
                            ),
                            shape = RoundedCornerShape(22.dp)
                        ),
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "End",
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "End Flight",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun FlightItem(
    flight: FlightEntity,
    onViewDetails: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    var showExportDialog by remember { mutableStateOf(false) }
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Ultra-compact flight item: minimal height, tiny icons and fonts
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1E293B).copy(alpha = 0.9f),
                            Color(0xFF0F172A).copy(alpha = 0.95f)
                        )
                    )
                )
                .clickable { onViewDetails() }
                .padding(vertical = 4.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Minimal status
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (flight.isCompleted) Brush.radialGradient(listOf(Color(0xFF10B981), Color.Transparent))
                        else Brush.radialGradient(listOf(Color(0xFFFFA500), Color.Transparent))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (flight.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (flight.isCompleted) Color(0xFF10B981) else Color(0xFFFFA500),
                    modifier = Modifier.size(10.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Date + time compact
            Column(modifier = Modifier.weight(1f)) {
                Text(text = dateFormat.format(Date(flight.startTime)), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Normal)
                Text(text = timeFormat.format(Date(flight.startTime)), color = Color(0xFF60A5FA), fontSize = 8.sp)
            }

            // Tiny chips for duration / area (show only if present)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                flight.flightDuration?.let { duration ->
                    Surface(
                        color = Color(0xFF1E3A8A).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 0.dp,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = formatDuration(duration), color = Color.White, fontSize = 10.sp)
                        }
                    }
                }

                flight.area?.let { area ->
                    Surface(
                        color = Color(0xFF059669).copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(start = 2.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Landscape, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = String.format(Locale.getDefault(), "%.2f ha", area), color = Color.White, fontSize = 10.sp)
                        }
                    }
                }

                // Small action icons
                IconButton(onClick = { showExportDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Download, contentDescription = "Export", modifier = Modifier.size(12.dp), tint = Color(0xFF60A5FA))
                }
            }
        }
    }

    // Modern Export Format Selection Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF1E88E5),
                                        Color(0xFF1565C0)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Export",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Export Flight Log",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.height(300.dp)
                ) {
                    Text(
                        "Choose export format:",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(ExportFormat.entries.toTypedArray()) { format ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .shadow(4.dp, RoundedCornerShape(12.dp))
                                    .clickable {
                                        onExport(format)
                                        showExportDialog = false
                                    },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF60A5FA).copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.InsertDriveFile,
                                            contentDescription = format.displayName,
                                            tint = Color(0xFF60A5FA),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = format.displayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = format.description,
                                            fontSize = 12.sp,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                    }
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Select",
                                        tint = Color(0xFF60A5FA),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF60A5FA)
                    )
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val hours = durationMs / (1000 * 60 * 60)
    val minutes = (durationMs % (1000 * 60 * 60)) / (1000 * 60)

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
