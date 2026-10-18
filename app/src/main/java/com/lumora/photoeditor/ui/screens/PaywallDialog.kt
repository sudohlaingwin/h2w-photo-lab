package com.lumora.photoeditor.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.data.billing.KeyValidation
import com.lumora.photoeditor.data.billing.LicenseStatus
import com.lumora.photoeditor.data.billing.LicenseStore
import com.lumora.photoeditor.data.billing.PRO_FEATURES
import com.lumora.photoeditor.data.billing.isPro
import com.lumora.photoeditor.data.billing.licenseStatusText
import com.lumora.photoeditor.ui.components.LumoraDialog
import com.lumora.photoeditor.ui.components.LumoraTextField
import com.lumora.photoeditor.ui.theme.Lumora

/**
 * Phase D#9 slice 1 — the paywall UI SKELETON. It shows the real Pro
 * feature list, the current license state, and a license-key entry, but
 * no store is connected and no prices are set: the copy says so plainly
 * instead of faking a purchase flow. The future store only needs to
 * write a key via [LicenseStore.activateKey].
 */
@Composable
fun PaywallDialog(
    store: LicenseStore,
    onDismiss: () -> Unit,
) {
    val colors = Lumora.colors
    val type = Lumora.type
    val status = remember { store.status.value }
    val pro = isPro(status)
    var key by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeOk by remember { mutableStateOf(false) }

    LumoraDialog(
        onDismissRequest = onDismiss,
        title = "H2W Photo Lab Pro",
        confirmLabel = if (pro) "Done" else "Activate key",
        confirmEnabled = pro || key.isNotBlank(),
        onConfirm = {
            if (pro) {
                onDismiss()
            } else {
                when (val v = store.activateKey(key)) {
                    is KeyValidation.Valid -> {
                        noticeOk = true
                        notice = "License activated — welcome to Pro."
                    }
                    is KeyValidation.Invalid -> {
                        noticeOk = false
                        notice = v.reason
                    }
                }
            }
        },
        dismissLabel = "Close",
        onDismiss = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    licenseStatusText(status),
                    style = type.controlLabel,
                    color = if (pro) colors.accent else colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                for (feature in PRO_FEATURES) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 3.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(feature, style = type.body, color = colors.textPrimary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "No store is connected yet and no prices are set — " +
                        "pricing is the photographer's business decision. " +
                        "This screen is the skeleton the future store will fill.",
                    style = type.caption,
                    color = colors.textTertiary,
                )
                if (!pro) {
                    Spacer(Modifier.height(12.dp))
                    LumoraTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            notice = null
                        },
                        label = "License key",
                        placeholder = "XXXX-XXXX-XXXX-XXXX",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (notice != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            notice!!,
                            style = type.caption,
                            color = if (noticeOk) colors.accent else colors.danger,
                        )
                    }
                }
            }
        },
    )
}
