package com.xrdavies.mob.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Question

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionDialog(
    question: Question,
    onAnswer: (String?, Boolean?) -> Unit,
    onDismiss: () -> Unit
) {
    var textAnswer by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (question.kind == "approval") "Approval" else "Question") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(question.text)
                
                if (!question.details.isNullOrEmpty()) {
                    Text(
                        question.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                when {
                    question.kind == "approval" -> {
                        // Handled by confirmButton/dismissButton
                    }
                    question.options != null && question.options.isNotEmpty() -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            question.options.forEach { option ->
                                Button(
                                    onClick = { onAnswer(option, null) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(option)
                                }
                            }
                        }
                    }
                    question.allowText == true -> {
                        OutlinedTextField(
                            value = textAnswer,
                            onValueChange = { textAnswer = it },
                            label = { Text("Your answer") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (question.kind == "approval") {
                Button(onClick = { onAnswer(null, true) }) {
                    Text("Approve")
                }
            } else if (question.allowText == true) {
                Button(
                    onClick = { onAnswer(textAnswer, null) },
                    enabled = textAnswer.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        },
        dismissButton = {
            if (question.kind == "approval") {
                TextButton(onClick = { onAnswer(null, false) }) {
                    Text("Deny")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
