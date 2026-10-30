package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.Pole

/**
 * 배전 선로번호로 계통 순서를 읽는다(4.5.6). `113R10L1` = 본선 113 → 오른쪽 분기 10번째 → 왼쪽 분기 1번째.
 * 번호가 작을수록 전원(변전소) 쪽이다. 앞(전원측) 전주는 같은 분기의 n−1, 분기의 1번이면 분기가 시작된 전주다.
 */
object LineNumbers {

    /** 번호 이웃이 이보다 멀면 실제 이웃이 아니라고 본다(대평선 본선 이웃 7~89m). */
    const val MAX_NEIGHBOR_M = 150.0

    /** 4방향 사선 지점 이름(5.6): 전원측 → 부하측을 바라볼 때 기준. 번호는 `CAPTURE.viewpoint`. */
    val SIDE_NAMES = listOf("부하측 우", "전원측 우", "전원측 좌", "부하측 좌")

    private val SEGMENT = Regex("""([A-Z]?)(\d+)""")

    /** 전원측 이웃 번호. 없으면 null(본선 1번 등). */
    fun previous(lineNo: String): String? {
        val segs = SEGMENT.findAll(lineNo.trim().uppercase()).toList()
        if (segs.isEmpty() || segs.joinToString("") { it.value } != lineNo.trim().uppercase()) return null
        val last = segs.last()
        val n = last.groupValues[2].toInt()
        val head = segs.dropLast(1).joinToString("") { it.value }
        return when {
            n > 1 -> head + last.groupValues[1] + (n - 1)
            segs.size > 1 -> head
            else -> null
        }
    }

    /** 부하측 이웃 번호(같은 분기의 n+1). */
    fun next(lineNo: String): String? {
        val segs = SEGMENT.findAll(lineNo.trim().uppercase()).toList()
        if (segs.isEmpty()) return null
        val last = segs.last()
        return segs.dropLast(1).joinToString("") { it.value } + last.groupValues[1] + (last.groupValues[2].toInt() + 1)
    }

    /**
     * 전주마다 전원측 → 부하측 선로 방위(정북 0°). 번호 이웃이 없거나 [MAX_NEIGHBOR_M]보다 멀면 넣지 않는다.
     * 같은 선로(선로명)끼리만 잇는다.
     */
    fun bearings(all: List<Pole>): Map<String, Double> {
        val byNo = all.filter { it.lineNo != null }.associateBy { (it.lineName.orEmpty()) to it.lineNo!!.trim().uppercase() }
        fun near(p: Pole, no: String?) = no?.let { byNo[p.lineName.orEmpty() to it] }
            ?.takeIf { it.poleId != p.poleId && LocalProjection.distanceM(p.position, it.position) <= MAX_NEIGHBOR_M }
        return all.mapNotNull { p ->
            val no = p.lineNo ?: return@mapNotNull null
            val prev = near(p, previous(no))
            val next = near(p, next(no))
            val from = prev?.position ?: p.position
            val to = next?.position ?: p.position
            if (from == to) null else p.poleId to Viewpoints.bearing(from, to)
        }.toMap()
    }

    /** [targets]에 [all](프로젝트 전체 전주)로 구한 선로 방위를 넣는다. */
    fun withBearings(targets: List<Pole>, all: List<Pole>): List<Pole> {
        val b = bearings(all)
        return targets.map { it.copy(lineBearingDeg = b[it.poleId]) }
    }
}
