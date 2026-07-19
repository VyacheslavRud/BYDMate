package com.bydmate.app.ui.settings

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** Keeps the reusable settings field's keyboard hint and visual secrecy in sync. */
internal fun settingsFieldVisualTransformation(keyboardType: KeyboardType): VisualTransformation =
    when (keyboardType) {
        KeyboardType.Password, KeyboardType.NumberPassword -> PasswordVisualTransformation()
        else -> VisualTransformation.None
    }
