package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AllergenAssessment
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.IngredientLabelItem
import com.apoorvdarshan.calorietracker.models.IngredientLabelParser
import com.apoorvdarshan.calorietracker.models.ProductInfoField
import com.apoorvdarshan.calorietracker.models.displayName

/**
 * Open Food Facts details for a barcode log. [fields] comes from
 * [ProductInfoField.visibleFields], so it already honours the user's Product information
 * settings (#427) and only lists fields with data.
 */
@Composable
internal fun FoodProductMetadataCard(
    metadata: FoodProductMetadata,
    fields: List<ProductInfoField>,
    /** Ingredient label entries that mention one of these are highlighted (#428). */
    allergenSensitivities: List<String> = emptyList()
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
            IngredientLabelSection(ingredients, allergenSensitivities)
        }
    }
}

/**
 * The scanned ingredient label as a readable list (#428), display only. Entries Open Food Facts
 * marks as allergens are bold; entries matching the user's allergen sensitivities get a warning
 * icon. Labels that do not split into a list stay a paragraph.
 */
@Composable
private fun IngredientLabelSection(text: String, sensitivities: List<String>) {
    val items = remember(text, sensitivities) { IngredientLabelParser.items(text, sensitivities) }
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
    val emphasis = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(R.string.product_ingredient_label),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }
        )
        if (items.isEmpty()) {
            Text(
                ingredientLabelText(IngredientLabelParser.segments(text.trim()), emphasis),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = secondary
            )
        } else {
            items.forEach { item -> IngredientLabelRow(item, secondary, emphasis) }
        }
    }
}

@Composable
private fun IngredientLabelRow(item: IngredientLabelItem, secondary: Color, emphasis: Color) {
    val description = if (item.matchesSensitivity) {
        "${AllergenAssessment.POSSIBLE_ALLERGEN.displayName}: ${item.text}"
    } else {
        item.text
    }
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.Top
    ) {
        if (item.matchesSensitivity) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = AllergenWarningColor,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(14.dp)
            )
        } else {
            Text("•", fontSize = 14.sp, lineHeight = 20.sp, color = secondary)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            ingredientLabelText(item.segments, emphasis),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = if (item.matchesSensitivity) FontWeight.SemiBold else null,
            color = if (item.matchesSensitivity) emphasis else secondary
        )
    }
}

private fun ingredientLabelText(segments: List<IngredientLabelItem.Segment>, emphasis: Color): AnnotatedString =
    buildAnnotatedString {
        segments.forEach { segment ->
            if (segment.isAllergen) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = emphasis)) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }

/** Matches the iOS system orange used for the same warning. */
private val AllergenWarningColor = Color(0xFFFF9500)

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
