package com.keiranhaas.bluetoothwalkie.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val TerminalShape =
    CutCornerShape(4.dp)

private val ButtonPadding =
    PaddingValues(
        horizontal = 12.dp,
        vertical = 10.dp
    )

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {

    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TerminalShape,
        border =
            BorderStroke(
                1.dp,
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            ),
        contentPadding = ButtonPadding,
        content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {

    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TerminalShape,
        border =
            BorderStroke(
                1.dp,
                if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                }
            ),
        contentPadding = ButtonPadding,
        content = content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {

    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TerminalShape,
        contentPadding = ButtonPadding,
        content = content
    )
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {

    androidx.compose.material3.Card(
        modifier = modifier,
        shape = TerminalShape,
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline
            ),
        content = content
    )
}

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation =
        VisualTransformation.None,
    label: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null
) {

    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        label = label,
        supportingText = supportingText,
        shape = TerminalShape
    )
}