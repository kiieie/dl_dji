package com.example.myfirstapp.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * 이어하기 전에 운용자에게 물어야 하는지(F-20). 마지막 비행이 오늘(현지 날짜)이 아니면 다른 날·다른 장소일 수 있으므로 묻는다.
 * 비행한 적이 없으면 묻지 않는다.
 */
fun needsResumeConfirm(lastFlightAtMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (lastFlightAtMs == null) return false
    val last = Instant.ofEpochMilli(lastFlightAtMs).atZone(zone).toLocalDate()
    return last != Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
}
