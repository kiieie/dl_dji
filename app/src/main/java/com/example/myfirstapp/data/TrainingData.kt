package com.example.myfirstapp.data

import com.example.myfirstapp.core.detect.LabeledBox
import com.example.myfirstapp.core.detect.yoloDataYaml
import com.example.myfirstapp.core.detect.yoloLabelText
import java.io.File

/**
 * 재학습 데이터(9.8, 2026-11-09): 판독 화면에서 사람이 상자를 고친 사진을 YOLO 데이터셋으로 앱 폴더 `training/<모델 버전>/`에 쌓는다.
 * 모델마다 클래스 순서가 다를 수 있어 버전별 폴더로 나눈다. PC에서는 폴더째 받아(`adb pull`) `yolo train data=data.yaml`로 쓴다.
 * - `images/<사진>.jpg`: 원본 복사(처음 한 번)
 * - `labels/<사진>.txt`: 고친 뒤 상자 전부(고칠 때마다 덮어씀, 상자가 없으면 빈 파일 = 설비 없는 사진)
 * - `classes.txt`·`data.yaml`·`train.txt`(학습 사진 목록)
 */
object TrainingData {
    fun write(root: File, version: String, image: File, boxes: List<LabeledBox>, classes: List<String>) {
        val dir = File(root, safeName(version))
        val img = File(dir, "images/${image.name}")
        img.parentFile?.mkdirs()
        if (!img.isFile) image.copyTo(img)
        File(dir, "labels").mkdirs()
        File(dir, "labels/${image.nameWithoutExtension}.txt").writeText(yoloLabelText(boxes, classes))
        File(dir, "classes.txt").writeText(classes.joinToString("\n", postfix = "\n"))
        File(dir, "data.yaml").writeText(yoloDataYaml(classes, "train.txt"))
        File(dir, "train.txt").writeText(File(dir, "images").list().orEmpty().sorted().joinToString("\n", postfix = "\n") { "./images/$it" })
    }

    /** 결과 ZIP(4.3)에 넣을 라벨 파일: [image] 이름의 라벨 중 가장 최근 것과 그 폴더의 `data.yaml`. 없으면 null. */
    fun labelFor(root: File?, image: File): Pair<File, File>? = root?.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { d -> File(d, "labels/${image.nameWithoutExtension}.txt").takeIf { it.isFile }?.let { it to File(d, "data.yaml") } }
        .maxByOrNull { it.first.lastModified() }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
