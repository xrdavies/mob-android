package com.xrdavies.mob.data

/** The block structure of an agent reply: enough Markdown for chat (no tables, no HTML). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val marker: String, val depth: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").split("\n")
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flush()
                val fence = trimmed.take(3)
                val lang = trimmed.drop(3).trim()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) code += lines[i++]
                out += MdBlock.Code(lang, code.joinToString("\n"))
            }
            line.isBlank() -> flush()
            RULE.matches(line) -> {
                flush()
                out += MdBlock.Rule
            }
            HEADING.matches(trimmed) -> {
                flush()
                val m = HEADING.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' '))
            }
            BULLET.matches(line) -> {
                flush()
                val m = BULLET.find(line)!!
                out += MdBlock.ListItem(false, "•", m.groupValues[1].length / 2, m.groupValues[2])
            }
            ORDERED.matches(line) -> {
                flush()
                val m = ORDERED.find(line)!!
                out += MdBlock.ListItem(true, m.groupValues[2] + ".", m.groupValues[1].length / 2, m.groupValues[3])
            }
            trimmed.startsWith(">") -> {
                flush()
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) quote += lines[i++].trimStart().removePrefix(">").trimStart()
                out += MdBlock.Quote(quote.joinToString("\n"))
                continue
            }
            else -> {
                // A wrapped list item continues on an indented line.
                val last = out.lastOrNull()
                if (para.isEmpty() && last is MdBlock.ListItem && line.startsWith("  ")) {
                    out[out.size - 1] = last.copy(text = last.text + " " + trimmed)
                } else {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line)
                }
            }
        }
        i++
    }
    flush()
    return out
}
