package com.example.myfirstapp.core.model

import com.example.myfirstapp.core.config.Tuning

enum class DownloadMode { PREVIEW, ORIGINAL }

/** 촬영 방식(5.6). [OVERHEAD]는 전주 상공 1곳, [DIAGONAL4]는 전주 대각선 4곳에서 머리를 향해 찍는다. */
enum class ShotMode { OVERHEAD, DIAGONAL4 }

/** 비행 경로 완료 동작(10.5.5 체크리스트, WPML `finishAction`). */
enum class FinishAction(val wpml: String, val label: String) {
    GO_HOME("goHome", "리턴 투 홈 (RTH)"), NO_ACTION("noAction", "호버링"), AUTO_LAND("autoLand", "착륙"), GOTO_FIRST("gotoFirstWaypoint", "첫 웨이포인트로"),
}

/** 신호 끊김 조치(10.5.5 체크리스트, WPML `exitOnRCLost`·`executeRCLostAction`). [CONTINUE]는 신호가 끊겨도 경로를 계속 난다. */
enum class RcLostAction(val wpml: String, val label: String) {
    GO_BACK("goBack", "리턴 투 홈 (RTH)"), HOVER("hover", "호버링"), LANDING("landing", "착륙"), CONTINUE("goContinue", "경로 계속"),
}

/**
 * 비행 파라미터(6.3). 기본값은 설계서의 기본값을 따른다.
 * 실행 시점의 값이 미션에 그대로 저장된다(4.2).
 */
