package com.my.knowledge.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.my.knowledge.R
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

/**
 * The 4 UI states a Screen can be in. Forces every screen to declare all
 * four explicitly instead of scattering `if (loading) ...` / `if (data.isEmpty()) ...`
 * / `if (error) ...` checks across the composable.
 *
 * @param T the data shape; usually the screen's ViewState / UiModel.
 *          When T is `Unit` the screen has no payload (e.g. action-only
 *          screens) and callers can use `ScreenState.Content(Unit)`.
 */
sealed interface ScreenState<out T> {
    data object Loading : ScreenState<Nothing>

    /**
     * @param icon   optional leading icon. Defaults to [Icons.Default.Inbox] in
     *               the renderer when null. Pick something semantic to the
     *               surface (no items, no search hits, no reviews, …).
     * @param actionLabel / onAction optional primary CTA. Most empty states
     *                     benefit from a single obvious next step ("导入一份
     *                     知识" / "新建对话"); keep it short.
     */
    data class Empty(
        val title: String,
        val desc: String? = null,
        val icon: ImageVector? = null,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    ) : ScreenState<Nothing>

    /**
     * @param title   short, human-readable summary ("加载失败" / "网络异常"). The
     *                renderer applies a default when not supplied.
     * @param message the actual reason — usually an exception message or
     *                server-supplied code. Shown as smaller body text below
     *                the title; can be raw without further formatting.
     */
    data class Error(
        val title: String = "加载失败",
        val message: String,
    ) : ScreenState<Nothing>

    data class Content<T>(val data: T) : ScreenState<T>
}

/**
 * Centralized state-aware layout. Wraps a screen's body and renders one of
 * Loading / Empty / Error / Content based on the supplied [state].
 *
 *   - Loading → centered spinner.
 *   - Empty   → [EmptyState] with title, desc, icon, optional action.
 *   - Error   → [ErrorState] with retry + optional contact-support.
 *   - Content → calls [content] with the payload.
 *
 * `onRetry` is provided at the StateContainer level (not inside the Error
 * variant) because retry is almost always "re-fetch whatever data the
 * screen is showing" — coupling it to the data layer's viewmodel rather
 * than each Error instance.
 */
@Composable
fun <T> StateContainer(
    state: ScreenState<T>,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onContactSupport: (() -> Unit)? = null,
    emptyIcon: ImageVector = Icons.Default.Inbox,
    errorIcon: ImageVector = Icons.Default.ErrorOutline,
    content: @Composable (T) -> Unit
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            ScreenState.Loading -> CircularProgressIndicator(color = LocalPalette.current.brand)
            is ScreenState.Empty -> EmptyState(
                title = state.title,
                desc = state.desc,
                icon = state.icon ?: emptyIcon,
                actionLabel = state.actionLabel,
                onAction = state.onAction
            )
            is ScreenState.Error -> ErrorState(
                title = state.title,
                message = state.message,
                icon = errorIcon,
                onRetry = onRetry,
                onContactSupport = onContactSupport
            )
            is ScreenState.Content -> content(state.data)
        }
    }
}

/**
 * Empty-state surface. Big circular icon → title → optional description →
 * optional single CTA button. Use inside [StateContainer] or standalone for
 * sections that need their own contextual empty hint (e.g. a "no results"
 * block in the middle of a populated screen).
 *
 * Style choices:
 * - 64dp circular icon background tinted with `brandSubtle` so empty
 *   states feel like a calm, branded prompt rather than a bug.
 * - Title in `titleMedium` / textPrimary; description in `bodyMedium` /
 *   textSecondary for a clear hierarchy.
 * - Single primary button. If you need two CTAs, use a `Row` of two
 *   [TextButton]s instead of stacking buttons.
 */
@Composable
fun EmptyState(
    title: String,
    desc: String? = null,
    icon: ImageVector = Icons.Default.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(horizontal = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(palette.brandSubtle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.brand,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center
        )
        if (desc != null) {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(spacing.lg))
            Button(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Error-state surface. Same visual rhythm as [EmptyState] but tinted with
 * the error palette and with a "retry" CTA front-and-center. The contact-
 * support button is optional; only show it when the app actually has a
 * feedback path (otherwise leave null to suppress the row entirely).
 */
@Composable
fun ErrorState(
    message: String,
    title: String = "加载失败",
    icon: ImageVector = Icons.Default.ErrorOutline,
    onRetry: () -> Unit = {},
    onContactSupport: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(horizontal = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(palette.semanticErrorBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.semanticError,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        Button(onClick = onRetry) {
            Text(
                text = stringResource(R.string.auto_e2d53a6d),
                style = MaterialTheme.typography.labelLarge
            )
        }
        if (onContactSupport != null) {
            Spacer(modifier = Modifier.height(spacing.xs))
            TextButton(onClick = onContactSupport) {
                Text(
                    text = "联系支持",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.textSecondary
                )
            }
        }
    }
}
