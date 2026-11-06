package com.example.myfirstapp.core.model

/**
 * 경로에 넣을 전주 정하기(4.5.8): 전압 대상(고압·고저압·저압·미상)과 손 선택(F-06).
 * 손 선택 [manual]은 전주번호 → 참(손으로 넣음)·거짓(손으로 뺌)이고, 전압 대상보다 먼저다.
 */
object PoleTargets {
    /** 모든 전압. null은 미상(값 없음·지선주 등, [PoleCodes.voltageOf]가 null). 옛 미션·설정(필드 없음)의 값이다. */
    val ALL_VOLTAGES: Set<Voltage?> = setOf(Voltage.HIGH, Voltage.BOTH, Voltage.LOW, null)

    /** 전주가 경로 대상인가: 손 선택이 있으면 그대로, 없으면 `고/저압` 값([voltage])이 [voltages]에 드는지. */
    fun isTarget(poleId: String, voltage: String?, voltages: Set<Voltage?>, manual: Map<String, Boolean>): Boolean =
        manual[poleId] ?: (PoleCodes.voltageOf(voltage) in voltages)

    /**
     * [poles] 중 [wanted]만 대상이 되게 하는 손 선택(다시 날기·남은 전주·가져온 경로): 전압 대상과 다른 전주만 적는다.
     * 그래서 뒤에 칩을 바꾸면 손 선택이 없는 전주만 따라 바뀐다.
     */
    fun manualFor(poles: List<Pole>, wanted: Set<String>, voltages: Set<Voltage?>): Map<String, Boolean> =
        poles.filter { (it.poleId in wanted) != (PoleCodes.voltageOf(it.voltage) in voltages) }.associate { it.poleId to (it.poleId in wanted) }
}
