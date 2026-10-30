package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
import com.example.myfirstapp.core.model.GeoPoint
import com.example.myfirstapp.core.model.Pole
import com.example.myfirstapp.core.model.ShotMode
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 소티 하나를 DJI WPML 1.0.2 KMZ(`wpmz/template.kml` + `wpmz/waylines.wpml`)로 쓴다(6.1 방식 A, 6.2).
 * 형식은 현장에서 M3E로 실제 비행한 KMZ(대평선 M1~M6)를 따른다.
 *
 * - 모든 촬영 지점에서 멈춘 뒤 돈다(`toPointAndStopWithDiscontinuityCurvature`). 사진이 흔들리지 않고, 현장 업로드 오류 1546(시작·끝 회전)도 생기지 않는다.
 * - 지점마다 도착하면 짐벌 피치를 맞추고 광각으로 1장 찍는다. 파일 이름 끝(`fileSuffix`)에 전주번호와 지점 번호를 붙여 SD 사진과 전주를 잇는다.
 * - 고도는 지형 추적이면 지면 기준, 아니면 이륙 지점 기준이다([heightMode]). 다른 전주로 가는 구간·이륙 뒤 첫 구간·복귀 구간이 [FlightParams.transitLegThresholdM]보다 길면
 *   이동 고도로 올라갔다 내려온다(5.6). 그 밖의 구간은 촬영 고도로 곧게 난다.
 */
object WpmlWriter {
    /**
     * KMZ 웨이포인트 하나. [shot]은 촬영 지점 번호([write]에 넘긴 순서)이고, 이동 고도 지점·마무리 지점은 null(촬영 동작 없음).
     * [poi]는 4방향 사선에서 이 지점을 떠나 다음 지점까지 기수가 바라볼 전주(전주 위 방식은 null).
     */
    data class Waypoint(
        val position: GeoPoint,
        val altitudeM: Double,
        val headingDeg: Double,
        val gimbalPitchDeg: Double,
        val shot: Int?,
        val poi: Pole?,
    )

    /** M3E 기체·카메라 값(현장 KMZ와 같음). M3T는 기체 77/1, 카메라 67. */
    private const val DRONE_ENUM = 77
    private const val DRONE_SUB_ENUM = 0
    private const val PAYLOAD_ENUM = 66
    private const val NS = """xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="http://www.dji.com/wpmz/1.0.2""""

