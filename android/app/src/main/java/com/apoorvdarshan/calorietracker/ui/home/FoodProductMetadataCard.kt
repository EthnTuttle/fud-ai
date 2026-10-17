package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.ProductInfoField

/**
 * Open Food Facts details for a barcode log. [fields] comes from
 * [ProductInfoField.visibleFields], so it already honours the user's Product information
 * settings (#427) and only lists fields with data.
 */
@Composable
internal fun FoodProductMetadataCard(
    metadata: FoodProductMetadata,
    fields: List<ProductInfoField>
) {
    val rows = fields.mapNotNull { field ->
        val label = stringResource(field.labelRes)
        when (field) {
            ProductInfoField.BARCODE -> ProductMetadataItem(label, metadata.barcode)
            ProductInfoField.PACKAGE -> metadata.packageQuantity?.let { ProductMetadataItem(label, it) }
            ProductInfoField.NUTRI_SCORE -> metadata.nutriScore?.let { ProductMetadataItem(label, it) }
            ProductInfoField.NOVA_GROUP -> metadata.novaGroup?.let { ProductMetadataItem(label, it.toString()) }
            ProductInfoField.ECO_SCORE -> metadata.ecoScore?.let { ProductMetadataItem(label, it) }
            ProductInfoField.ALLERGENS -> metadata.allergens.stackedItem(label)
            ProductInfoField.TRACES -> metadata.traces.stackedItem(label)
            ProductInfoField.LABELS -> metadata.labels.stackedItem(label)
            ProductInfoField.CATEGORIES -> metadata.categories.stackedItem(label)
            ProductInfoField.INGREDIENT_LABEL -> null
        }
    }
    val ingredientsText = metadata.ingredientsText?.takeIf { ProductInfoField.INGREDIENT_LABEL in fields }

    SheetPillCard {
        rows.forEachIndexed { index, item ->
            if (index > 0) SheetHairline()
            ProductMetadataRow(item)
        }
        ingredientsText?.let { ingredients ->
            if (rows.isNotEmpty()) SheetHairline()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.product_ingredient_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    ingredients,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
            }
        }
    }
}

private data class ProductMetadataItem(
    val label: String,
    val value: String,
    val stacked: Boolean = false
)

private fun List<String>.stackedItem(label: String): ProductMetadataItem? =
    takeIf { it.isNotEmpty() }?.let { ProductMetadataItem(label, it.joinToString(", "), stacked = true) }

@Composable
private fun ProductMetadataRow(item: ProductMetadataItem) {
    if (item.stacked) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                item.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                item.value,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            item.label,
            fontSize = 15.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Text(
            item.value,
            modifier = Modifier.weight(1.6f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            fontSize = 15.sp,
            textAlign = TextAlign.End
        )
    }
}