data class FlightParams(
    val transitAltitudeM: Double = 50.0,
    val inspectAltitudeM: Double = 30.0,
    val speedMps: Double = 8.0,
    val offsetDistanceM: Double = 0.0,
    val offsetBearingDeg: Double = 0.0,
    val gimbalPitchDeg: Double = -90.0,
    val lenses: List<Lens> = listOf(Lens.WIDE, Lens.ZOOM),
    val zoomRatio: Double = 7.0,
    val waitSec: Int = 5,
    val batteryReservePct: Double = 30.0,
    val vlosRadiusM: Double = 2000.0,
    val downloadMode: DownloadMode = DownloadMode.ORIGINAL,
    val shotMode: ShotMode = ShotMode.DIAGONAL4,
    /** 4방향 사선(5.6): 비행 고도(이륙 지점 기준), 전주까지 수평거리, 조준 높이(지상 기준). */
    val diagonalAltitudeM: Double = 27.0,
    val diagonalDistanceM: Double = 10.0,
    val aimHeightM: Double = 17.5,
    /** 방식 A(5.6): 다른 전주의 촬영 지점 사이(또는 이륙·복귀 구간)가 이보다 길면 이동 고도로 올라갔다 내려온다. 저장하지 않는 조정값(2026-11-04 60 → 150m: 이웃 전주 62m에서도 오르내림). */
    val transitLegThresholdM: Double = 150.0,
    /** 실시간 지형 추적(5.6, 2026-10-30 기본 켬): 켜면 KMZ 고도가 지면 기준(`realTimeFollowSurface`), 끄면 이륙 지점 기준. */
    val terrainFollow: Boolean = true,
    /**
     * 설비 조준 사진(5.6, 2026-10-30 사용자 결정): 4방향 사선에서 설치기기가 있는 전주는 지점마다 조준 높이에서 이만큼 아래(m)를 한 장 더 찍는다.
     * 기기가 여럿이면 가장 많이 내리는 값. 비우면 설비 사진을 찍지 않는다.
     */
    val equipmentDropM: Map<Equipment, Double> = Equipment.entries.associateWith { 2.0 },
    /** 체크리스트(10.5.5) 안전한 이륙 고도(KMZ `takeOffSecurityHeight`). null이면 첫 지점 고도와 [Tuning.Safety.TAKEOFF_SECURITY_M] 중 큰 값. */
    val takeoffSecurityM: Double? = null,
    /** 체크리스트 시작 위치로 상승: 켜면 이륙 안전 고도로 오른 뒤 첫 지점으로(WPML `flyToWaylineMode` safely), 끄면 곧바로(pointToPoint). */
    val climbToStart: Boolean = true,
    val finishAction: FinishAction = FinishAction.GO_HOME,
    val rcLostAction: RcLostAction = RcLostAction.GO_BACK,
    /** 체크리스트 폴더 만들기: SD 사진 폴더 이름. null이면 앱이 "<프로젝트>-#<미션>"으로 정한다. */
    val photoFolder: String? = null,
    /** 전주별 설정(5.7, Pilot 2 웨이포인트별 ALT·짐벌 피치): 전주번호 → 덮어쓸 값. 없는 전주·값은 위 미션 설정을 따른다(Pilot 2 "경로 팔로우"). */
    val poleOverrides: Map<String, PoleOverride> = emptyMap(),
) {
    /** 촬영 고도: 4방향 사선은 사선 고도, 전주 위는 점검 고도(5.6). */
    val shotAltitudeM: Double get() = if (shotMode == ShotMode.DIAGONAL4) diagonalAltitudeM else inspectAltitudeM

    /** 촬영 고도로 고를 수 있는 범위(전주별 설정의 [−][+] 끝값, [validate]와 같음): 사선은 전선 위 10m ~ 이동 고도, 전주 위는 1m ~ 이동 고도. */
    val shotAltitudeRangeM: ClosedFloatingPointRange<Double>
        get() = (if (shotMode == ShotMode.DIAGONAL4) Tuning.Safety.WIRE_HEIGHT_M + Tuning.Safety.WIRE_CLEARANCE_M else 1.0)..transitAltitudeM

    /** 전주 [poleId]의 전주별 설정을 [o]로 바꾼다. 비면(모두 경로 팔로우) 지운다. */
    fun withOverride(poleId: String, o: PoleOverride) =
        copy(poleOverrides = if (o.altitudeM == null && o.gimbalPitchDeg == null) poleOverrides - poleId else poleOverrides + (poleId to o))

    /**
     * 전주 하나에서 받을 사진 파일 수(5.6) = 촬영 동작 수: 촬영 지점 수(사선 4, 전주 위 1) × 렌즈별 동작 수, 사선에서 설치기기가 있으면 설비 조준 사진으로 2배.
     * 렌즈를 한 동작에 묶는 옛 방식([Tuning.Photo.ACTION_PER_LENS] 거짓)은 동작당 1장이다(2026-11-03 현장 `_V` 1장, "52/128"). [pole]을 모르면 설비 사진은 셈하지 않는다.
     */
    fun photosPerPole(pole: Pole? = null): Int {
        val points = if (shotMode == ShotMode.DIAGONAL4) 4 else 1
        val aims = if (shotMode == ShotMode.DIAGONAL4 && PoleCodes.equipmentsOf(pole?.equipment).any { it in equipmentDropM }) 2 else 1
        val perAim = if (Tuning.Photo.ACTION_PER_LENS) lenses.distinct().size.coerceAtLeast(1) else 1
        return points * perAim * aims
    }

    /**
     * 미션의 예상 사진 수(10.5 숫자 규칙, 2026-11-04): 미션의 모든 방문 전주에 [photosPerPole]을 더한 값.
     * 홈·라이브러리·미션 칸·계획 칩이 모두 이 값을 "사진 찍은/예상"의 분모로 쓴다.
     */
    fun expectedPhotos(poles: List<Pole>): Int = poles.sumOf { photosPerPole(it) }

    /** RTH 고도 = 이동 고도 + 10m, 법정 상한 150m을 넘지 않는다(6.3). */
    val rthAltitudeM: Double get() = minOf(transitAltitudeM + Tuning.Safety.RTH_MARGIN_M, Tuning.Safety.MAX_ALTITUDE_M)

    /** 설정값 검증. 문제가 없으면 빈 목록을 돌려준다. */
    fun validate(): List<String> = buildList {
        if (transitAltitudeM !in 30.0..120.0) add("이동 고도는 30~120m여야 합니다.")
        if (inspectAltitudeM <= 0.0 || inspectAltitudeM > transitAltitudeM) add("점검 고도는 0m 초과, 이동 고도 이하여야 합니다.")
        if (transitAltitudeM + Tuning.Safety.RTH_MARGIN_M > Tuning.Safety.MAX_ALTITUDE_M) add("RTH 고도가 150m를 넘습니다.")
        if (speedMps !in Tuning.Safety.SPEED_RANGE_MPS) add("비행 속도는 1.0~15.0m/s여야 합니다.")
        if (offsetDistanceM < 0.0) add("오프셋 거리는 0m 이상이어야 합니다.")
        if (gimbalPitchDeg !in Tuning.Safety.GIMBAL_PITCH_RANGE_DEG) add("짐벌 피치는 -90~0°여야 합니다.")
        if (lenses.isEmpty()) add("촬영 렌즈를 하나 이상 선택해야 합니다.")
        if (waitSec < 0) add("대기 시간은 0초 이상이어야 합니다.")
        if (batteryReservePct < Tuning.Safety.MIN_RESERVE_PCT) add("배터리 예비율은 20% 이상이어야 합니다.")
        if (vlosRadiusM <= 0.0) add("가시권 반경은 0m보다 커야 합니다.")
        takeoffSecurityM?.let { if (it !in Tuning.Safety.TAKEOFF_SECURITY_RANGE_M) add("안전한 이륙 고도는 ${Tuning.Safety.TAKEOFF_SECURITY_RANGE_M.start.toInt()}~${Tuning.Safety.TAKEOFF_SECURITY_RANGE_M.endInclusive.toInt()}m여야 합니다.") }
        if (shotMode == ShotMode.DIAGONAL4) {
            // 지형 추적이면 지면 기준이라 경사지에서 여유가 줄지 않아 전선 위 7.5m(대각 10m 배치)까지 허용한다(5.6)
            // 지형 추적은 기체 아래 땅 기준이라 비탈 아래쪽 사선 지점에서는 전선 여유가 줄어든다. 현장 PoC 전까지 지형 추적이어도 27m(2026-10-30 사용자 결정)
            if (diagonalAltitudeM < Tuning.Safety.WIRE_HEIGHT_M + Tuning.Safety.WIRE_CLEARANCE_M) add("4방향 사선 고도는 전선(약 17m)보다 10m 이상 높아야 합니다(27m 이상).")
            if (diagonalAltitudeM > transitAltitudeM) add("4방향 사선 고도는 이동 고도 이하여야 합니다.")
            if (diagonalAltitudeM + Tuning.Safety.RTH_MARGIN_M > Tuning.Safety.MAX_ALTITUDE_M) add("4방향 사선 고도가 너무 높습니다.")
            if (diagonalDistanceM !in 3.0..50.0) add("전주까지 수평거리는 3~50m여야 합니다.")
            if (aimHeightM !in 0.0..diagonalAltitudeM) add("조준 높이는 0m 이상, 비행 고도 이하여야 합니다.")
        }
        // 전주별 설정(5.7)도 미션 설정과 같은 범위를 지킨다(이동 고도를 낮추면 걸릴 수 있음)
        poleOverrides.forEach { (id, o) ->
            o.altitudeM?.let { if (it !in shotAltitudeRangeM) add("전주 $id 고도는 ${shotAltitudeRangeM.start.toInt()}~${shotAltitudeRangeM.endInclusive.toInt()}m여야 합니다.") }
            o.gimbalPitchDeg?.let { if (it !in Tuning.Safety.GIMBAL_PITCH_RANGE_DEG) add("전주 $id 짐벌 피치는 -90~0°여야 합니다.") }
        }
    }
}

/** 전주 하나의 촬영 고도(이륙 지점 또는 지면 기준, 미션 고도 모드와 같음)·짐벌 피치 덮어쓰기(5.7). null은 미션 설정을 따른다. */
data class PoleOverride(val altitudeM: Double? = null, val gimbalPitchDeg: Double? = null)
