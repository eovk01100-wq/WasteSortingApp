package com.jiwoo.wastesorting

/** 인터넷 없이 보여 주는 7개 분류 기본 배출법 (환경부 일반 기준) */
object OfflineGuide {

    private const val NOTE = "인터넷 없이 보여 주는 기본 안내예요. 지역에 따라 다를 수 있어요."

    private val guides = mapOf(
        "can" to DisposalResponse(
            itemName = "캔",
            category = "캔류",
            steps = listOf(
                "내용물을 비우고 물로 헹궈요.",
                "겉의 비닐이나 플라스틱 뚜껑 등 다른 재질은 떼어 내요.",
                "가능하면 납작하게 눌러 캔류로 배출해요."
            ),
            caution = "부탄가스·스프레이 캔은 남은 가스를 완전히 뺀 뒤 배출해요.",
            note = NOTE
        ),
        "glass" to DisposalResponse(
            itemName = "유리병",
            category = "유리병",
            steps = listOf(
                "내용물을 비우고 물로 헹궈요.",
                "뚜껑은 재질(금속·플라스틱)에 맞게 따로 분리해요.",
                "깨지지 않게 유리병으로 배출해요."
            ),
            caution = "깨진 유리, 거울, 도자기, 내열 유리는 유리병이 아니에요. 신문지로 싸서 종량제 봉투(또는 불연성 마대)에 버려요. 빈용기보증금 표시가 있는 병은 판매처에 반환할 수 있어요.",
            note = NOTE
        ),
        "pack" to DisposalResponse(
            itemName = "종이팩 (우유팩 등)",
            category = "종이팩",
            steps = listOf(
                "내용물을 비우고 물로 헹궈요.",
                "빨대나 비닐 등 다른 재질을 떼어 내요.",
                "펼쳐서 말린 뒤 종이팩 전용 수거함에 넣어요."
            ),
            caution = "전용 수거함이 없으면 일반 종이와 섞지 말고 따로 묶어서 배출해요. 멸균팩도 종이팩이에요.",
            note = NOTE
        ),
        "paper" to DisposalResponse(
            itemName = "종이",
            category = "종이류",
            steps = listOf(
                "테이프, 스테이플러 심, 스프링 등을 제거해요.",
                "상자는 펼쳐서 납작하게 만들어요.",
                "물에 젖지 않게 묶어서 종이류로 배출해요."
            ),
            caution = "음식물이 묻거나 젖은 종이, 영수증(감열지), 코팅된 종이는 종량제 봉투에 버려요.",
            note = NOTE
        ),
        "pet" to DisposalResponse(
            itemName = "페트병",
            category = "투명 페트병",
            steps = listOf(
                "내용물을 비우고 물로 헹궈요.",
                "겉의 라벨(비닐)을 떼어 내요.",
                "납작하게 눌러 뚜껑을 닫고 투명 페트병 전용 수거함에 넣어요."
            ),
            caution = "색이 있는 페트병은 투명 페트병이 아니라 플라스틱류로 배출해요.",
            note = NOTE
        ),
        "plastic" to DisposalResponse(
            itemName = "플라스틱",
            category = "플라스틱류",
            steps = listOf(
                "내용물을 비우고 물로 헹궈요.",
                "붙어 있는 다른 재질(스티커, 금속 등)을 떼어 내요.",
                "플라스틱류로 배출해요."
            ),
            caution = "기름 등 오염이 심해 씻기지 않거나, 여러 재질이 섞인 물건(칫솔, 장난감 등)은 종량제 봉투에 버려요.",
            note = NOTE
        ),
        "vinyl" to DisposalResponse(
            itemName = "비닐",
            category = "비닐류",
            steps = listOf(
                "음식물 등 이물질을 털어 내거나 헹궈요.",
                "흩날리지 않게 한 봉투에 모아요.",
                "비닐류로 배출해요."
            ),
            caution = "이물질이 씻기지 않을 만큼 오염된 비닐은 종량제 봉투에 버려요.",
            note = NOTE
        )
    )
    // 바로 기본 안내를 보여도 되는 "구체적인 품목" 이름만
    private val exactWords = mapOf(
        "can" to listOf("캔", "음료캔", "알루미늄캔", "철캔", "맥주캔", "참치캔", "통조림캔"),
        "glass" to listOf("유리병", "소주병", "맥주병", "음료병", "음료수병", "잼병"),
        "pack" to listOf("종이팩", "우유팩", "멸균팩", "두유팩", "주스팩"),
        "paper" to listOf("신문", "신문지", "종이상자", "종이박스", "택배상자", "골판지", "공책", "책"),
        "pet" to listOf("페트병", "생수병", "투명페트병", "음료페트병"),
        "plastic" to listOf("플라스틱용기", "배달용기"),
        "vinyl" to listOf("비닐봉지", "비닐봉투", "과자봉지", "라면봉지")
    )

    // 인터넷이 없을 때만 쓰는 "가까운 분류" 힌트 (넓은 말 포함)
    private val hintWords = mapOf(
        "can" to listOf("캔", "통조림"),
        "glass" to listOf("유리병", "병"),
        "pack" to listOf("종이팩", "우유팩", "멸균팩", "팩"),
        "paper" to listOf("종이", "박스", "상자"),
        "pet" to listOf("페트"),
        "plastic" to listOf("플라스틱", "용기"),
        "vinyl" to listOf("비닐", "봉지", "봉투")
    )

    // 재질 이름이 들어 있어도 기본 분류와 배출법이 다른 품목 → 기본 안내로 대신하지 않음
    private val exceptionWords = listOf(
        "종이컵", "영수증", "코팅", "기저귀", "물티슈", "휴지", "벽지",
        "깨진", "유리컵", "거울", "도자기", "내열", "형광등", "전구", "세정제",
        "아이스팩", "칫솔", "스티로폼", "컵라면", "보온", "고무", "장난감",
        "약", "건전지", "배터리", "부탄", "스프레이", "가스"
    )

    private fun norm(s: String) = s.replace(" ", "").lowercase()

    private fun isException(q: String) = exceptionWords.any { q.contains(norm(it)) }

    /** 검색어가 구체적인 품목 이름과 정확히 같을 때만 분류를 돌려줌 */
    fun exactMatch(query: String): String? {
        val q = norm(query)
        if (isException(q)) return null
        return exactWords.entries.firstOrNull { (_, words) -> words.any { norm(it) == q } }?.key
    }

    /** 인터넷이 없을 때 쓸 가까운 분류. 예외 품목이면 null (추측하지 않음) */
    fun partialMatch(query: String): String? {
        val q = norm(query)
        if (isException(q)) return null
        return hintWords.flatMap { (label, words) -> words.map { label to norm(it) } }
            .filter { q.contains(it.second) }
            .maxByOrNull { it.second.length }
            ?.first
    }

    fun get(label: String): DisposalResponse =
        guides[label] ?: DisposalResponse(
            itemName = label,
            category = "확인 필요",
            steps = listOf("인터넷에 연결한 뒤 다시 눌러 주세요."),
            caution = null,
            note = NOTE
        )
}