    /**
     * 소티 KMZ를 쓰고 웨이포인트 목록을 돌려준다(어댑터가 KMZ 번호를 촬영 지점 번호로 바꿀 때 [shotIndex]에 쓴다).
     * [start]는 미션을 시작하는 곳(지상이면 이륙 지점, 재업로드면 현재 위치), [home]은 복귀할 이륙 지점. 없으면 그 구간은 이동 고도를 쓰지 않는다.
     */
    fun write(
        points: List<Viewpoint>,
        params: FlightParams,
        out: OutputStream,
        nowMs: Long = System.currentTimeMillis(),
        start: GeoPoint? = null,
        home: GeoPoint? = start,
    ): List<Waypoint> {
        val wps = waypoints(points, params, start, home)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml"))
            zip.write(template(wps, points, params, nowMs).toByteArray())
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            zip.write(waylines(wps, points, params).toByteArray())
            zip.closeEntry()
        }
        return wps
    }

    /**
     * 촬영 지점에 이동 고도 지점을 끼운다(5.6): 다른 전주로 가는 수평 거리가 [FlightParams.transitLegThresholdM]보다 길면
     * 떠나는 곳 상공(이동 고도) → 다음 촬영 지점 상공(이동 고도) → 촬영 지점. 이륙 뒤 첫 구간([start])도 같고,
     * 마지막 지점에서 [home]까지 멀면 마지막 지점 상공까지 올린 뒤 끝낸다(완료 동작 RTH).
     */
    fun waypoints(points: List<Viewpoint>, params: FlightParams, start: GeoPoint? = null, home: GeoPoint? = start): List<Waypoint> {
        require(points.isNotEmpty()) { "촬영 지점이 없습니다." }
        val diagonal = params.shotMode == ShotMode.DIAGONAL4
        fun far(a: GeoPoint, b: GeoPoint) = LocalProjection.distanceM(a, b) > params.transitLegThresholdM
        fun high(v: Viewpoint) = maxOf(params.transitAltitudeM, v.altitudeM)
        val out = mutableListOf<Waypoint>()
        // 기수·짐벌은 도착할 촬영 지점 값을 미리 쓴다(사선은 도착할 전주를 보며 오르내림)
        fun climbTo(from: GeoPoint, to: Viewpoint) {
            val poi = if (diagonal) to.pole else null
            out += Waypoint(from, high(to), to.headingDeg, to.gimbalPitchDeg, null, poi)
            out += Waypoint(to.position, high(to), to.headingDeg, to.gimbalPitchDeg, null, poi)
        }
        points.forEachIndexed { i, v ->
            val prev = points.getOrNull(i - 1)
            when {
                prev == null -> if (start != null && far(start, v.position)) climbTo(start, v)
                prev.pole != v.pole && far(prev.position, v.position) -> climbTo(prev.position, v)
            }
            out += Waypoint(v.position, v.altitudeM, v.headingDeg, v.gimbalPitchDeg, i, if (diagonal) points[minOf(i + 1, points.lastIndex)].pole else null)
        }
        val last = points.last()
        val lastPoi = if (diagonal) last.pole else null
        if (home != null && far(last.position, home)) out += Waypoint(last.position, high(last), last.headingDeg, last.gimbalPitchDeg, null, lastPoi)
        // 기체는 웨이포인트를 2개 이상 요구한다(2026-10-30 시뮬레이터). 하나뿐이면 촬영 없는 마무리 지점을 1m 북쪽에 덧붙인다
        if (out.size < 2) out += Waypoint(LocalProjection(last.position).toGeo(Enu(0.0, 1.0)), last.altitudeM, last.headingDeg, last.gimbalPitchDeg, null, lastPoi)
        return out
    }

    /** KMZ 웨이포인트 번호 → 촬영 지점 번호: 그 웨이포인트까지 지나온 마지막 촬영 지점(사진 알림이 다음 번호로 바뀐 뒤 와도 같은 지점). 아직 없으면 null. */
    fun shotIndex(wps: List<Waypoint>, kmzIndex: Int): Int? = wps.take(kmzIndex + 1).lastOrNull { it.shot != null }?.shot

    /** SD 파일 이름에 붙일 수 있게 영문·숫자·`-`만 남긴다. */
    fun fileSuffix(p: Viewpoint) = "${p.pole.poleId.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' }}_${p.index}"

    private fun f(v: Double) = String.format(Locale.ROOT, "%.8f", v)
    private fun n(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    /**
     * 고도 모드(5.6): 실시간 지형 추적(DJI `REAL_TIME_SURFACE_FOLLOW`, M3E/M3T)은 WPML 값 `realTimeFollowSurface`로
     * template `heightMode`와 waylines `executeHeightMode`에 같은 값을 쓴다. 끄면 이륙 지점 기준.
     */
    private fun heightMode(p: FlightParams) = if (p.terrainFollow) "realTimeFollowSurface" else "relativeToStartPoint"

    /** WPML 기수는 -180~180. */
    private fun heading(deg: Double) = n(if (deg > 180.0) deg - 360.0 else deg)

    private fun missionConfig(points: List<Waypoint>, p: FlightParams) = """
  <wpml:missionConfig>
    <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
    <wpml:finishAction>goHome</wpml:finishAction>
    <wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>
    <wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>
    <wpml:takeOffSecurityHeight>${n(maxOf(20.0, points.first().altitudeM))}</wpml:takeOffSecurityHeight>
    <wpml:globalTransitionalSpeed>${n(p.speedMps)}</wpml:globalTransitionalSpeed>
    <wpml:droneInfo><wpml:droneEnumValue>$DRONE_ENUM</wpml:droneEnumValue><wpml:droneSubEnumValue>$DRONE_SUB_ENUM</wpml:droneSubEnumValue></wpml:droneInfo>
    <wpml:payloadInfo><wpml:payloadEnumValue>$PAYLOAD_ENUM</wpml:payloadEnumValue><wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue><wpml:payloadPositionIndex>0</wpml:payloadPositionIndex></wpml:payloadInfo>
  </wpml:missionConfig>"""

    /** 기수: 사선(5.6)은 [Waypoint.poi]를 바라봄(이동 중에도 목표를 봄), 전주 위는 지점 값으로 부드럽게 바꿈. */
    private fun headingParam(v: Waypoint, p: FlightParams): String {
        val poi = v.poi
        return if (poi == null) {
        "<wpml:waypointHeadingParam><wpml:waypointHeadingMode>smoothTransition</wpml:waypointHeadingMode><wpml:waypointHeadingAngle>${heading(v.headingDeg)}</wpml:waypointHeadingAngle>" +
            "<wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint><wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode></wpml:waypointHeadingParam>"
    } else {
        // 관심점 = 전주 머리(위도,경도,조준 높이)
        "<wpml:waypointHeadingParam><wpml:waypointHeadingMode>towardPOI</wpml:waypointHeadingMode><wpml:waypointHeadingAngle>${heading(v.headingDeg)}</wpml:waypointHeadingAngle>" +
            "<wpml:waypointPoiPoint>${f(poi.position.lat)},${f(poi.position.lon)},${n(Viewpoints.aimHeight(poi, p))}</wpml:waypointPoiPoint><wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode></wpml:waypointHeadingParam>"
        }
    }

    private const val TURN =
        "<wpml:waypointTurnParam><wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode><wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist></wpml:waypointTurnParam>"

    /** 촬영 지점에만 동작을 넣는다(이동 고도·마무리 지점은 동작 없음). */
    private fun shotActions(i: Int, v: Waypoint, shots: List<Viewpoint>) = v.shot?.let { actions(i, shots[it], v.poi) } ?: ""

    private fun template(points: List<Waypoint>, shots: List<Viewpoint>, p: FlightParams, nowMs: Long) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<kml $NS>
<Document>
  <wpml:author>dl_dji</wpml:author><wpml:createTime>$nowMs</wpml:createTime><wpml:updateTime>$nowMs</wpml:updateTime>""")
        append(missionConfig(points, p))
        append("""
  <Folder>
    <wpml:templateType>waypoint</wpml:templateType><wpml:templateId>0</wpml:templateId>
    <wpml:waylineCoordinateSysParam><wpml:coordinateMode>WGS84</wpml:coordinateMode><wpml:heightMode>${heightMode(p)}</wpml:heightMode></wpml:waylineCoordinateSysParam>
    <wpml:autoFlightSpeed>${n(p.speedMps)}</wpml:autoFlightSpeed><wpml:globalHeight>${n(points.first().altitudeM)}</wpml:globalHeight><wpml:caliFlightEnable>0</wpml:caliFlightEnable>
    <wpml:gimbalPitchMode>usePointSetting</wpml:gimbalPitchMode>
    <wpml:globalWaypointHeadingParam><wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode><wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle><wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint><wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode></wpml:globalWaypointHeadingParam>
    <wpml:globalWaypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:globalWaypointTurnMode><wpml:globalUseStraightLine>1</wpml:globalUseStraightLine>""")
        points.forEachIndexed { i, v ->
            append("""
      <Placemark><Point><coordinates>${f(v.position.lon)},${f(v.position.lat)}</coordinates></Point>
        <wpml:index>$i</wpml:index><wpml:ellipsoidHeight>${n(v.altitudeM)}</wpml:ellipsoidHeight><wpml:height>${n(v.altitudeM)}</wpml:height>
        <wpml:useGlobalHeight>0</wpml:useGlobalHeight><wpml:useGlobalSpeed>1</wpml:useGlobalSpeed><wpml:useGlobalHeadingParam>0</wpml:useGlobalHeadingParam>
        ${headingParam(v, p)}
        <wpml:useGlobalTurnParam>0</wpml:useGlobalTurnParam>$TURN<wpml:useStraightLine>1</wpml:useStraightLine><wpml:gimbalPitchAngle>${n(v.gimbalPitchDeg)}</wpml:gimbalPitchAngle>
${shotActions(i, v, shots)}
      </Placemark>""")
        }
        append("\n  </Folder>\n</Document>\n</kml>\n")
    }

    private fun waylines(points: List<Waypoint>, shots: List<Viewpoint>, p: FlightParams) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<kml $NS>
<Document>""")
        append(missionConfig(points, p))
        append("""
  <Folder>
    <wpml:templateId>0</wpml:templateId><wpml:executeHeightMode>${heightMode(p)}</wpml:executeHeightMode><wpml:waylineId>0</wpml:waylineId>
    <wpml:autoFlightSpeed>${n(p.speedMps)}</wpml:autoFlightSpeed>""")
        points.forEachIndexed { i, v ->
            append("""
      <Placemark><Point><coordinates>${f(v.position.lon)},${f(v.position.lat)}</coordinates></Point>
        <wpml:index>$i</wpml:index><wpml:executeHeight>${n(v.altitudeM)}</wpml:executeHeight><wpml:waypointSpeed>${n(p.speedMps)}</wpml:waypointSpeed>
        ${headingParam(v, p)}
        $TURN
        <wpml:useStraightLine>1</wpml:useStraightLine>
${shotActions(i, v, shots)}
      </Placemark>""")
        }
        append("\n  </Folder>\n</Document>\n</kml>\n")
    }

    /**
     * 도착하면 차례로: (사선) 기수를 이 지점의 전주로 → 짐벌 피치 맞추기 → 광각 1장 촬영.
     * 사선은 구간 관심점이 다음 전주라 도착 뒤 기수가 그쪽으로 돌 수 있어, 찍기 전에 이 전주로 되돌린다(가까운 쪽으로).
     */
    private fun actions(i: Int, v: Viewpoint, poi: Pole?) = """        <wpml:actionGroup><wpml:actionGroupId>$i</wpml:actionGroupId><wpml:actionGroupStartIndex>$i</wpml:actionGroupStartIndex><wpml:actionGroupEndIndex>$i</wpml:actionGroupEndIndex><wpml:actionGroupMode>sequence</wpml:actionGroupMode><wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
${if (poi != null) rotateYaw(v, poi) else ""}          <wpml:action><wpml:actionId>${if (poi != null) 1 else 0}</wpml:actionId><wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc><wpml:actionActuatorFuncParam><wpml:gimbalHeadingYawBase>north</wpml:gimbalHeadingYawBase><wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode><wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable><wpml:gimbalPitchRotateAngle>${n(v.gimbalPitchDeg)}</wpml:gimbalPitchRotateAngle><wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable><wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle><wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable><wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle><wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable><wpml:gimbalRotateTime>0</wpml:gimbalRotateTime><wpml:payloadPositionIndex>0</wpml:payloadPositionIndex></wpml:actionActuatorFuncParam></wpml:action>
          <wpml:action><wpml:actionId>${if (poi != null) 2 else 1}</wpml:actionId><wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc><wpml:actionActuatorFuncParam><wpml:payloadPositionIndex>0</wpml:payloadPositionIndex><wpml:fileSuffix>${fileSuffix(v)}</wpml:fileSuffix><wpml:payloadLensIndex>wide</wpml:payloadLensIndex><wpml:useGlobalPayloadLensIndex>0</wpml:useGlobalPayloadLensIndex></wpml:actionActuatorFuncParam></wpml:action>
        </wpml:actionGroup>"""

    private fun rotateYaw(v: Viewpoint, poi: Pole): String {
        val turn = (v.headingDeg - Viewpoints.bearing(v.position, poi.position)).mod(360.0)
        val path = if (turn <= 180.0) "clockwise" else "counterClockwise"
        return "          <wpml:action><wpml:actionId>0</wpml:actionId><wpml:actionActuatorFunc>rotateYaw</wpml:actionActuatorFunc><wpml:actionActuatorFuncParam>" +
            "<wpml:aircraftHeading>${heading(v.headingDeg)}</wpml:aircraftHeading><wpml:aircraftPathMode>$path</wpml:aircraftPathMode></wpml:actionActuatorFuncParam></wpml:action>\n"
    }
}
