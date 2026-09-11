package com.reader.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.reader.app.ui.theme.ReaderFonts
import com.reader.app.ui.theme.appColors

/**
 * The one search presentation used by Shelf and Highlights. A quiet 48dp
 * surface instead of the previous full-width outlined form: a 20dp decorative
 * icon, 16sp editable text, and a 48dp clear target that appears only when
 * there is something to clear. The field itself carries the editable
 * accessibility label; the leading icon stays decorative.
 */
@Composable
fun ReaderSearchField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  clearLabel: String,
  modifier: Modifier = Modifier,
  keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  onClear: () -> Unit = { onValueChange("") },
  focusRequester: FocusRequester? = null,
  /** Request focus once, after this field's focus node exists in the layout. */
  autoFocus: Boolean = false,
  onAutoFocused: () -> Unit = {},
  /** Optional reader-surface overrides so the field can live inside the reader. */
  surfaceColor: Color? = null,
  textColor: Color? = null,
  hintColor: Color? = null,
  cursorColor: Color? = null,
) {
  val c = appColors()
  val surface = surfaceColor ?: c.divider.copy(alpha = .4f)
  val text = textColor ?: c.text
  val hint = hintColor ?: c.secondary
  val cursor = cursorColor ?: c.link
  if (focusRequester != null && autoFocus) {
    // The focus node only exists after the layout pass, so requesting focus
    // straight from a state-change effect can throw "FocusRequester is not
    // initialized". Retry across frames instead of crashing.
    LaunchedEffect(focusRequester) {
      var focused = false
      repeat(4) {
        if (!focused && runCatching { focusRequester.requestFocus() }.isSuccess) focused = true
        if (!focused) withFrameNanos { }
      }
      onAutoFocused()
    }
  }
  Surface(color = surface, shape = RoundedCornerShape(12.dp), modifier = modifier) {
    Row(
      Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(Icons.Default.Search, contentDescription = null, tint = hint, modifier = Modifier.size(20.dp))
      Spacer(Modifier.width(10.dp))
      Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
        if (value.isEmpty()) {
          Text(
            placeholder,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReaderFonts.Ui),
            color = hint,
            maxLines = 1,
          )
        }
        BasicTextField(
          value = value,
          onValueChange = onValueChange,
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = ReaderFonts.Ui, color = text),
          cursorBrush = SolidColor(cursor),
          keyboardOptions = keyboardOptions,
          keyboardActions = keyboardActions,
          modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .fillMaxWidth()
            .semantics { contentDescription = placeholder },
        )
      }
      if (value.isNotEmpty()) {
        IconButton(onClick = onClear) {
          Icon(Icons.Default.Close, contentDescription = clearLabel, tint = hint, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}
