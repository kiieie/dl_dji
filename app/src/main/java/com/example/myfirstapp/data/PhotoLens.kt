// lint: 플랫폼 ExifInterface(API 24+)로 초점 하나만 읽는다(PhotoInfo.kt와 같음)
@file:SuppressLint("ExifInterface")

package com.example.myfirstapp.data

import android.annotation.SuppressLint
import android.media.ExifInterface
import com.example.myfirstapp.core.model.Lens
import com.example.myfirstapp.core.model.lensOfFocal35
import java.io.ByteArrayInputStream
import java.io.File

/** 받은 사진의 실제 렌즈 이름(5.6): EXIF 35mm 환산 초점으로 정한다(줌 2~6.9배는 광각 센서라 WIDE). 읽지 못하면 [claimed]. */
fun actualLens(bytes: ByteArray, claimed: String): String {
    val lens = runCatching { Lens.valueOf(claimed) }.getOrNull() ?: return claimed
    val f35 = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
    }.getOrDefault(0)
    return lensOfFocal35(f35, lens).name
}

/** 파일 사진의 렌즈 이름(앨범 [탐지], 9.6): EXIF 초점으로. 읽지 못하면 null. IO 스레드에서 부른다. */
fun lensOfFile(file: File): String? = runCatching {
    val f35 = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
    if (f35 > 0) lensOfFocal35(f35, Lens.WIDE).name else null
}.getOrNull()
