package com.xrdavies.mob.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun parsesCommonBlocks() {
        val blocks = parseMarkdown("# Title\n\nSome **bold**\ntext.\n\n- one\n  wrapped\n2. two\n\n```kotlin\nval x = 1\n```\n> quoted\n---")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("Some **bold**\ntext."),
                MdBlock.ListItem(false, "•", 0, "one wrapped"),
                MdBlock.ListItem(true, "2.", 0, "two"),
                MdBlock.Code("kotlin", "val x = 1"),
                MdBlock.Quote("quoted"),
                MdBlock.Rule,
            ),
            blocks,
        )
    }
}
