/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.ui.screen.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.health.openscale.R
import com.health.openscale.core.bluetooth.LiveWeighingState
import com.health.openscale.ui.navigation.Routes
import com.health.openscale.ui.screen.settings.BluetoothViewModel
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Global dialog that activates automatically when the user steps on a supported scale
 * (e.g. Xiaomi Mi Scale / Body Composition Scale 2) while the app is open.
 *
 * It mirrors the official Zepp Life experience:
 * 1. Live fluctuating weight is displayed in real time in sync with the scale display.
 * 2. When weight locks, an impedance progress indicator prompts the user to remain standing.
 * 3. When impedance completes, a summary of body metrics is displayed and the app automatically
 *    navigates to the detailed measurement screen.
 */
@Composable
fun LiveWeighingDialog(
    bluetoothViewModel: BluetoothViewModel,
    navController: NavController
) {
    val liveState by bluetoothViewModel.liveWeighingState.collectAsState()

    val currentState = liveState
    if (currentState is LiveWeighingState.Idle) {
        return
    }

    // Auto-dismiss the pop-up window 4 seconds after a successful weigh-in
    if (currentState is LiveWeighingState.Completed) {
        LaunchedEffect(currentState) {
            delay(4000)
            bluetoothViewModel.resetLiveWeighingState()
        }
    }

    val dialogTitle = when (currentState) {
        is LiveWeighingState.WaitingForScale -> currentState.deviceName ?: stringResource(R.string.live_weighing_title)
        is LiveWeighingState.Measuring -> currentState.deviceName ?: stringResource(R.string.live_weighing_title)
        is LiveWeighingState.Completed -> currentState.deviceName ?: stringResource(R.string.live_weighing_title)
        LiveWeighingState.Idle -> stringResource(R.string.live_weighing_title)
    }

    AlertDialog(
        onDismissRequest = {
            bluetoothViewModel.resetLiveWeighingState()
        },
        icon = {
            if (currentState is LiveWeighingState.Completed) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_weight),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        title = {
            Text(
                text = dialogTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                when (currentState) {
                    is LiveWeighingState.WaitingForScale -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(44.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.live_weighing_waiting_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.live_weighing_waiting_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    is LiveWeighingState.Measuring -> {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = String.format(Locale.getDefault(), "%.2f", currentState.weightKg),
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (currentState.isStabilized) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentState.unit.name.lowercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        if (!currentState.isStabilized) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.live_weighing_measuring_weight),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (currentState.isMeasuringImpedance) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                                Text(
                                    text = stringResource(R.string.live_weighing_measuring_impedance),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.live_weighing_keep_standing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.live_weighing_weight_locked),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    is LiveWeighingState.Completed -> {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = String.format(Locale.getDefault(), "%.2f", currentState.weightKg),
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentState.unit.name.lowercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        Text(
                            text = stringResource(R.string.live_weighing_complete),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (currentState.bodyFatPercent != null || currentState.musclePercent != null || currentState.waterPercent != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                currentState.bodyFatPercent?.let { fat ->
                                    LiveMetricChip(
                                        iconRes = R.drawable.ic_fat,
                                        label = stringResource(R.string.live_weighing_body_fat),
                                        value = String.format(Locale.getDefault(), "%.1f%%", fat)
                                    )
                                }
                                currentState.musclePercent?.let { muscle ->
                                    LiveMetricChip(
                                        iconRes = R.drawable.ic_muscle,
                                        label = stringResource(R.string.live_weighing_muscle),
                                        value = String.format(Locale.getDefault(), "%.1f%%", muscle)
                                    )
                                }
                                currentState.waterPercent?.let { water ->
                                    LiveMetricChip(
                                        iconRes = R.drawable.ic_water,
                                        label = stringResource(R.string.live_weighing_water),
                                        value = String.format(Locale.getDefault(), "%.1f%%", water)
                                    )
                                }
                            }
                        }
                    }
                    LiveWeighingState.Idle -> { /* unreachable */ }
                }
            }
        },
        confirmButton = {
            if (currentState is LiveWeighingState.Completed) {
                Button(
                    onClick = {
                        val mId = currentState.measurementId
                        val uId = currentState.userId
                        bluetoothViewModel.resetLiveWeighingState()
                        if (mId > 0) {
                            navController.navigate(Routes.measurementDetail(mId, uId))
                        }
                    }
                ) {
                    Text(stringResource(R.string.live_weighing_view_details))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    bluetoothViewModel.resetLiveWeighingState()
                }
            ) {
                Text(
                    text = if (currentState is LiveWeighingState.WaitingForScale) {
                        stringResource(R.string.live_weighing_cancel)
                    } else {
                        stringResource(R.string.live_weighing_dismiss)
                    }
                )
            }
        }
    )
}

@Composable
private fun LiveMetricChip(
    iconRes: Int,
    label: String,
    value: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
