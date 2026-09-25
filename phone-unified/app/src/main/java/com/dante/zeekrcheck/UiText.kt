package com.dante.zeekrcheck

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrcheck.core.PresentationStrings

internal fun ui(value: String): String = PresentationStrings.render(value, PhoneLanguage.locale.language == "zh")
internal fun android.widget.RemoteViews.setUiText(id: Int, value: CharSequence?) = setTextViewText(id, value?.toString()?.let(::ui))
internal fun android.widget.RemoteViews.setUiDescription(id: Int, value: String) = setContentDescription(id, ui(value))

/** Explicit display boundary; raw user-entered names, addresses and notes bypass translation. */
@Composable internal fun UiText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current, raw: Boolean = false) {
    androidx.compose.material3.Text(if (raw) text else ui(text), modifier, color, fontSize, fontStyle, fontWeight,
        fontFamily, letterSpacing, textDecoration, textAlign, lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style)
}
