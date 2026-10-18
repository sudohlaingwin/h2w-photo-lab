package com.lumora.photoeditor

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumora.photoeditor.ui.navigation.LumoraNav
import com.lumora.photoeditor.ui.theme.LumoraTheme

class MainActivity : ComponentActivity() {
    /** Photos shared into the app via ACTION_SEND; consumed once by the nav layer. */
    private var pendingSharedUris by mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The manifest advertises ACTION_SEND for image/* — actually honor it:
        // a photo shared from the gallery/camera rolls straight into the library
        // and opens in the editor instead of being silently dropped.
        pendingSharedUris = intent.sharedImageUris().takeIf { it.isNotEmpty() }
        val container = (application as LumoraApp).container
        setContent {
            val themeMode by container.settings.themeMode.collectAsState()
            val dark = when (themeMode) {
                1 -> true
                2 -> false
                else -> isSystemInDarkTheme()
            }
            LumoraTheme(darkTheme = dark) {
                LumoraNav(
                    container = container,
                    sharedUris = pendingSharedUris,
                    onSharedConsumed = { pendingSharedUris = null },
                )
            }
        }
    }

    /** Extracts shared image URIs from an ACTION_SEND(/MULTIPLE) intent, empty if none. */
    private fun Intent.sharedImageUris(): List<Uri> {
        val mime = type ?: return emptyList()
        if (!mime.startsWith("image/")) return emptyList()
        return when (action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                listOfNotNull(uri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                }
            }
            else -> emptyList()
        }
    }
}
