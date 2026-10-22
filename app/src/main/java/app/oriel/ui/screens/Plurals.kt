package app.oriel.ui.screens

object Plurals {
    /** "1 video", "12 videos": a count with its noun, singular for one. */
    fun count(n: Int, noun: String, plural: String = noun + "s"): String = "$n ${if (n == 1) noun else plural}"
}
