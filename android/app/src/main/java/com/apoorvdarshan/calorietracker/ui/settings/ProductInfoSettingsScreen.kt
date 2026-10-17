package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.ProductInfoField
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Settings → App Preferences → Product information (#427), the Android port of iOS
 * ProductInfoSettingsView: turn off the barcode product details block or choose which
 * Open Food Facts fields it shows. Allergen check is not one of these fields.
 */
@Composable
fun ProductInfoSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    // null until DataStore answers, so a disabled block never flashes as enabled.
    val shown by container.prefs.productInfoShown.collectAsState<Boolean, Boolean?>(initial = null)
    val hidden by container.prefs.productInfoHiddenFields.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = BottomNavScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.nav_settings),
                        color = AppColors.Calorie,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.product_information),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            val isShown = shown ?: return@LazyColumn
            item {
                SectionCard {
                    ToggleRow(
                        label = stringResource(R.string.product_info_settings_show),
                        checked = isShown,
                        onChange = { value -> scope.launch { container.prefs.setProductInfoShown(value) } }
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.product_info_settings_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (isShown) {
                item {
                    SectionCard(title = stringResource(R.string.product_info_settings_fields)) {
                        ProductInfoField.entries.forEachIndexed { index, field ->
                            if (index > 0) HorizontalDivider()
                            ToggleRow(
                                label = stringResource(field.labelRes),
                                checked = field !in hidden,
                                onChange = { visible ->
                                    scope.launch { container.prefs.setProductInfoFieldHidden(field, !visible) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
