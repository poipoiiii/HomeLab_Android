package com.poi.homelab.feature.bills.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.theme.SuccessStrong
import com.poi.homelab.core.util.shortDate
import com.poi.homelab.core.util.signedMoney
import com.poi.homelab.core.util.typeLabel
import com.poi.homelab.feature.bills.BillTransaction

@Composable
fun TransactionRow(txn: BillTransaction, modifier: Modifier = Modifier) {
    val subtitle = buildString {
        append(txn.category.ifBlank { typeLabel(txn.type) })
        if (txn.account.isNotBlank()) append(" · ").append(txn.account)
        val whenText = shortDate(txn.date, txn.time)
        if (whenText.isNotBlank()) append(" · ").append(whenText)
    }
    val amountColor = when (txn.type) {
        "income" -> SuccessStrong
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = txn.merchant.ifBlank { txn.description.ifBlank { "未命名交易" } },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = signedMoney(txn.amount, txn.type),
            style = MaterialTheme.typography.titleMedium,
            color = amountColor,
        )
    }
}

@Composable
fun darkAmountColor(type: String): Color = when (type) {
    "income" -> Color(0xFF8FD9A3)
    else -> Color(0xFFFAF9F5)
}
