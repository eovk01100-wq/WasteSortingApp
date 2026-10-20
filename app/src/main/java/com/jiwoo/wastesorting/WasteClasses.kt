package com.jiwoo.wastesorting

/** 분류 목록은 여기 한 곳에서만 관리 (assets/labels.txt, data.yaml 순서와 같아야 함) */
object WasteClasses {

    val NAMES = listOf("can", "glass", "pack", "paper", "pet", "plastic", "vinyl")
    const val OTHER = "other"

    fun indexOf(name: String?): Int = NAMES.indexOf(name)

    /** 화면에 보여 줄 짧은 한글 이름 (박스 라벨, 로딩 칩, 안내 문구) */
    private val KO = mapOf(
        "can" to "캔", "glass" to "유리병", "pack" to "종이팩", "paper" to "종이",
        "pet" to "페트병", "plastic" to "플라스틱", "vinyl" to "비닐", OTHER to "분류 밖"
    )

    /** 모르는 이름이면 받은 그대로 돌려줌 */
    fun koName(label: String): String = KO[label] ?: label

    /** Gemini가 준 분류를 허용된 값(7개 + other)으로 정리. 그 외 값은 null */
    fun normalize(raw: String?): String? {
        val v = raw?.trim()?.lowercase() ?: return null
        return if (v in NAMES || v == OTHER) v else null
    }

    /** 모델의 labels.txt와 순서가 같은지 확인 */
    fun matches(labels: List<String>): Boolean = labels == NAMES
}