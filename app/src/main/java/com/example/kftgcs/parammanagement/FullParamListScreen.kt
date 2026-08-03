package com.example.kftgcs.parammanagement

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ─────────────────────────────────────────────────────────────────────
// Palette — warm mixed tones, clean & modern
// ─────────────────────────────────────────────────────────────────────
private val ScreenBg       = Color(0xFFEFF2F7)
private val TopBarBg       = Color(0xFF2B3044)
private val HeaderStart     = Color(0xFF3A6BD5)
private val HeaderEnd       = Color(0xFF5B8DEF)
private val CardBg          = Color.White
private val RowWhite        = Color.White
private val RowAlt          = Color(0xFFF7F9FC)
private val DividerCol      = Color(0xFFE2E8F0)
private val BluePrimary     = Color(0xFF3A6BD5)
private val BlueLight       = Color(0xFF87CEEB)
private val GreenConfirm    = Color(0xFF38A169)
private val RedErr          = Color(0xFFE53935)
private val Amber           = Color(0xFFED8936)
private val TextDark        = Color(0xFF1A202C)
private val TextMed         = Color(0xFF4A5568)
private val TextMuted       = Color(0xFFA0AEC0)
private val DropBg          = Color(0xFFEDF2F7)

// ─────────────────────────────────────────────────────────────────────
// Column widths
// ─────────────────────────────────────────────────────────────────────
private val ColW_Idx     = 44.dp
private val ColW_Name    = 178.dp
private val ColW_Units   = 72.dp
private val ColW_Desc    = 220.dp
private val ColW_Default = 88.dp
private val ColW_Options = 178.dp
private val ColW_Value   = 146.dp

// ─────────────────────────────────────────────────────────────────────
// ArduPilot parameter metadata
// ─────────────────────────────────────────────────────────────────────
data class ParamMeta(
    val description: String = "",
    val defaultValue: String = "",
    val options: Map<Int, String> = emptyMap(),
    val range: String = "",
    val units: String = ""
)

// ═════════════════════════════════════════════════════════════════════
private fun fmtValue(value: Float): String {
    if (value == value.toLong().toFloat() && value >= -999999 && value <= 999999)
        return value.toLong().toString()
    return "%.4f".format(value).trimEnd('0').trimEnd('.')
}

