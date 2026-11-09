package de.jobisoft.hitsterconnector.util

/** CSV lines the way the app writes them: a field in double quotes when it holds a comma, a quote or a line break. */
object CsvLines {
    /** One CSV line to its fields. Fields in double quotes may hold commas, "" is a quote. */
    fun parse(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { fields.add(current.toString()); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    /** A field for a CSV line, in quotes if it needs them. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun line(fields: List<String>): String = fields.joinToString(",") { field(it) }
}
