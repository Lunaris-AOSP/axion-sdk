/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.axion.compose.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.android.axion.blur.AxBlurSurfaceDefaults
import com.android.axion.blur.axBlurBackground

@Composable
fun AxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    containerColor: Color = AlertDialogDefaults.containerColor,
) {
    val shape = AlertDialogDefaults.shape
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.composed {
            Modifier.clip(shape).axBlurBackground(
                enabled = true,
                fallbackColor = containerColor,
                tintColor = AxBlurSurfaceDefaults.tintColor(containerColor),
                shape = shape,
            )
        },
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        containerColor = Color.Transparent,
    )
}
