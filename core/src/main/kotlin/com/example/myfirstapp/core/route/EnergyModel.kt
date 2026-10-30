package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.ShotMode

/** 에너지 모델 파라미터(5.5). 초기값은 보수적으로 잡았고 필드 테스트로 보정한다. */
data class EnergyParams(
    /** 순항 소모율(%/s). M3E 공칭 45분 대신 25분 기준. */
    val cruisePctPerSec: Double = 100.0 / (25 * 60),
    /** 호버 소모율(%/s). 28분 기준. */
    val hoverPctPerSec: Double = 100.0 / (28 * 60),
    /** 전주 1개 처리 시간(촬영 + 다운로드 + 분석 + 대기). */
    val visitSec: Double = 20.0,
    /** 구간 KMZ 생성·업로드·시작 지연(PoC ①에서 실측해 교체). */
    val legOverheadSec: Double = 10.0,
    /** 상승·하강 속도. 이착륙과 점검 고도 하강·재상승 비용 계산에 쓴다. */
    val verticalSpeedMps: Double = 3.0,
    /** 계획 예산에서 빼는 RTH 여유(%). */
    val rthMarginPct: Double = 5.0,
    /**
     * 6.1 방식 A(소티 미션 하나로 계속 비행)의 촬영 지점 하나 처리 시간: 멈춤·짐벌·촬영 후 출발.
     * 2026-10-30 시뮬레이터 실측 지점 간격 약 6.5초(이동 포함). 다운로드·분석은 비행과 병렬이라 넣지 않는다.
     */
    val shotSec: Double = 6.0,
)

/** 배터리 소모 추정(5.5). 모든 값은 배터리 %이다. */
class EnergyModel(val params: EnergyParams, val flight: FlightParams) {

    fun move(distanceM: Double): Double = distanceM / flight.speedMps * params.cruisePctPerSec

    /**
     * 전주 1개 방문 비용. 방식 A: 촬영 지점 수 × 지점 처리 시간(이동은 거리로 따로 센다).
     * 방식 B(구간 미션)의 구간 준비·처리 대기·점검 고도 하강·재상승은 [visitSec], [legOverheadSec]로 남겨 둔다(6.1 선택지).
     */
    val perVisit: Double
        get() = shots * params.shotSec * params.hoverPctPerSec

    val takeoffLanding: Double
        get() = 2 * flight.transitAltitudeM / params.verticalSpeedMps * params.hoverPctPerSec

    /** 소티 계획 예산 = 시작 배터리 − 예비율 − RTH 여유. */
    fun budget(startBatteryPct: Double = 100.0): Double =
        startBatteryPct - flight.batteryReservePct - params.rthMarginPct

    /** 촬영 방식(5.6)에 따른 배수: 4방향 사선은 전주 사이 구간을 4번 지나고(4패스) 전주마다 4장을 찍는다. */
    val shots: Int get() = if (flight.shotMode == ShotMode.DIAGONAL4) 4 else 1

    /**
     * 이륙 지점 왕복 [homeLegsM]과 전주 사이 구간 [chainM]의 경로 길이. 4패스는 전주 사이 구간만 4번 난다.
     */
    fun pathM(homeLegsM: Double, chainM: Double): Double = homeLegsM + chainM * shots

    /** 경로 길이 [distanceM]([pathM]), 방문 [visits]개인 소티의 예상 소모량. */
    fun sortieCost(distanceM: Double, visits: Int): Double = move(distanceM) + visits * perVisit + takeoffLanding

    fun sortieDurationSec(distanceM: Double, visits: Int): Double =
        distanceM / flight.speedMps +
            visits * shots * params.shotSec +
            2 * flight.transitAltitudeM / params.verticalSpeedMps

    /**
     * 구간 출발 전 검증(5.5 실시간 검증):
     * 현재 배터리 ≥ E(현재→다음) + E_visit + E(다음→Home) + 예비율.
     */
    fun canProceed(currentPct: Double, toNextM: Double, nextToHomeM: Double): Boolean =
        currentPct >= move(toNextM) + perVisit + move(nextToHomeM) + flight.batteryReservePct

    /** 구간 비행 중 감시: 현재 배터리 < E(현재→Home) + 예비율 하한(20%)이면 RTH를 권고한다. */
    fun mustReturn(currentPct: Double, toHomeM: Double): Boolean =
        currentPct < move(toHomeM) + FlightParams.MIN_RESERVE_PCT
}
