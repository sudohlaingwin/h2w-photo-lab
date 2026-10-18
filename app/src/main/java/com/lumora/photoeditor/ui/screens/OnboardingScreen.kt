package com.lumora.photoeditor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumora.photoeditor.data.Presets
import com.lumora.photoeditor.ui.theme.Lumora
import com.lumora.photoeditor.ui.theme.LumoraIcons
import kotlinx.coroutines.launch

/**
 * First-run welcome tour: 4 swipeable pages introducing H2W Photo Lab's
 * headline capabilities. Shown once on first launch (flag in AppSettings),
 * replayable from Settings → About.
 */
private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

// The preset count is read from the real built-in list, never hardcoded —
// the tour must not go stale again when presets are added.
private fun onboardingPages(presetCount: Int): List<OnboardingPage> = listOf(
    OnboardingPage(
        icon = LumoraIcons.PhotoCamera,
        title = "Welcome to H2W Photo Lab",
        body = "A professional photo editor in your pocket — precise, " +
            "non-destructive, and fast. Your originals are never touched.",
    ),
    OnboardingPage(
        icon = LumoraIcons.Healing,
        title = "One-tap AI pro retouch",
        body = "Drop a photo in, tap AI Pro Retouch, done. It reads your " +
            "photo's exposure, color cast and scene — and retouches it the " +
            "way a pro would.",
    ),
    OnboardingPage(
        icon = LumoraIcons.Brush,
        title = "Masks that follow your intent",
        body = "Brush, linear and radial masks with feather, invert and " +
            "auto-mask — darken the sky without touching the subject.",
    ),
    OnboardingPage(
        icon = LumoraIcons.Tune,
        title = "Total control, zero fear",
        body = "Light, color, detail, geometry, lens blur and $presetCount hand-tuned " +
            "presets. Every edit is undoable, and export is full resolution.",
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val colors = Lumora.colors
    val type = Lumora.type
    // Built once per composition: the preset count comes from the real
    // built-in list so the tour copy can never go stale.
    val pages = remember { onboardingPages(Presets.builtIn().size) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == pages.size - 1

    Column(
        modifier = Modifier.fillMaxSize().background(colors.background)
            .semantics {
                contentDescription =
                    "Welcome tour, page ${pagerState.currentPage + 1} of ${pages.size}"
            },
    ) {
        // Skip — always available, never traps the user.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) {
                Text("Skip", color = colors.textSecondary, style = type.controlLabel)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) { index ->
            val page = pages[index]
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(112.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    page.title,
                    style = type.screenTitle,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    page.body,
                    style = type.body,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Dots + primary action.
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pages.size) { i ->
                    val selected = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .then(
                                if (selected) Modifier.width(24.dp).height(8.dp)
                                else Modifier.size(8.dp)
                            )
                            .clip(if (selected) RoundedCornerShape(4.dp) else CircleShape)
                            .background(
                                if (selected) colors.accent
                                else colors.textTertiary.copy(alpha = 0.4f)
                            ),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    if (lastPage) {
                        onDone()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(
                    if (lastPage) "Get started" else "Next",
                    style = type.controlLabel,
                )
            }
        }
    }
}
