package com.badmintontracker.android.invites

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.invites.formatInviteCode
import com.badmintontracker.shared.invites.qrPayloadForCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.datetime.Instant

/**
 * Bottom sheet presenting a single-use invite code, scannable QR code, copy,
 * native share sheet, regenerate, cancel, and optional phone-only matches warning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerInviteSheet(
    playerName: String,
    code: String?,
    expiresAt: Instant?,
    phoneOnlyMatchCount: Int,
    loading: Boolean,
    onCopyCode: () -> Unit,
    onGenerateNewCode: () -> Unit,
    onCancelInvite: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val formattedCode = remember(code) { code?.let(::formatInviteCode) }
    val qrBitmap = remember(code) {
        code?.let { buildQrBitmap(qrPayloadForCode(it), sizePx = 512) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = PlayerInviteCopy.inviteSheetTitle(playerName),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = PlayerInviteCopy.inviteSheetSubtitle(playerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            if (loading && formattedCode == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.5.dp)
                }
            } else if (formattedCode != null) {
                val codeShape = RoundedCornerShape(ShuttlRadius.medium)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(codeShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant, codeShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, codeShape)
                        .clickable(role = Role.Button) {
                            copyInviteCodeToClipboard(context, formattedCode)
                            onCopyCode()
                        }
                        .padding(vertical = 16.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formattedCode,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = PlayerInviteCopy.TAP_TO_COPY,
                        style = MaterialTheme.typography.labelSmall,
                        color = ShuttlTheme.extended.accentDark,
                    )
                }

                if (qrBitmap != null) {
                    Spacer(Modifier.height(16.dp))
                    // Always white card with quiet zone so the QR scans reliably in dark theme too.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(ShuttlRadius.medium))
                            .background(Color.White)
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "QR code for $formattedCode",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.size(188.dp),
                        )
                    }
                }

                if (expiresAt != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = PlayerInviteCopy.expiryCaption(expiresAt, System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (phoneOnlyMatchCount > 0) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ShuttlRadius.small))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = PlayerInviteCopy.phoneOnlyMatchesNote(playerName, phoneOnlyMatchCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                ShuttlButton(
                    text = PlayerInviteCopy.SHARE_INVITE,
                    onClick = { shareInvite(context, playerName, code.orEmpty()) },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                ShuttlButton(
                    text = PlayerInviteCopy.GENERATE_NEW_CODE,
                    onClick = onGenerateNewCode,
                    variant = ShuttlButtonVariant.Secondary,
                    loading = loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = onCancelInvite, enabled = !loading) {
                        Text(
                            text = PlayerInviteCopy.ACCESS_CANCEL_INVITE,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun copyInviteCodeToClipboard(context: Context, formattedCode: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Shuttl invite code", formattedCode))
}

private fun shareInvite(context: Context, playerName: String, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, PlayerInviteCopy.SHARE_SUBJECT)
        putExtra(Intent.EXTRA_TEXT, PlayerInviteCopy.shareInviteText(playerName, code))
    }
    context.startActivity(Intent.createChooser(send, PlayerInviteCopy.SHARE_INVITE))
}

internal fun buildQrBitmap(payload: String, sizePx: Int): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
