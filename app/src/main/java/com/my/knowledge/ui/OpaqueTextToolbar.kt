package com.my.knowledge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

/**
 * 系统默认 TextToolbar (在 Android 上由 FloatingToolbar 实现) 的背景在部分
 * 设备/主题下会半透明, 在浅色页面上和正文字叠在一起看不清楚. 这里自己渲染一个
 * 不透明 Surface + 文字按钮 的菜单, 通过 LocalTextToolbar 注入到 SelectionContainer.
 */
class OpaqueTextToolbarState : TextToolbar {
    var rect by mutableStateOf(Rect.Zero)
        private set
    var onCopy by mutableStateOf<(() -> Unit)?>(null)
        private set
    var onCut by mutableStateOf<(() -> Unit)?>(null)
        private set
    var onPaste by mutableStateOf<(() -> Unit)?>(null)
        private set
    var onSelectAll by mutableStateOf<(() -> Unit)?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    override val status: TextToolbarStatus
        get() = if (visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.rect = rect
        this.onCopy = onCopyRequested
        this.onCut = onCutRequested
        this.onPaste = onPasteRequested
        this.onSelectAll = onSelectAllRequested
        this.visible = true
    }

    override fun hide() {
        visible = false
    }

    fun fireAndDismiss(action: (() -> Unit)?) {
        action?.invoke()
        hide()
    }
}

@Composable
fun OpaqueTextToolbarRender(state: OpaqueTextToolbarState) {
    if (!state.visible) return
    Popup(
        offset = IntOffset(
            x = state.rect.left.toInt().coerceAtLeast(0),
            y = (state.rect.top - 96).toInt().coerceAtLeast(0)
        )
    ) {
        Surface(
            color = Color.White,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.shadow(8.dp, RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (state.onSelectAll != null) {
                    TextButton(onClick = { state.fireAndDismiss(state.onSelectAll) }) {
                        Text("全选", color = Color(0xFF1E293B))
                    }
                }
                if (state.onCopy != null) {
                    TextButton(onClick = { state.fireAndDismiss(state.onCopy) }) {
                        Text("复制", color = Color(0xFF1E293B))
                    }
                }
                if (state.onCut != null) {
                    TextButton(onClick = { state.fireAndDismiss(state.onCut) }) {
                        Text("剪切", color = Color(0xFF1E293B))
                    }
                }
                if (state.onPaste != null) {
                    TextButton(onClick = { state.fireAndDismiss(state.onPaste) }) {
                        Text("粘贴", color = Color(0xFF1E293B))
                    }
                }
            }
        }
    }
}
