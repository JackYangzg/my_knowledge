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

@Composable
fun ReviewQueueScreen(
    viewModel: ProcessingStatusViewModel,
    onBack: () -> Unit
) {
    val reviews by viewModel.pendingReviews.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7FBFF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp, bottom = 12.dp)
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF147EC5))
                Spacer(modifier = Modifier.size(4.dp))
                Text(stringResource(R.string.auto_11d02415), fontSize = 14.sp, color = Color(0xFF147EC5))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Review Queue", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Text(stringResource(R.string.auto_1191b28d), fontSize = 13.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (reviews.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auto_405fb51f), modifier = Modifier.padding(28.dp), color = Color(0xFF5F87A3), fontSize = 14.sp)
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
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.RateReview, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(review.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Text(review.type, fontSize = 11.sp, color = Color(0xFFEA580C), modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(review.description, fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(top = 10.dp))
            if (review.payloadJson.isNotBlank()) {
                Surface(color = Color(0xFFF7FBFF), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(review.payloadJson, fontSize = 11.sp, color = Color(0xFF5F87A3), modifier = Modifier.padding(10.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSkip) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.auto_31a98593))
                }
                Spacer(modifier = Modifier.size(8.dp))
                Button(onClick = onAccept, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF147EC5))) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(stringResource(R.string.auto_b56d9ac6))
                }
            }
        }
    }
}
