package com.bydmate.app.ui.settings

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFieldTransformationTest {

    @Test
    fun `password keyboard types use masked visual transformation`() {
        assertTrue(settingsFieldVisualTransformation(KeyboardType.Password) is PasswordVisualTransformation)
        assertTrue(settingsFieldVisualTransformation(KeyboardType.NumberPassword) is PasswordVisualTransformation)
    }

    @Test
    fun `non-password keyboard types remain visible`() {
        assertSame(VisualTransformation.None, settingsFieldVisualTransformation(KeyboardType.Text))
        assertSame(VisualTransformation.None, settingsFieldVisualTransformation(KeyboardType.Uri))
        assertSame(VisualTransformation.None, settingsFieldVisualTransformation(KeyboardType.Decimal))
    }
}
