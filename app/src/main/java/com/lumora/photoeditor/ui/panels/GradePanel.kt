package com.lumora.photoeditor.ui.panels

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.engine.GradingParams
import com.lumora.photoeditor.ui.components.GradeWheelControl
import com.lumora.photoeditor.ui.state.EditorViewModel

@Composable
fun GradePanel(vm: EditorViewModel) {
    val state by vm.state.collectAsState()
    val g = state.grading

    fun begin() = vm.beginGesture()
    fun end(label: String) = vm.endGesture(label)

    PanelColumn {
        PanelHeader(
            title = "Grade",
            showReset = state.grading != GradingParams(),
            resetLabel = "Reset color grading",
            onReset = { vm.update("Reset grading") { s -> s.copy(grading = GradingParams()) } },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            GradeWheelControl("Shadows", g.shadows,
                onBegin = ::begin,
                onChange = { w -> vm.update(gesture = true) { s ->
                    s.copy(grading = s.grading.copy(shadows = w)) } },
                onEnd = { end("Grade shadows") })
            GradeWheelControl("Midtones", g.midtones,
                onBegin = ::begin,
                onChange = { w -> vm.update(gesture = true) { s ->
                    s.copy(grading = s.grading.copy(midtones = w)) } },
                onEnd = { end("Grade midtones") })
            GradeWheelControl("Highlights", g.highlights,
                onBegin = ::begin,
                onChange = { w -> vm.update(gesture = true) { s ->
                    s.copy(grading = s.grading.copy(highlights = w)) } },
                onEnd = { end("Grade highlights") })
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            GradeWheelControl("Global", g.global,
                onBegin = ::begin,
                onChange = { w -> vm.update(gesture = true) { s ->
                    s.copy(grading = s.grading.copy(global = w)) } },
                onEnd = { end("Grade global") })
        }
        PanelSection("Blend") {
            ParamSlider(vm, "Balance", g.balance, historyLabel = "Grading balance") { s, v ->
                s.copy(grading = s.grading.copy(balance = v))
            }
            ParamSlider(vm, "Blending", g.blending, 0f..100f, "Grading blending", default = 100f) { s, v ->
                s.copy(grading = s.grading.copy(blending = v))
            }
        }
    }
}
