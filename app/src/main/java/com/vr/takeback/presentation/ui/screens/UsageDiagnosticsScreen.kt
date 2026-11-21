package com.vr.takeback.presentation.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vr.takeback.data.repository.DailyUsageSummary
import com.vr.takeback.data.repository.UsageStatsRepository
import com.vr.takeback.domain.usage.UsageDiagnostics
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDiagnosticsScreen(
    usageStatsRepository: UsageStatsRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    val usageSummary by produceState<DailyUsageSummary?>(
        initialValue = null,
        key1 = selectedDate
    ) {
        value = usageStatsRepository.getUsageSummaryForDate(selectedDate)
    }

    val diagnostics = usageSummary?.diagnostics ?: usageStatsRepository.getLatestDiagnostics()

    fun copyReportToClipboard() {
        if (diagnostics == null) {
            Toast.makeText(context, "No diagnostics available", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("TakeBack Diagnostics", diagnostics.toFormattedReport())
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Diagnostics report copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Usage Diagnostics",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Raw OS vs Reconstructed Screen Time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = ::copyReportToClipboard) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Report")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Date Switcher Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val today = LocalDate.now()
                    val dates = listOf(today, today.minusDays(1), today.minusDays(2))
                    dates.forEach { date ->
                        val isSelected = date == selectedDate
                        val label = when (date) {
                            today -> "Today"
                            today.minusDays(1) -> "Yesterday"
                            else -> date.dayOfWeek.name.take(3)
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedDate = date },
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (diagnostics == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                // Summary Comparison Matrix Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Ground-Truth Comparison",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (diagnostics.queryInfo.isSuccess) Color(0xFF064E3B) else MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = if (diagnostics.queryInfo.isSuccess) "VERIFIED" else "ERROR",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (diagnostics.queryInfo.isSuccess) Color(0xFFA7F3D0) else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            DiagnosticRow(
                                label = "TakeBack Screen-On Time (Union)",
                                value = formatMinutesSeconds(diagnostics.calculationDiagnostics.deviceScreenOnTimeMillis),
                                isHighlight = true,
                                color = MaterialTheme.colorScheme.primary
                            )
                            DiagnosticRow(
                                label = "TakeBack Total App Time (Sum)",
                                value = formatMinutesSeconds(diagnostics.calculationDiagnostics.totalAppUsageMillis)
                            )
                            DiagnosticRow(
                                label = "OS queryAndAggregateUsageStats()",
                                value = formatMinutesSeconds(diagnostics.rawOsResults.queryAndAggregateTotalMillis),
                                subtitle = "Unrotated interval bucket (${diagnostics.rawOsResults.queryAndAggregatePackageCount} pkgs)"
                            )
                            DiagnosticRow(
                                label = "OS queryUsageStats(INTERVAL_DAILY)",
                                value = formatMinutesSeconds(diagnostics.rawOsResults.dailyIntervalTotalMillis),
                                subtitle = "${diagnostics.rawOsResults.dailyBucketCount} daily file buckets"
                            )
                            DiagnosticRow(
                                label = "Raw UsageEvents In Interval",
                                value = "${diagnostics.rawOsResults.eventCount} events",
                                subtitle = "Span: ${diagnostics.rawOsResults.firstEventTimestampFormatted ?: "-"} to ${diagnostics.rawOsResults.lastEventTimestampFormatted ?: "-"}"
                            )

                            val overReportingMillis = diagnostics.rawOsResults.queryAndAggregateTotalMillis - diagnostics.calculationDiagnostics.totalAppUsageMillis
                            if (overReportingMillis > 0) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Filled.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "OS aggregate over-reported by ${formatMinutesSeconds(overReportingMillis)} due to pre-midnight bucket overlap.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Query Metadata Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Query Window Parameters",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            MetadataText("Target Date: ${diagnostics.queryInfo.targetDate}")
                            MetadataText("Timezone: ${diagnostics.queryInfo.timeZoneId}")
                            MetadataText("Start (Inclusive): ${diagnostics.queryInfo.startFormatted}")
                            MetadataText("End (Exclusive): ${diagnostics.queryInfo.endFormatted}")
                            MetadataText("API Level: ${diagnostics.queryInfo.apiLevel}")
                            MetadataText("Permission: ${if (diagnostics.queryInfo.hasUsagePermission) "Granted" else "Denied"}")
                        }
                    }
                }

                // Package Filtering Breakdown Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Package Filtering Policy",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${diagnostics.filterDiagnostics.eligiblePackagesCount} Included • ${diagnostics.filterDiagnostics.excludedPackagesCount} Excluded (${diagnostics.filterDiagnostics.totalPackagesEvaluated} total evaluated)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Show filter decision samples
                            diagnostics.filterDiagnostics.filterDecisions.entries.take(15).forEach { (pkg, reason) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pkg,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = reason,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (reason.startsWith("INCLUDED")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                // Copy Report Button Action
                item {
                    Button(
                        onClick = ::copyReportToClipboard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Complete Diagnostic Report")
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    subtitle: String? = null,
    isHighlight: Boolean = false,
    color: Color = Color.Unspecified
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun MetadataText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

private fun formatMinutesSeconds(millis: Long): String {
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m ${s}s"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
