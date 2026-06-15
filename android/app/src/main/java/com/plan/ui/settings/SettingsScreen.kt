package com.plan.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plan.data.api.Settings
import com.plan.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Locale

/** Format a stored "ddMMyyyy" string as DD/MM/YYYY for display. */
private fun formatDmy(ddMMyyyy: String): String = try {
    val src = SimpleDateFormat("ddMMyyyy", Locale.US)
    val dst = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    dst.format(src.parse(ddMMyyyy)!!)
} catch (_: Exception) { ddMMyyyy }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
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

            state.error != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(Spacing.sm))
                Button(onClick = { viewModel.loadSettings() }) {
                    Text("Retry")
                }
            }

            else -> {
                val settings = state.settings ?: return@Scaffold
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    DailySettingsCard(settings)
                    PersonSettingsCard(settings)
                    GroupsSettingsCard(settings)
                    SystemSettingsCard(settings)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(Spacing.xs))
            content()
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DailySettingsCard(settings: Settings) {
    SettingsCard("Daily Nutrition") {
        SettingsRow("Calories target", "${settings.daily.calories}")
        SettingsRow("Calorie type", settings.daily.calorie_type)
        SettingsRow("Fat", "${settings.daily.fat}%")
        SettingsRow("Protein per kg", "${settings.daily.protein} g/kg")
        SettingsRow("TDEE multiplier", "${settings.daily.tdee_multiplier}")
    }
}

@Composable
private fun PersonSettingsCard(settings: Settings) {
    SettingsCard("Person") {
        SettingsRow("Birth day", formatDmy(settings.person.birth_day))
        SettingsRow("Gender", settings.person.gender)
        SettingsRow("Height", "${settings.person.height} cm")
    }
}

@Composable
private fun GroupsSettingsCard(settings: Settings) {
    SettingsCard("Groups") {
        settings.groups.forEachIndexed { i, group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "×${group.new_day_amount}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (i < settings.groups.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
    }
}

@Composable
private fun SystemSettingsCard(settings: Settings) {
    SettingsCard("System") {
        SettingsRow("Timezone", settings.timezone_name)
        SettingsRow("Start date", formatDmy(settings.start_date))
    }
}
