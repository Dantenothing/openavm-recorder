package com.dante.zeekrcapabilitylab.ui.product

import android.app.DatePickerDialog
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.RecordingDateFilter
import com.dante.zeekrcapabilitylab.util.Utils
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun RecordingDateControls(selectedEpochDay: Long?, enabled: Boolean, onSelect: (Long?) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = AppLanguage.locale
    val today = RecordingDateFilter.today(System.currentTimeMillis(), ZoneId.systemDefault())
    var picking by remember { mutableStateOf(false) }
    val latestSelect by rememberUpdatedState(onSelect)
    val latestEnabled by rememberUpdatedState(enabled)
    val selected = selectedEpochDay?.let(LocalDate::ofEpochDay)
    val label = selected?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                null to Utils.t("All dates", "全部日期"),
                0L to Utils.t("Today", "今天"),
                -1L to Utils.t("Yesterday", "昨天"),
            ).forEach { (offset, text) ->
                val date = offset?.let { today.plusDays(it).toEpochDay() }
                OutlinedButton(onClick = {
                    onSelect(offset?.let { RecordingDateFilter.today(System.currentTimeMillis(), ZoneId.systemDefault())
                        .plusDays(it).toEpochDay() })
                }, enabled = enabled) {
                    Text(text + if (selectedEpochDay == date) " ✓" else "")
                }
            }
            OutlinedButton(onClick = { picking = true }, enabled = enabled) {
                Text(Utils.t("Choose date", "选择日期") + (label?.let { " · $it" } ?: ""))
            }
        }
        if (selected != null) Text(Utils.t("Filtered by recording start date", "按录像开始日期筛选"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (picking) DisposableEffect(context, locale, configuration) {
        val datedContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog).apply {
            applyOverrideConfiguration(Configuration(configuration).apply { setLocale(locale) })
        }
        val initial = selected ?: today
        val dialog = DatePickerDialog(datedContext, { _, year, month, day ->
            if (latestEnabled) latestSelect(LocalDate.of(year, month + 1, day).toEpochDay())
            picking = false
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        dialog.setTitle(Utils.t("Choose date", "选择日期"))
        dialog.setOnDismissListener { picking = false }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
