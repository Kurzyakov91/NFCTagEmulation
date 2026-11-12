package com.kurzyakov.nfctagemulation.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kurzyakov.nfctagemulation.R
import com.kurzyakov.nfctagemulation.ui.NfcStatus
import com.kurzyakov.nfctagemulation.ui.theme.NfcTagTheme

@Composable
fun StatusCard(
    status: NfcStatus,
    readerPresent: Boolean,
    onOpenNfcSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ready = status == NfcStatus.Ready
    val connected = readerPresent && ready

    val (title, subtitle) = when {
        connected -> R.string.status_reader_title to R.string.status_reader_subtitle
        status == NfcStatus.Ready -> R.string.status_ready_title to R.string.status_ready_subtitle
        status == NfcStatus.Disabled -> R.string.status_disabled_title to R.string.status_disabled_subtitle
        status == NfcStatus.NoHce -> R.string.status_no_hce_title to R.string.status_no_hce_subtitle
        else -> R.string.status_no_nfc_title to R.string.status_no_nfc_subtitle
    }
    val container by animateColorAsState(
        when {
            connected -> colors.tertiaryContainer
            ready -> colors.primaryContainer
            status == NfcStatus.Disabled -> colors.secondaryContainer
            else -> colors.surfaceContainerHighest
        },
        label = "statusContainer",
    )
    val content by animateColorAsState(
        when {
            connected -> colors.onTertiaryContainer
            ready -> colors.onPrimaryContainer
            status == NfcStatus.Disabled -> colors.onSecondaryContainer
            else -> colors.onSurfaceVariant
        },
        label = "statusContent",
    )
    val icon = when {
        connected -> Icons.Rounded.Sensors
        ready || status == NfcStatus.Disabled -> Icons.Rounded.Nfc
        else -> Icons.Rounded.ErrorOutline
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NfcPulse(icon = icon, tint = content, animated = ready, fast = connected)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
            if (status == NfcStatus.Disabled) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onOpenNfcSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.status_disabled_action))
                }
            }
        }
    }
}

/** Значок с расходящимися волнами: метка «излучает», пока NFC готов к работе. */
@Composable
private fun NfcPulse(icon: ImageVector, tint: Color, animated: Boolean, fast: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (fast) 1200 else 2600, easing = LinearEasing)),
        label = "pulsePhase",
    )
    Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        if (animated) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val maxRadius = size.minDimension / 2
                val minRadius = maxRadius * 0.45f
                repeat(RINGS) { ring ->
                    val p = (phase + ring.toFloat() / RINGS) % 1f
                    drawCircle(
                        color = tint,
                        radius = minRadius + (maxRadius - minRadius) * p,
                        alpha = (1f - p) * 0.45f,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = tint.copy(alpha = 0.12f),
            contentColor = tint,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(38.dp))
            }
        }
    }
}

private const val RINGS = 3

@Preview
@Composable
private fun StatusCardPreview() {
    NfcTagTheme { StatusCard(status = NfcStatus.Ready, readerPresent = false, onOpenNfcSettings = {}) }
}

@Preview
@Composable
private fun StatusCardDisabledPreview() {
    NfcTagTheme(darkTheme = true) { StatusCard(status = NfcStatus.Disabled, readerPresent = false, onOpenNfcSettings = {}) }
}
