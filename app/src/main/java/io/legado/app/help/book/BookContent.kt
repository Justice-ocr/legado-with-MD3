package io.legado.app.help.book

import io.legado.app.data.entities.BookContentProcess
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.domain.model.BookContentProcessEngine

data class BookContent(
    val sameTitleRemoved: Boolean,
    val textList: List<String>,
    //起效的替换规则
    val effectiveReplaceRules: List<ReplaceRule>?,
    val effectiveContentProcesses: List<BookContentProcess> = emptyList(),
    val appliedContentProcessRanges: List<BookContentProcessEngine.AppliedProcessRange> = emptyList(),
) {

    override fun toString(): String {
        return textList.joinToString("\n")
    }

}
