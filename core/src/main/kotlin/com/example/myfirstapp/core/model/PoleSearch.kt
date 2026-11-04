package com.example.myfirstapp.core.model

/**
 * 지도 검색(10.5.7, Pilot 2 지도 검색 + 전주번호·선로번호): 앱 DB에 있는 전주만 찾는다(외부 검색 없음).
 * 전주번호(전산화번호)·선로번호·"선로명 선로번호"를 보고, 공백과 대소문자는 무시한다.
 * 맞는 정도 순: 같음 → 앞부분이 같음 → 어딘가 들어 있음. 같은 정도면 받은 순서를 지킨다.
 */
object PoleSearch {
    fun search(poles: List<Pole>, query: String, limit: Int): List<Pole> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        return poles.mapNotNull { p -> rank(p, q)?.let { p to it } }.sortedBy { it.second }.take(limit).map { it.first }
    }

    private fun rank(p: Pole, q: String): Int? =
        listOfNotNull(p.poleId, p.lineNo, p.displayName).map(::norm).mapNotNull { f ->
            when {
                f == q -> 0
                f.startsWith(q) -> 1
                q in f -> 2
                else -> null
            }
        }.minOrNull()

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}
