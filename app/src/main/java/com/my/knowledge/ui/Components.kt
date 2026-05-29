package com.my.knowledge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PageHeader(
    title: String,
    hint: String? = null,
    action: @Composable (() -> Unit)? = null,
    back: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                back?.invoke()
                Text(
                    text = title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0F172A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                hint?.let {
                    Text(
                        text = it,
                        fontSize = 13.sp,
                        color = Color(0xFF5F87A3),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            action?.invoke()
        }
    }
}

@Composable
fun QuietCell(
    icon: ImageVector? = null,
    title: String,
    desc: String? = null,
    leftContent: @Composable (() -> Unit)? = null,
    right: @Composable (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        color = Color.White,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leftContent != null) {
                leftContent()
            } else if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFEFF7FF))
                        .border(1.dp, Color(0xFFCBE8FF), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFF147EC5)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF0F172A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    right?.invoke()
                }
                desc?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = Color(0xFF5F87A3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFD4D4D4)
            )
        }
    }
}

@Composable
fun Section(
    title: String,
    more: String? = null,
    onMoreClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(top = 22.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF5F87A3)
            )
            more?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = Color(0xFF6AA8D0),
                    modifier = Modifier.clickable { onMoreClick() }
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .border(0.5.dp, Color(0xFFDBEEFF))
        ) {
            content()
        }
    }
}

@Composable
fun SoftTag(text: String) {
    Surface(
        color = Color(0xFFEFF7FF),
        shape = CircleShape,
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = Color(0xFF147EC5),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun MiniTag(text: String) {
    Surface(
        color = Color(0xFFEFF7FF),
        border = BorderStroke(1.dp, Color(0xFFDBEEFF)),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = Color(0xFF147EC5),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
