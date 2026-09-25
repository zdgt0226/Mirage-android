package com.mirage.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirage.android.R
import com.mirage.android.ui.theme.MirageTheme

@Composable
fun VersionSheetContent(
    versionName: String,
    versionCode: Int,
    buildTag: String,
    buildTime: String,
    isBuiltinCore: Boolean,
    customCoreName: String,
    nativeVer: String,
    onManageCore: () -> Unit,
    onDismiss: () -> Unit
) {
    val coreDesc = if (isBuiltinCore) {
        stringResource(R.string.core_builtin)
    } else {
        stringResource(R.string.core_custom, customCoreName)
    }
    val info = stringResource(
        R.string.version_info,
        versionName,
        versionCode.toString(),
        buildTag,
        buildTime,
        coreDesc,
        nativeVer
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.version_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = info,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.close))
                }
                Button(
                    onClick = onManageCore,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.manage_core))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VersionSheetPreview() {
    MirageTheme {
        VersionSheetContent(
            versionName = "0.3.0",
            versionCode = 201,
            buildTag = "Release 7",
            buildTime = "2026.09.24",
            isBuiltinCore = true,
            customCoreName = "",
            nativeVer = "0.3.0",
            onManageCore = {},
            onDismiss = {}
        )
    }
}
