package com.plan.ui.weights

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.plan.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightsScreen(
    viewModel: WeightsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            state.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                WeightChartCard(
                    title = "Daily Weight",
                    subtitle = pluralize(state.dailyWeights.size, "data point")
                ) {
                    if (state.dailyWeights.isNotEmpty()) {
                        DailyWeightChart(dataPoints = state.dailyWeights)
                    } else {
                        EmptyChartMessage("No weight data yet")
                    }
                }

                WeightChartCard(
                    title = "Weekly Average",
                    subtitle = pluralize(state.weeklyAverages.size, "week")
                ) {
                    if (state.weeklyAverages.isNotEmpty()) {
                        WeeklyAverageChart(dataPoints = state.weeklyAverages)
                    } else {
                        EmptyChartMessage("Not enough data for weekly averages")
                    }
                }

                WeightChartCard(
                    title = "Weekly Reduction",
                    subtitle = "Week-over-week change"
                ) {
                    if (state.weeklyReductions.isNotEmpty()) {
                        WeeklyReductionChart(dataPoints = state.weeklyReductions)
                    } else {
                        EmptyChartMessage("Not enough data for weekly reductions")
                    }
                }
            }
        }
    }
}

private fun pluralize(n: Int, word: String): String =
    if (n == 1) "1 $word" else "$n ${word}s"

@Composable
private fun WeightChartCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.md))
            content()
        }
    }
}

@Composable
private fun EmptyChartMessage(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ShowChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DailyWeightChart(dataPoints: List<WeightDataPoint>) {
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.toArgb()

    val labels = remember(dataPoints) {
        val total = dataPoints.size
        if (total == 0) emptyList()
        else {
            val step = maxOf(1, total / 6)
            dataPoints.mapIndexed { i, dp ->
                if (i % step == 0 || i == total - 1) formatDdMm(dp.date) else ""
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)
                legend.isEnabled = false
                axisRight.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.granularity = 1f
                xAxis.setDrawGridLines(false)
                xAxis.setAvoidFirstLastClipping(true)
                setNoDataText("")
            }
        },
        update = { chart ->
            applyAxisTheme(chart.xAxis, axisColor)
            applyAxisTheme(chart.axisLeft, axisColor, gridColor)
            val entries = dataPoints.mapIndexed { i, dp -> Entry(i.toFloat(), dp.weight) }
            val dataSet = LineDataSet(entries, "Weight").apply {
                color = primary
                setCircleColor(primary)
                circleRadius = 3f
                lineWidth = 2.5f
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
                setDrawFilled(true)
                fillColor = primary
                fillAlpha = 30
            }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            chart.data = LineData(dataSet)
            chart.invalidate()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    )
}

private fun formatDdMm(ddMMyyyy: String): String =
    if (ddMMyyyy.length >= 4) "${ddMMyyyy.substring(0,2)}/${ddMMyyyy.substring(2,4)}" else ddMMyyyy

private fun thinnedLabels(values: List<String>, maxVisible: Int = 6): List<String> {
    val total = values.size
    if (total == 0) return emptyList()
    val step = maxOf(1, total / maxVisible)
    return values.mapIndexed { i, v ->
        if (i % step == 0 || i == total - 1) v else ""
    }
}

private fun applyAxisTheme(
    axis: com.github.mikephil.charting.components.AxisBase,
    color: Int,
    gridColor: Int = color
) {
    axis.textColor = color
    axis.axisLineColor = color
    if (axis is com.github.mikephil.charting.components.YAxis) {
        axis.gridColor = gridColor
    } else if (axis is com.github.mikephil.charting.components.XAxis) {
        axis.gridColor = gridColor
    }
}

@Composable
private fun WeeklyAverageChart(dataPoints: List<WeightDataPoint>) {
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val labels = remember(dataPoints) {
        thinnedLabels(dataPoints.map { it.weekLabel.takeLast(4) })
    }

    AndroidView(
        factory = { ctx ->
            LineChart(ctx).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                legend.isEnabled = false
                axisRight.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.granularity = 1f
                xAxis.setDrawGridLines(false)
                xAxis.setAvoidFirstLastClipping(true)
            }
        },
        update = { chart ->
            applyAxisTheme(chart.xAxis, axisColor)
            applyAxisTheme(chart.axisLeft, axisColor, gridColor)
            val entries = dataPoints.mapIndexed { i, dp -> Entry(i.toFloat(), dp.weight) }
            val dataSet = LineDataSet(entries, "Avg Weight").apply {
                color = primary
                setCircleColor(primary)
                circleRadius = 4f
                lineWidth = 2.5f
                setDrawValues(false)
                mode = LineDataSet.Mode.LINEAR
                setDrawFilled(true)
                fillColor = primary
                fillAlpha = 30
            }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            chart.data = LineData(dataSet)
            chart.invalidate()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    )
}

@Composable
private fun WeeklyReductionChart(dataPoints: List<WeightDataPoint>) {
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val secondary = MaterialTheme.colorScheme.secondary.toArgb()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val labels = remember(dataPoints) {
        thinnedLabels(dataPoints.map { it.weekLabel.takeLast(4) })
    }

    AndroidView(
        factory = { ctx ->
            BarChart(ctx).apply {
                description.isEnabled = false
                setTouchEnabled(true)
                legend.isEnabled = false
                axisRight.isEnabled = false
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.granularity = 1f
                xAxis.setDrawGridLines(false)
                xAxis.setAvoidFirstLastClipping(true)
            }
        },
        update = { chart ->
            applyAxisTheme(chart.xAxis, axisColor)
            applyAxisTheme(chart.axisLeft, axisColor, gridColor)
            val entries = dataPoints.mapIndexed { i, dp -> BarEntry(i.toFloat(), dp.weight) }
            val colors = dataPoints.map { dp ->
                if (dp.weight >= 0) primary else secondary
            }
            val dataSet = BarDataSet(entries, "Reduction").apply {
                this.colors = colors
                setDrawValues(false)
            }
            chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            chart.data = BarData(dataSet)
            chart.invalidate()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
    )
}