// ═════════════════════════════════════════════════════════════════════
// Main Screen
// ═════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullParamListScreen(
    navController: NavController,
    viewModel: FullParamListViewModel
) {
    val state by viewModel.state.collectAsState()
    val paramMetadata by viewModel.paramMetadata.collectAsState()
    val ctx = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val filteredParams by remember(state.params, searchQuery) {
        derivedStateOf {
            val all = state.params.values.toList()
            val q = searchQuery.trim().uppercase()
            if (q.isEmpty()) all.sortedBy { it.name }
            else all.filter { it.name.uppercase().contains(q) }
                .sortedWith(compareByDescending<DroneParam> { it.name.uppercase().startsWith(q) }.thenBy { it.name })
        }
    }

    // Auto-fetch all params once the drone is connected (no manual tap needed)
    LaunchedEffect(state.isDroneConnected) {
        if (state.isDroneConnected && state.params.isEmpty() && !state.isLoading) {
            viewModel.fetchAllParams()
        }
    }

    LaunchedEffect(state.writeSuccess) {
        state.writeSuccess?.let {
            Toast.makeText(ctx, "✅ $it written", Toast.LENGTH_SHORT).show()
            viewModel.clearWriteMessages()
        }
    }
    LaunchedEffect(state.writeError) {
        state.writeError?.let {
            Toast.makeText(ctx, "❌ $it", Toast.LENGTH_SHORT).show()
            viewModel.clearWriteMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        InlineSearchField(searchQuery, { searchQuery = it }, { searchQuery = ""; isSearchActive = false })
                    } else {
                        Column {
                            Text("Full Param List", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (state.params.isNotEmpty()) {
                                Text(
                                    "${filteredParams.size} / ${state.params.size} params • ${state.metadataCount} defs",
                                    color = BlueLight, fontSize = 11.sp, letterSpacing = 0.3.sp
                                )
                            } else if (state.isMetadataLoading) {
                                Text(
                                    "Loading parameter definitions…",
                                    color = BlueLight, fontSize = 11.sp, letterSpacing = 0.3.sp
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (!isSearchActive) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                },
                actions = {
                    if (!isSearchActive && state.params.isNotEmpty()) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Filled.Search, "Search", tint = Color.White)
                        }
                    }
                    if (!isSearchActive) {
                        // Refresh metadata definitions from ArduPilot
                        IconButton(
                            onClick = { viewModel.refreshMetadata() },
                            enabled = !state.isMetadataLoading
                        ) {
                            Icon(
                                Icons.Filled.Refresh, "Refresh Metadata",
                                tint = if (!state.isMetadataLoading) Color.White.copy(alpha = 0.7f)
                                else Color.White.copy(alpha = 0.35f)
                            )
                        }
                        // Fetch params from drone
                        IconButton(
                            onClick = { viewModel.fetchAllParams() },
                            enabled = state.isDroneConnected && !state.isLoading
                        ) {
                            Icon(
                                Icons.Filled.Download, "Fetch",
                                tint = if (state.isDroneConnected && !state.isLoading) Color.White
                                else Color.White.copy(alpha = 0.35f)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TopBarBg)
            )
        },
        containerColor = ScreenBg
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                // Only block the screen while there is genuinely nothing to show.
                // Once params start arriving the table renders and keeps filling in,
                // so a stalled download can never leave the pilot staring at a spinner.
                state.isLoading && state.params.isEmpty() ->
                    LoadingView(state) { viewModel.stopFetch() }

                state.params.isEmpty() ->
                    EmptyView(state.isDroneConnected, state.errorMessage) { viewModel.fetchAllParams() }

                else -> Column(Modifier.fillMaxSize()) {
                    if (state.isLoading) {
                        FetchBanner(state) { viewModel.stopFetch() }
                    } else if (state.missingCount > 0) {
                        MissingBanner(state.missingCount) { viewModel.fetchAllParams() }
                    }
                    ParamTable(filteredParams, searchQuery, state.writingParam, paramMetadata) { n, v ->
                        viewModel.writeParam(n, v)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════ Search ═══════════════════════════════════

@Composable
private fun InlineSearchField(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { fr.requestFocus() }

    TextField(
        value = query, onValueChange = onChange,
        placeholder = { Text("Search parameters…", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.White.copy(alpha = 0.6f)) },
        trailingIcon = {
            IconButton(onClick = { if (query.isNotEmpty()) onChange("") else onClose() }) {
                Icon(Icons.Filled.Close, "Clear", tint = Color.White.copy(alpha = 0.6f))
            }
        },
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            cursorColor = BlueLight,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
        modifier = Modifier.fillMaxWidth().focusRequester(fr)
    )
}

// ═══════════════════════════ Loading ══════════════════════════════════

@Composable
private fun LoadingView(state: FullParamListState, onStop: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            elevation = CardDefaults.cardElevation(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    progress = { state.loadingProgress }, color = BluePrimary,
                    trackColor = BluePrimary.copy(alpha = 0.12f),
                    modifier = Modifier.size(64.dp), strokeWidth = 5.dp
                )
                Spacer(Modifier.height(20.dp))
                Text("Fetching Parameters…", color = TextDark, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${state.receivedCount} / ${if (state.totalCount > 0) state.totalCount else "?"}",
                    color = TextMed, fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.loadingProgress },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = BluePrimary, trackColor = BluePrimary.copy(alpha = 0.12f)
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onStop) {
                    Text("Stop and show what's loaded", color = BluePrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

// ═══════════════════════════ In-table banners ═════════════════════════

/** Slim progress strip shown above the table while the rest of the list streams in. */
@Composable
private fun FetchBanner(state: FullParamListState, onStop: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFE8F1FE))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.isFillingGaps) "Re-requesting dropped parameters…"
                else "Fetching parameters… ${state.receivedCount} / ${if (state.totalCount > 0) state.totalCount else "?"}",
                color = TextMed, fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Stop", color = BluePrimary, fontSize = 12.sp)
            }
        }
        LinearProgressIndicator(
            progress = { state.loadingProgress },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = BluePrimary, trackColor = BluePrimary.copy(alpha = 0.12f)
        )
    }
}

/** Shown when the FC advertised more params than the link delivered. */
@Composable
private fun MissingBanner(missingCount: Int, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF4E5))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$missingCount parameter${if (missingCount == 1) "" else "s"} not received — telemetry link dropped them.",
            color = Amber, fontSize = 12.sp, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Retry", color = BluePrimary, fontSize = 12.sp)
        }
    }
}

