package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.DetailParams
import com.lumora.photoeditor.ui.state.EditorViewModel
import com.lumora.photoeditor.ui.theme.Lumora

@Composable
fun DetailPanel(vm: EditorViewModel) {
    val state by vm.state.collectAsState()
    val d = state.detail
    // One-tap auto-denoise feedback: what was suggested, or why nothing changed.
    var denoiseOutcome by remember { mutableStateOf<EditorViewModel.AutoDenoiseOutcome?>(null) }
    // Stale guard: the analysis may finish after the user opened another
    // photo — never show one photo's denoise verdict on the next.
    val libraryImage by vm.libraryImage.collectAsState()
    LaunchedEffect(libraryImage?.id) { denoiseOutcome = null }
    PanelColumn {
        PanelHeader(
            title = "Detail",
            showReset = state.detail != DetailParams(),
            resetLabel = "Reset detail adjustments",
            onReset = { vm.update("Reset detail") { s -> s.copy(detail = DetailParams()) } },
            actionLabel = "Auto",
            actionContentDescription =
                "Auto denoise: analyze this photo's noise and set noise reduction",
            onAction = {
                denoiseOutcome = null
                vm.autoDenoise { outcome -> denoiseOutcome = outcome }
            },
        )
        denoiseOutcome?.let { outcome ->
            val failed = outcome is EditorViewModel.AutoDenoiseOutcome.Failed
            Text(
                text = when (outcome) {
                    is EditorViewModel.AutoDenoiseOutcome.Failed ->
                        "Couldn't analyze this photo — try again"
                    is EditorViewModel.AutoDenoiseOutcome.NoChange ->
                        "Already clean — no noise reduction needed"
                    is EditorViewModel.AutoDenoiseOutcome.Applied ->
                        "Auto denoise: ${outcome.summary} — adjust or undo freely"
                },
                style = Lumora.type.caption,
                color = if (failed) Lumora.colors.danger else Lumora.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Transient: dismiss after 5s; a fresh result restarts the timer
            // because the effect is keyed on the feedback instance.
            LaunchedEffect(outcome) {
                kotlinx.coroutines.delay(5000)
                denoiseOutcome = null
            }
        }
        PanelSection("Sharpening") {
            ParamSlider(vm, "Amount", d.sharpening, 0f..150f, "Sharpening") { s, v ->
                s.copy(detail = s.detail.copy(sharpening = v))
            }
            ParamSlider(vm, "Radius", d.radius, 0.5f..3f, "Sharpen radius", default = 1f) { s, v ->
                s.copy(detail = s.detail.copy(radius = v))
            }
            ParamSlider(vm, "Detail", d.detail, 0f..100f, "Sharpen detail", default = 25f) { s, v ->
                s.copy(detail = s.detail.copy(detail = v))
            }
            ParamSlider(vm, "Masking", d.masking, 0f..100f, "Sharpen masking") { s, v ->
                s.copy(detail = s.detail.copy(masking = v))
            }
        }
        PanelSection("Noise reduction") {
            ParamSlider(vm, "Luminance", d.luminanceNr, 0f..100f, "Luminance NR") { s, v ->
                s.copy(detail = s.detail.copy(luminanceNr = v))
            }
            ParamSlider(vm, "Luminance detail", d.luminanceDetail, 0f..100f, "Luminance detail",
                default = 50f) { s, v ->
                s.copy(detail = s.detail.copy(luminanceDetail = v))
            }
            ParamSlider(vm, "Color", d.colorNr, 0f..100f, "Color NR", default = 25f) { s, v ->
                s.copy(detail = s.detail.copy(colorNr = v))
            }
            ParamSlider(vm, "Color detail", d.colorDetail, 0f..100f, "Color NR detail",
                default = 50f) { s, v ->
                s.copy(detail = s.detail.copy(colorDetail = v))
            }
        }
        PanelSection("Moiré reduction") {
            ParamSlider(vm, "Moiré", d.moire, 0f..100f, "Moiré reduction") { s, v ->
                s.copy(detail = s.detail.copy(moire = v))
            }
        }
        PanelSection("Deconvolution") {
            ParamSlider(vm, "Amount", d.deconv, 0f..100f, "Deconvolution") { s, v ->
                s.copy(detail = s.detail.copy(deconv = v))
            }
            ParamSlider(vm, "Radius", d.deconvRadius, 0.5f..1.5f, "Deconvolution radius",
                default = 1f) { s, v ->
                s.copy(detail = s.detail.copy(deconvRadius = v))
            }
            Text(
                "Inverts mild capture blur (Wiener filter) — for slightly " +
                    "soft shots, not heavy blur. Skips the defringe offset " +
                    "while active.",
                style = Lumora.type.caption,
                color = Lumora.colors.textTertiary,
            )
        }
    }
}
