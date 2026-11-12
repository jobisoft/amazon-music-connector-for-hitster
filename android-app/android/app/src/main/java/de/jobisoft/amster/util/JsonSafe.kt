package de.jobisoft.amster.util

/** `org.json` refuses NaN and infinite numbers with an exception, a debug log must never crash the app because of one. */
object JsonSafe {
    /** The number if it can go into JSON, otherwise its name as text ("NaN", "Infinity"). */
    fun number(value: Number): Any {
        val d = value.toDouble()
        return if (d.isNaN() || d.isInfinite()) d.toString() else value
    }
}
