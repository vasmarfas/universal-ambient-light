package com.vasmarfas.UniversalAmbientLight.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.QRCodeWriter
import com.vasmarfas.UniversalAmbientLight.common.util.ReviewHelper
import com.vasmarfas.UniversalAmbientLight.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Диалоги главного экрана: помощь, оценка приложения, поддержка проекта и QR со ссылкой.
 */
@Composable
fun HelpDialog(
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.help_title))
        },
        text = {
            Text(stringResource(R.string.help_message))
        },
        confirmButton = {
            TextButton(onClick = onOpenGitHub) {
                Text(stringResource(R.string.help_open_github))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.help_close))
            }
        }
    )
}

/**
 * Открывает страницу создания issue на GitHub.
 */
fun openGitHubIssues(context: Context) {
    val url = context.getString(R.string.github_issues_url)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // Запасной путь: пробуем открыть в браузере с категорией browsable
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            browserIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(browserIntent)
        } catch (e2: Exception) {
            // Ни один браузер не отозвался — открыть ссылку не получится
            Log.e("MainActivity", "Failed to open GitHub issues: ${e2.message}")
            Toast.makeText(
                context,
                "Could not open browser. Please visit GitHub manually.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/**
 * Открывает диалог оценки Google Play.
 */
fun openGooglePlayReview(context: Context) {
    if (context is Activity) {
        ReviewHelper.forceShowReview(context)
    }
}

/**
 * Диалог оценки: от одной до пяти звёзд.
 */
@Composable
fun RatingDialog(
    onDismiss: () -> Unit,
    onRatingSelected: (Int) -> Unit,
) {
    // rememberSaveable: выбранная оценка не должна сбрасываться поворотом экрана
    var selectedRating by rememberSaveable { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.rating_dialog_title))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.rating_dialog_message),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 1..5) {
                        IconButton(
                            onClick = { selectedRating = i }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = stringResource(R.string.rating_stars_desc, i),
                                tint = if (i <= selectedRating) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    // Не alpha на alpha: в тёмной теме такие звёзды исчезали
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedRating > 0) {
                        onRatingSelected(selectedRating)
                    }
                },
                enabled = selectedRating > 0
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rating_dialog_cancel))
            }
        }
    )
}

/**
 * Диалог, показываемый после низкой оценки (1–3 звезды).
 */
@Composable
fun LowRatingDialog(
    onDismiss: () -> Unit,
    onReportIssue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.rating_dialog_low_rating_title))
        },
        text = {
            Text(stringResource(R.string.rating_dialog_low_rating_message))
        },
        confirmButton = {
            TextButton(onClick = onReportIssue) {
                Text(stringResource(R.string.rating_dialog_report_issue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rating_dialog_cancel))
            }
        }
    )
}

@Composable
fun SupportDialog(
    onDismiss: () -> Unit,
    onOpenSupport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.support_project_title))
        },
        text = {
            Text(stringResource(R.string.support_project_message))
        },
        confirmButton = {
            TextButton(onClick = onOpenSupport) {
                Text(stringResource(R.string.support_open_details))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.help_close))
            }
        }
    )
}

@Composable
fun UrlDialog(
    url: String,
    onDismiss: () -> Unit,
    onOpenLink: (() -> Unit)? = null,
) {
    // Генерация — 160 тысяч пикселей; на главном потоке она подвешивала кадр открытия
    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.Default) { generateQRCode(url, 400) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.url_dialog_title))
        },
        text = {
            // Прокрутка — в ландшафте телефона QR с текстом не помещаются в диалог
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.url_dialog_message),
                    textAlign = TextAlign.Center
                )

                val qr = qrBitmap
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = stringResource(R.string.url_dialog_qr_description),
                        modifier = Modifier.size(250.dp)
                    )
                }

                SelectionContainer {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (onOpenLink != null) {
                TextButton(onClick = onOpenLink) {
                    Text(stringResource(R.string.url_dialog_open_link))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.help_close))
            }
        }
    )
}

internal fun generateQRCode(content: String, size: Int): ImageBitmap? {
    return try {
        val hints = hashMapOf<EncodeHintType, Any>().apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 1)
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val black = Color.Black.toArgb()
        val white = Color.White.toArgb()
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                pixels[rowOffset + x] = if (bitMatrix[x, y]) black else white
            }
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)

        bitmap.asImageBitmap()
    } catch (e: Exception) {
        Log.e("UrlDialog", "Failed to generate QR code", e)
        null
    }
}
