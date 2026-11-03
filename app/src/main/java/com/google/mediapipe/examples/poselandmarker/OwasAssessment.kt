package com.google.mediapipe.examples.poselandmarker

/** Categorias para uma observação, conforme a tabela 6 de Ergonautas/UPV. */
object OwasAssessment {
    private val table = arrayOf(
        "111111111222222111111", "111111111222222111111", "111111111223223111112",
        "223223223333333222233", "223223233344344334234", "334223333344444444234",
        "111111111233344411111", "223111112444444333111", "223111233444444444111",
        "233223223444444444234", "334234334444444444234", "444234334444444444234"
    )

    fun calculate(back: Int, arms: Int, legs: Int, load: Int): ErgonomicScore {
        require(back in 1..4 && arms in 1..3 && legs in 1..7 && load in 1..3)
        val category = table[(back - 1) * 3 + arms - 1][(legs - 1) * 3 + load - 1].digitToInt()
        return when (category) {
            1 -> ErgonomicScore(1, "Baixo", "Sem ação corretiva imediata")
            2 -> ErgonomicScore(2, "Moderado", "Corrigir em futuro próximo")
            3 -> ErgonomicScore(3, "Alto", "Corrigir o mais breve possível")
            else -> ErgonomicScore(4, "Muito alto", "Corrigir imediatamente")
        }
    }
}
