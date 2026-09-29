package com.example.myfirstapp.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.myfirstapp.core.port.CaptureInfo
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 모의 기체가 돌려줄 사진(10장 ⑤). 앱 폴더 `fake_photos/`에 JPEG이 있으면 이름순으로 차례로 쓰고(탐지 모델 시험용),
 * 없으면 렌즈·미디어 번호·좌표를 그린 JPEG을 만든다. 실제 기체에서는 쓰지 않는다.
 */
fun fakePhotoSource(appFilesDir: File?): (CaptureInfo) -> ByteArray {
    val samples = File(appFilesDir, "fake_photos").listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty().sortedBy { it.name }
    if (samples.isEmpty()) return ::drawFakePhoto
    var shot = 0
    return { samples[shot++ % samples.size].readBytes() }
}

private fun drawFakePhoto(c: CaptureInfo): ByteArray {
    val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(if (c.lens.name == "ZOOM") 0xFF3B4A5A.toInt() else 0xFF4A6072.toInt())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8C7B6B.toInt() }
    canvas.drawRect(305f, 80f, 335f, 480f, paint) // 전주
    canvas.drawRect(200f, 130f, 440f, 150f, paint) // 완금
    paint.color = Color.WHITE
    paint.textSize = 28f
    canvas.drawText("모의 사진 ${c.lens} ${c.mediaId}", 20f, 40f, paint)
    c.position?.let { canvas.drawText("%.6f, %.6f".format(it.lat, it.lon), 20f, 460f, paint) }
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it); bmp.recycle() }.toByteArray()
}
