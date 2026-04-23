package com.faultory.editor.ui.inspector

import com.faultory.editor.validation.Severity
import com.faultory.editor.validation.ValidationIssue
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable

class IssuePanel {
    val actor: VisTable = VisTable().apply { top().left() }

    fun show(issues: List<ValidationIssue>) {
        actor.clear()
        if (issues.isEmpty()) return
        for (issue in issues) {
            val prefix = when (issue.severity) {
                Severity.ERROR -> "ERROR"
                Severity.WARNING -> "WARN"
                Severity.INFO -> "INFO"
            }
            val field = issue.fieldName?.let { "$it: " } ?: ""
            actor.add(VisLabel("[$prefix] $field${issue.message}")).left().pad(2f).row()
        }
    }

    fun clear() {
        actor.clear()
    }
}
