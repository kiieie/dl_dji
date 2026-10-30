package com.example.myfirstapp.core.route

import com.example.myfirstapp.core.model.FlightParams
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
 * - 고도는 이륙 지점 기준이다. 지점 사이도 같은 고도로 곧게 난다(이동 고도 상승은 하지 않음).
 */
object WpmlWriter {
    /** M3E 기체·카메라 값(현장 KMZ와 같음). M3T는 기체 77/1, 카메라 67. */
    private const val DRONE_ENUM = 77
    private const val DRONE_SUB_ENUM = 0
    private const val PAYLOAD_ENUM = 66
    private const val NS = """xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="http://www.dji.com/wpmz/1.0.2""""

    fun write(points: List<Viewpoint>, params: FlightParams, out: OutputStream, nowMs: Long = System.currentTimeMillis()) {
        require(points.isNotEmpty()) { "촬영 지점이 없습니다." }
        val shots = points.size
        // 기체는 웨이포인트를 2개 이상 요구한다(2026-10-30 시뮬레이터). 지점이 하나면 촬영 없는 마무리 지점을 1m 북쪽에 덧붙인다
        val points = if (points.size > 1) points else points + points[0].copy(position = LocalProjection(points[0].position).toGeo(Enu(0.0, 1.0)))
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml"))
            zip.write(template(points, params, nowMs, shots).toByteArray())
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            zip.write(waylines(points, params, shots).toByteArray())
            zip.closeEntry()
        }
    }

    /** SD 파일 이름에 붙일 수 있게 영문·숫자·`-`만 남긴다. */
    fun fileSuffix(p: Viewpoint) = "${p.pole.poleId.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' }}_${p.index}"

    private fun f(v: Double) = String.format(Locale.ROOT, "%.8f", v)
    private fun n(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    /** WPML 기수는 -180~180. */
    private fun heading(deg: Double) = n(if (deg > 180.0) deg - 360.0 else deg)

    private fun missionConfig(points: List<Viewpoint>, p: FlightParams) = """
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

    private fun headingParam(p: Viewpoint) =
        "<wpml:waypointHeadingParam><wpml:waypointHeadingMode>smoothTransition</wpml:waypointHeadingMode><wpml:waypointHeadingAngle>${heading(p.headingDeg)}</wpml:waypointHeadingAngle>" +
            "<wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint><wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode></wpml:waypointHeadingParam>"

    private const val TURN =
        "<wpml:waypointTurnParam><wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode><wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist></wpml:waypointTurnParam>"

    /** [shots] 뒤의 덧붙인 지점에는 동작을 넣지 않는다. */
    private fun template(points: List<Viewpoint>, p: FlightParams, nowMs: Long, shots: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<kml $NS>
<Document>
  <wpml:author>dl_dji</wpml:author><wpml:createTime>$nowMs</wpml:createTime><wpml:updateTime>$nowMs</wpml:updateTime>""")
        append(missionConfig(points, p))
        append("""
  <Folder>
    <wpml:templateType>waypoint</wpml:templateType><wpml:templateId>0</wpml:templateId>
    <wpml:waylineCoordinateSysParam><wpml:coordinateMode>WGS84</wpml:coordinateMode><wpml:heightMode>relativeToStartPoint</wpml:heightMode></wpml:waylineCoordinateSysParam>
    <wpml:autoFlightSpeed>${n(p.speedMps)}</wpml:autoFlightSpeed><wpml:globalHeight>${n(points.first().altitudeM)}</wpml:globalHeight><wpml:caliFlightEnable>0</wpml:caliFlightEnable>
    <wpml:gimbalPitchMode>usePointSetting</wpml:gimbalPitchMode>
    <wpml:globalWaypointHeadingParam><wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode><wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle><wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint><wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode></wpml:globalWaypointHeadingParam>
    <wpml:globalWaypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:globalWaypointTurnMode><wpml:globalUseStraightLine>1</wpml:globalUseStraightLine>""")
        points.forEachIndexed { i, v ->
            append("""
      <Placemark><Point><coordinates>${f(v.position.lon)},${f(v.position.lat)}</coordinates></Point>
        <wpml:index>$i</wpml:index><wpml:ellipsoidHeight>${n(v.altitudeM)}</wpml:ellipsoidHeight><wpml:height>${n(v.altitudeM)}</wpml:height>
        <wpml:useGlobalHeight>0</wpml:useGlobalHeight><wpml:useGlobalSpeed>1</wpml:useGlobalSpeed><wpml:useGlobalHeadingParam>0</wpml:useGlobalHeadingParam>
        ${headingParam(v)}
        <wpml:useGlobalTurnParam>0</wpml:useGlobalTurnParam>$TURN<wpml:useStraightLine>1</wpml:useStraightLine><wpml:gimbalPitchAngle>${n(v.gimbalPitchDeg)}</wpml:gimbalPitchAngle>
${if (i < shots) actions(i, v) else ""}
      </Placemark>""")
        }
        append("\n  </Folder>\n</Document>\n</kml>\n")
    }

    private fun waylines(points: List<Viewpoint>, p: FlightParams, shots: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<kml $NS>
<Document>""")
        append(missionConfig(points, p))
        append("""
  <Folder>
    <wpml:templateId>0</wpml:templateId><wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode><wpml:waylineId>0</wpml:waylineId>
    <wpml:autoFlightSpeed>${n(p.speedMps)}</wpml:autoFlightSpeed>""")
        points.forEachIndexed { i, v ->
            append("""
      <Placemark><Point><coordinates>${f(v.position.lon)},${f(v.position.lat)}</coordinates></Point>
        <wpml:index>$i</wpml:index><wpml:executeHeight>${n(v.altitudeM)}</wpml:executeHeight><wpml:waypointSpeed>${n(p.speedMps)}</wpml:waypointSpeed>
        ${headingParam(v)}
        $TURN
        <wpml:useStraightLine>1</wpml:useStraightLine>
${if (i < shots) actions(i, v) else ""}
      </Placemark>""")
        }
        append("\n  </Folder>\n</Document>\n</kml>\n")
    }

    /** 도착하면 차례로: 짐벌 피치 맞추기 → 광각 1장 촬영. */
    private fun actions(i: Int, v: Viewpoint) = """        <wpml:actionGroup><wpml:actionGroupId>$i</wpml:actionGroupId><wpml:actionGroupStartIndex>$i</wpml:actionGroupStartIndex><wpml:actionGroupEndIndex>$i</wpml:actionGroupEndIndex><wpml:actionGroupMode>sequence</wpml:actionGroupMode><wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType></wpml:actionTrigger>
          <wpml:action><wpml:actionId>0</wpml:actionId><wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc><wpml:actionActuatorFuncParam><wpml:gimbalHeadingYawBase>north</wpml:gimbalHeadingYawBase><wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode><wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable><wpml:gimbalPitchRotateAngle>${n(v.gimbalPitchDeg)}</wpml:gimbalPitchRotateAngle><wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable><wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle><wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable><wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle><wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable><wpml:gimbalRotateTime>0</wpml:gimbalRotateTime><wpml:payloadPositionIndex>0</wpml:payloadPositionIndex></wpml:actionActuatorFuncParam></wpml:action>
          <wpml:action><wpml:actionId>1</wpml:actionId><wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc><wpml:actionActuatorFuncParam><wpml:payloadPositionIndex>0</wpml:payloadPositionIndex><wpml:fileSuffix>${fileSuffix(v)}</wpml:fileSuffix><wpml:payloadLensIndex>wide</wpml:payloadLensIndex><wpml:useGlobalPayloadLensIndex>0</wpml:useGlobalPayloadLensIndex></wpml:actionActuatorFuncParam></wpml:action>
        </wpml:actionGroup>"""
}
