package com.my.knowledge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.my.knowledge.R
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.viewmodel.ProcessingStatusViewModel
import androidx.compose.material3.MaterialTheme
import com.my.knowledge.ui.theme.LocalPalette
import com.my.knowledge.ui.theme.LocalSpacing

@Composable
fun ReviewQueueScreen(
    viewModel: ProcessingStatusViewModel,
    onBack: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    val reviews by viewModel.pendingReviews.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bgPage)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp, bottom = 12.dp)
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.brand)
                Spacer(modifier = Modifier.size(4.dp))
                Text(stringResource(R.string.auto_11d02415), fontSize = 14.sp, color = palette.brand)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Review Queue", style = MaterialTheme.typography.displayLarge, color = palette.textPrimary)
            Text(stringResource(R.string.auto_1191b28d), style = MaterialTheme.typography.labelLarge, color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (reviews.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(spacing.md), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auto_405fb51f), modifier = Modifier.padding(28.dp), color = palette.textSecondary, fontSize = 14.sp)
                    }
                }
            } else {
                items(reviews) { review ->
                    ReviewQueueCard(
                        review = review,
                        onAccept = { viewModel.acceptReview(review.id) },
                        onSkip = { viewModel.skipReview(review.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewQueueCard(
    review: ReviewItemEntity,
    onAccept: () -> Unit,
    onSkip: () -> Unit
) {

    val palette = LocalPalette.current

    val spacing = LocalSpacing.current
    Surface(shape = RoundedCornerShape(spacing.md), color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RateReview, contentDescription = null, tint = palette.semanticWarning, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(review.title, style = MaterialTheme.typography.titleMedium, color = palette.textPrimary)
                    Text(review.type, style = MaterialTheme.typography.labelSmall, color = palette.semanticWarning, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(review.description, style = MaterialTheme.typography.labelLarge, lineHeight = 20.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 10.dp))
            if (review.payloadJson.isNotBlank()) {
                Surface(color = palette.bgPage, shape = RoundedCornerShape(spacing.sm), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(review.payloadJson, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary, modifier = Modifier.padding(10.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.auto_31a98593))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = palette.brand)) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.auto_b56d9ac6))
                }
            }
        }
    }
}
