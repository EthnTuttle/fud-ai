package com.apoorvdarshan.calorietracker.ui.voice

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

/** The orb on Home that starts a voice conversation. */
@Composable
fun VoiceAssistantButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 60.dp) {
    val label = stringResource(R.string.voice_talk_to_fud_ai)
    Box(
        modifier
            .size(size)
            .shadow(14.dp, CircleShape, ambientColor = AppColors.Calorie, spotColor = AppColors.Calorie)
            .clip(CircleShape)
            .border(1.5.dp, Brush.linearGradient(listOf(AppColors.CalorieEnd, Color.White.copy(alpha = 0.35f))), CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .testTag("voiceAssistantButton")
    ) {
        LiveOrb(mode = OrbMode.IDLE, tint = AppColors.Calorie, modifier = Modifier.fillMaxSize())
    }
}