// ═══════════════════════════ Empty ════════════════════════════════════

@Composable
private fun EmptyView(connected: Boolean, error: String?, onFetch: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            elevation = CardDefaults.cardElevation(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Download, null, tint = TextMuted, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(16.dp))
                Text("Full Parameter List", color = TextDark, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (connected) "Tap below to fetch all parameters from the drone."
                    else "Connect to a drone first, then fetch parameters.",
                    color = TextMed, fontSize = 13.sp, textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = RedErr, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onFetch, enabled = connected,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Fetch All Parameters", fontSize = 14.sp)
                }
                if (!connected) {
                    Spacer(Modifier.height(10.dp))
                    Text("⚠ Drone not connected", color = RedErr.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
// TABLE
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun ParamTable(
    params: List<DroneParam>,
    searchQuery: String,
    writingParam: String?,
    metadataMap: Map<String, ParamMeta>,
    onWrite: (String, Float) -> Unit
) {
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()

    if (params.isEmpty() && searchQuery.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Search, null, tint = TextMuted, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No params matching \"$searchQuery\"", color = TextMed, fontSize = 14.sp)
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // ── Table card ──
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            elevation = CardDefaults.cardElevation(3.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            Column {
                // ── Header row with gradient ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(hScroll)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(Brush.horizontalGradient(listOf(HeaderStart, HeaderEnd)))
                        .padding(vertical = 2.dp)
                ) {
                    HdrCell("#", ColW_Idx)
                    HdrCell("Name", ColW_Name)
                    HdrCell("Description", ColW_Desc)
                    HdrCell("Units", ColW_Units)
                    HdrCell("Default", ColW_Default)
                    HdrCell("Options", ColW_Options)
                    HdrCell("Value  ✎", ColW_Value)
                }

                // Thin separator
                HorizontalDivider(color = BluePrimary.copy(alpha = 0.3f), thickness = 1.dp)

                // ── Data rows ──
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // itemsIndexed, not items + indexOf: the latter is a linear scan of a
                    // 1000+ entry list for every row that composes.
                    itemsIndexed(items = params, key = { _, p -> p.name }) { idx, param ->
                        DataRow(param, idx, writingParam == param.name, hScroll, metadataMap) { v -> onWrite(param.name, v) }
                        // Divider between rows (skip after last)
                        if (idx < params.lastIndex) {
                            HorizontalDivider(
                                color = DividerCol,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 0.dp)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

// ── Header cell ──────────────────────────────────────────────────────

@Composable
private fun HdrCell(label: String, w: Dp) {
    Box(
        Modifier
            .width(w)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            label, color = Color.White,
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp, maxLines = 1
        )
    }
}

// ── Data row ─────────────────────────────────────────────────────────

@Composable
private fun DataRow(
    param: DroneParam,
    rowIdx: Int,
    isWriting: Boolean,
    hScroll: androidx.compose.foundation.ScrollState,
    metadataMap: Map<String, ParamMeta>,
    onWrite: (Float) -> Unit
) {
    val meta = remember(param.name, metadataMap) { metadataMap[param.name] ?: ParamMeta() }
    val bg = if (rowIdx % 2 == 0) RowWhite else RowAlt

    var editText by remember(param.value) { mutableStateOf(fmtValue(param.value)) }
    var isEditing by remember { mutableStateOf(false) }
    var dirty by remember(param.value) { mutableStateOf(false) }
    val fm = LocalFocusManager.current

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll)
            .background(bg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // # index
        Box(Modifier.width(ColW_Idx).padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                "${rowIdx + 1}", color = TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium
            )
        }

        // Name
        Box(Modifier.width(ColW_Name).padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                param.name, color = BluePrimary, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        // Description (tap to expand if truncated)
        Box(Modifier.width(ColW_Desc).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            val desc = meta.description
            if (desc.isNotEmpty()) {
                var showFullDesc by remember { mutableStateOf(false) }
                var isTruncated by remember { mutableStateOf(false) }

                Column {
                    Text(
                        desc, color = TextMed, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        lineHeight = 14.sp,
                        onTextLayout = { result -> isTruncated = result.hasVisualOverflow },
                        modifier = Modifier.clickable { if (isTruncated) showFullDesc = true }
                    )
                    if (isTruncated) {
                        Text(
                            "tap to read more…", color = BluePrimary.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                            modifier = Modifier.clickable { showFullDesc = true }
                        )
                    }
                }

                if (showFullDesc) {
                    AlertDialog(
                        onDismissRequest = { showFullDesc = false },
                        containerColor = TopBarBg,
                        confirmButton = {
                            TextButton(onClick = { showFullDesc = false }) {
                                Text("Close", color = BlueLight)
                            }
                        },
                        title = {
                            Text(
                                param.name, color = BlueLight,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        text = {
                            Column {
                                Text(desc, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                                if (meta.range.isNotEmpty() || meta.units.isNotEmpty() || meta.defaultValue.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (meta.range.isNotEmpty()) {
                                    Text("Range: ${meta.range}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                }
                                if (meta.units.isNotEmpty()) {
                                    Text("Units: ${meta.units}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                }
                                if (meta.defaultValue.isNotEmpty()) {
                                    Text("Default: ${meta.defaultValue}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            } else {
                Text("—", color = TextMuted, fontSize = 11.sp)
            }
        }

        // Units
        Box(Modifier.width(ColW_Units).padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            val units = meta.units
            if (units.isNotEmpty()) {
                Text(
                    units, color = TextMed, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE8F4FD))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            } else {
                Text("—", color = TextMuted, fontSize = 11.sp)
            }
        }

        // Default — pill style
        Box(Modifier.width(ColW_Default).padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            val defVal = meta.defaultValue
            if (defVal.isNotEmpty()) {
                Text(
                    defVal, color = TextMed, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFF0F0F5))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            } else {
                Text("—", color = TextMuted, fontSize = 11.sp)
            }
        }

        // Options dropdown or dash
        Box(
            Modifier.width(ColW_Options).padding(horizontal = 6.dp, vertical = 5.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (meta.options.isNotEmpty()) {
                OptionsDropdown(meta.options, param.value) { onWrite(it) }
            } else {
                Text(
                    "—", color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }
        }

        // Value (editable)
        Box(
            Modifier.width(ColW_Value).padding(horizontal = 6.dp, vertical = 5.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isEditing) BluePrimary.copy(alpha = 0.06f) else Color(0xFFFAFAFC))
                    .border(
                        width = 1.dp,
                        color = when {
                            dirty    -> Amber.copy(alpha = 0.7f)
                            isEditing -> BluePrimary.copy(alpha = 0.4f)
                            else     -> Color(0xFFE2E8F0)
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                BasicTextField(
                    value = editText,
                    onValueChange = { editText = it; dirty = it.trim() != fmtValue(param.value) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (dirty) Amber else TextDark,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = if (dirty) FontWeight.Bold else FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(BluePrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        fm.clearFocus()
                        val v = editText.trim().toFloatOrNull()
                        if (v != null && dirty) { onWrite(v); dirty = false }
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 7.dp)
                        .onFocusChanged { isEditing = it.isFocused }
                )

                if (dirty && !isWriting) {
                    IconButton(
                        onClick = {
                            fm.clearFocus()
                            val v = editText.trim().toFloatOrNull()
                            if (v != null) { onWrite(v); dirty = false }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Check, "Write", tint = GreenConfirm, modifier = Modifier.size(16.dp))
                    }
                }

                if (isWriting) {
                    CircularProgressIndicator(Modifier.size(16.dp).padding(2.dp), color = BluePrimary, strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ── Options dropdown ─────────────────────────────────────────────────

@Composable
private fun OptionsDropdown(
    options: Map<Int, String>,
    currentValue: Float,
    onSelect: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentInt = currentValue.toInt()
    val label = options[currentInt]?.let { "$currentInt : $it" } ?: fmtValue(currentValue)

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DropBg)
                .border(1.dp, Color(0xFFD5DCE6), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = TextDark, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ArrowDropDown, null, tint = TextMed, modifier = Modifier.size(18.dp))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(CardBg, RoundedCornerShape(10.dp))
                .border(0.5.dp, DividerCol, RoundedCornerShape(10.dp))
        ) {
            options.forEach { (key, text) ->
                val sel = key == currentInt
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Number badge
                            Text(
                                "$key", fontSize = 11.sp,
                                color = if (sel) Color.White else BluePrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (sel) BluePrimary else BluePrimary.copy(alpha = 0.1f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text, fontSize = 13.sp,
                                color = if (sel) BluePrimary else TextDark,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    },
                    onClick = { expanded = false; onSelect(key.toFloat()) },
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) BluePrimary.copy(alpha = 0.06f) else Color.Transparent)
                )
            }
        }
    }
}

