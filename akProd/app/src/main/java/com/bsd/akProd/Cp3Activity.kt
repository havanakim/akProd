package com.bsd.akProd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

open class Cp3Activity : AppCompatActivity() {
    protected open val registrationPage: String = Cp3TableReader.INJECTION
    private data class Photo(val file: File, val loadedAt: String, var ocr: String = "")

    private val photos = mutableListOf<Photo>()
    private val fields = linkedMapOf<String, EditText>()
    private val absent = linkedMapOf<String, CheckBox>()
    private val editing = mutableListOf<android.view.View>()
    private val korean by lazy {
        TextRecognition.getClient(
            KoreanTextRecognizerOptions.Builder().build()
        )
    }
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private lateinit var body: LinearLayout
    private lateinit var preview: CaptureRegionView
    private lateinit var status: TextView
    private lateinit var saveStatus: TextView
    private lateinit var confirmed: CheckBox
    private lateinit var photoSelector: Spinner
    private lateinit var machine: MachineNoPicker
    private var cameraUri: Uri? = null
    private var requestId = UUID.randomUUID().toString()
    private var capturedAt = ""
    private var busy = false
    private var pending = false
    private var saved = false
    private var restoring = false
    private val recordPage get()=registrationPage
    private fun activeLabels()=Cp3TableReader.labelsFor(recordPage)
    private fun disabledFields()=Cp3TableReader.disabledFor(recordPage)
    private fun selectableLabels()=activeLabels().filterKeys { it !in disabledFields() }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let(::loadPhoto)
    }
    private val gallery =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::loadPhoto) }
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else message("카메라 권한을 허용하거나 사진 선택을 이용하세요.")
        }

    private fun now() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date())

    @SuppressLint("ResourceType")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(
            20,
            20,
            20,
            20
        )
        }
        setContentView(ScrollView(this).apply { addView(body) })
        label((if(recordPage==Cp3TableReader.METERING) "계량·흘림방지(color)" else "사출·보압(color)")+" · 수치 등록 (v${BuildConfig.VERSION_NAME})", 22f)
        label(
            "작업자: ${intent.getStringExtra("empName").orEmpty()} (${
                intent.getStringExtra("empNo").orEmpty()
            })"
        )
        label("전체 사진 또는 좌측/우측 확대 사진을 추가하면 같은 건에 값을 모읍니다. 동일 시점의 설정 화면만 함께 등록하세요.")
        label(if(recordPage==Cp3TableReader.METERING) "계량 1→2→3→4→5: 압력·속도·위치·배압 / 흘림방지1: 압력·속도·위치. 표시 없는 배압은 입력할 수 없습니다."
            else "보압 3→2→1: 압력·속도·시간 / 사출 5→4→3→2→1: 압력·속도·시간·위치")
        label("사출기 호기")
        machine = MachineNoPicker(this, 23500).also { picker ->
            body.addView(picker.spinner); editing.add(picker.spinner)
            if (state == null) picker.restoreLast()
        }
        actionRow("저장자료 조회" to { openHistory() }, "사진 촬영 추가" to {
            if (canEdit()) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            }
        })
        actionRow(
            "사진 선택 추가 (최대 5장)" to { if (canEdit()) gallery.launch("image/*") },
            "선택 사진 취소" to { cancelSelectedPhoto() })
        photoSelector = Spinner(this)
        body.addView(photoSelector)
        photoSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (!busy) showPhoto(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        preview = CaptureRegionView(this).apply {
            regions.clear(); contentDescription = "CP3 등록 사진과 인식한 수치 위치"
            onRegionChanged = { confirmed.isChecked = false }
        }
        body.addView(
            preview,
            LinearLayout.LayoutParams(-1, (350 * resources.displayMetrics.density).toInt())
        )
        editing.add(preview)
        status = label("사진을 추가하세요. 사진에서 비어 있는 칸과 숫자 0을 구분합니다.")
        val chooser = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@Cp3Activity,
                android.R.layout.simple_spinner_dropdown_item,
                selectableLabels().values.toList()
            )
        }
        body.addView(chooser)
        chooser.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                preview.selectedKey = selectableLabels().keys.elementAt(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        label("영역이 어긋나면 항목 선택 → 사진에서 숫자 부분을 드래그 → 선택 항목 다시 읽기")
        button("선택 항목만 다시 읽기") { if (canEdit()) readSelected() }
        button("0 인식 보정 학습") { if (canEdit()) learnZeroFont() }
        button("0 보정 학습 초기화") {
            if (canEdit()) {
                CaptureNumberCorrection.clear(
                    this,
                    Cp3TableReader.MACHINE
                ); message("0 글꼴 학습을 초기화했습니다.")
            }
        }
        Cp3TableReader.stagesFor(recordPage).forEach { stage ->
            label(Cp3TableReader.stageName(stage), 20f)
            val grid = FieldGrid(this, body)
            activeLabels().filterKeys { it.startsWith(stage + "_") }
                .forEach { (key, title) ->
                    val cell = grid.cell(title.substringAfter(" · "))
                    val input = EditText(this).apply {
                        id = 21000 + fields.size; isSingleLine = true
                        inputType =
                            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        hint = "입력"; textSize = 15f; gravity = android.view.Gravity.CENTER
                        contentDescription = title
                    }
                    fields[key] = input; cell.addView(input, LinearLayout.LayoutParams(-1, -2))
                    if(key in disabledFields()) { input.isEnabled=false; input.hint="해당없음" }
                    else editing.add(input)
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                        ) {
                        }

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) {
                            if (!restoring && ::confirmed.isInitialized) confirmed.isChecked = false
                        }

                        override fun afterTextChanged(s: Editable?) {}
                    })
                    if (key in Cp3TableReader.blankAllowed && key !in disabledFields()) {
                        val check = CheckBox(this).apply {
                            id = 22000 + absent.size; text = "표시없음"; textSize = 12f
                            contentDescription = "사진에서 표시 없음 (0과 다름)"
                        }
                        absent[key] = check; cell.addView(check); editing.add(check)
                        check.setOnCheckedChangeListener { _, checked ->
                            if (checked) {
                                input.setText(""); input.error = null
                            }
                            input.isEnabled = !checked && !pending && !saved && !busy
                            if (::confirmed.isInitialized && !restoring) confirmed.isChecked = false
                        }
                    }
                }
        }
        confirmed = CheckBox(this).apply { id = 23000; text = "입력값을 확인했으며 빈 항목은 null로 등록합니다." }
        body.addView(confirmed); editing.add(confirmed)
        button("서버 등록 / 동일 요청 재확인") { save() }
        saveStatus = label("서버에는 수치만 등록합니다. 000·000.0은 숫자 0이며, 빈 항목은 null로 등록합니다.")
        button("새 등록 시작") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("새 등록")
                .setMessage(if (pending) "미확인 요청의 사진과 입력값은 보관함에 남기고 새 등록을 시작합니다. 이전 건은 보관함에서 동일 요청으로 재확인할 수 있습니다." else "현재 사진과 입력값을 비우고 새 건을 시작합니다.")
                .setPositiveButton("시작") { _, _ ->
                    CapturePendingArchive.archiveActive(
                        this,
                        "cp3_pending",
                        draftKey()
                    ); reset()
                }.setNegativeButton("취소", null).show()
        }
        button("미확인 등록 보관함") { openPendingArchive() }
        button("메뉴로 돌아가기") { finish() }
        requestId = state?.getString("requestId") ?: requestId
        capturedAt = state?.getString("capturedAt").orEmpty()
        pending = state?.getBoolean("pending") ?: false
        saved = state?.getBoolean("saved") ?: false
        cameraUri = state?.getString("cameraUri")?.let(Uri::parse)
        state?.getString("photos")?.let { serialized ->
            val array = JSONArray(serialized)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i);
                val file = File(item.getString("file"))
                if (file.isFile) photos.add(
                    Photo(
                        file,
                        item.getString("loadedAt"),
                        item.optString("ocr")
                    )
                )
            }
        }
        if (state == null && CapturePendingArchive.archiveActive(
                this,
                "cp3_pending",
                draftKey()
            )
        ) message("이전 미확인 자료는 보관함으로 옮겼습니다. 새 등록을 시작하세요.")
        updatePhotoSelector()
        status.text = when {
            saved -> "등록 완료"; pending -> "등록 결과 재확인이 필요합니다. 같은 요청으로 재시도하세요."; else -> "사진 ${photos.size}장 · 인식값을 확인하세요."
        }
    }

    override fun onPostCreate(state: Bundle?) {
        super.onPostCreate(state); setBusy(false)
    }

    private fun label(value: String, size: Float = 16f) =
        TextView(this).apply { text = value; textSize = size; body.addView(this) }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title; isAllCaps =
        false; body.addView(this); setOnClickListener { if (!busy) action() }
    }

    private fun message(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    }

    private fun actionRow(vararg actions: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.forEach { (title, action) ->
            row.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 14f
                setOnClickListener { if (!busy) action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        body.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun canEdit(): Boolean {
        if (pending || saved) {
            message("등록 결과를 재확인하거나 새 등록을 시작하세요."); return false
        }
        return !busy
    }

    private fun setBusy(value: Boolean) {
        busy = value
        editing.forEach { it.isEnabled = !value && !pending && !saved }
        absent.forEach { (key, check) -> if (check.isChecked) fields[key]?.isEnabled = false }
        photoSelector.isEnabled = !value
    }

    private fun launchCamera() {
        runCatching {
            val dir = File(cacheDir, "injection").apply { mkdirs() }
            cameraUri = FileProvider.getUriForFile(
                this,
                "$packageName.injection.photos",
                File.createTempFile("cp3_camera_", ".jpg", dir)
            )
            camera.launch(cameraUri!!)
        }.onFailure { message("카메라를 실행할 수 없습니다. 사진 선택을 이용하세요.") }
    }

    private fun cancelSelectedPhoto() {
        if (!canEdit()) return
        val index = photoSelector.selectedItemPosition
        val photo = photos.getOrNull(index)
        if (photo == null) {
            message("취소할 사진이 없습니다."); return
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("선택 사진 취소")
            .setMessage("사진 ${index + 1}의 선택을 취소합니다. 이미 인식하거나 직접 입력한 수치는 유지됩니다. 취소 후 입력값을 다시 확인하세요.")
            .setPositiveButton("선택 취소") { _, _ ->
                if (!canEdit()) return@setPositiveButton
                val current = photos.indexOf(photo)
                if (current < 0) return@setPositiveButton
                photos.removeAt(current)
                confirmed.isChecked = false
                updatePhotoSelector(minOf(current, photos.lastIndex))
                status.text = "사진 선택 취소 · 남은 사진 ${photos.size}장. 기존 입력값을 확인하세요."
            }.setNegativeButton("유지", null).show()
    }

    private fun updatePhotoSelector(selected: Int = photos.lastIndex) {
        photoSelector.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            photos.mapIndexed { index, photo -> "사진 ${index + 1} · ${photo.loadedAt}" })
        if (photos.isNotEmpty()) {
            val index = selected.coerceIn(0, photos.lastIndex)
            photoSelector.setSelection(index); showPhoto(index)
        } else {
            preview.bitmap = null; preview.regions.clear(); preview.invalidate()
        }
    }

    private fun showPhoto(index: Int) {
        if (index !in photos.indices) return
        preview.bitmap = BitmapFactory.decodeFile(photos[index].file.absolutePath)
        preview.regions.clear(); preview.invalidate()
    }

    private fun loadPhoto(uri: Uri) {
        if (!canEdit()) return
        if (photos.size >= 5) {
            message("한 건당 최대 5장입니다."); return
        }
        setBusy(true)
        val loaded = now()
        lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)!!
                        .use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "사진을 읽을 수 없습니다." }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
                    val decoded = contentResolver.openInputStream(uri)!!.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("사진을 읽을 수 없습니다.")
                    val exif = contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
                    val matrix = Matrix().apply {
                        postRotate(exif.rotationDegrees.toFloat()); if (exif.isFlipped) postScale(
                        -1f,
                        1f
                    )
                    }
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                }
                val file = withContext(Dispatchers.IO) {
                    var current = image
                    var bytes: ByteArray
                    do {
                        bytes = ByteArrayOutputStream().use { output ->
                            current.compress(
                                Bitmap.CompressFormat.JPEG,
                                90,
                                output
                            ); output.toByteArray()
                        }
                        if (bytes.size > 1_500_000) current = Bitmap.createScaledBitmap(
                            current,
                            (current.width * .85f).toInt().coerceAtLeast(1),
                            (current.height * .85f).toInt().coerceAtLeast(1),
                            true
                        )
                    } while (bytes.size > 1_500_000)
                    File.createTempFile(
                        "cp3_photo_",
                        ".jpg",
                        File(filesDir, "cp3").apply { mkdirs() }).apply { writeBytes(bytes) }
                }
                val photo = Photo(file, loaded)
                photos.add(photo)
                if (capturedAt.isBlank()) capturedAt = loaded
                confirmed.isChecked = false
                updatePhotoSelector()
                preview.bitmap = image
                readTable(image, photo)
            } catch (e: Exception) {
                status.text = e.message ?: "사진 처리 실패"; setBusy(false)
            }
        }
    }

    private fun words(text: Text, width: Int, height: Int) =
        text.textBlocks.flatMap { block -> block.lines.flatMap { it.elements } }
            .mapNotNull { element ->
                element.boundingBox?.let { b ->
                    Cp3TableReader.Word(
                        element.text,
                        b.left.toFloat() / width,
                        b.top.toFloat() / height,
                        b.right.toFloat() / width,
                        b.bottom.toFloat() / height
                    )
                }
            }

    private fun readTable(image: Bitmap,photo: Photo) {
        status.text="녹색 헤더와 상단 데이터 칸을 찾는 중…"
        lifecycleScope.launch {
            try {
                val result=withContext(Dispatchers.Default) { ColoredHmiOcr.read(image,recordPage,korean,latin) }
                photo.ocr=result.details
                result.values.filterKeys { it !in disabledFields() }.forEach { (key,value) ->
                    val input=fields[key] ?: return@forEach
                    if(input.text.isBlank()) { input.setText(value); input.error=if(key in result.review) "화면 줄무늬로 0·8·9 확인이 필요합니다." else null }
                    else if(Cp3TableReader.number(input.text.toString())!=value) input.error="추가 사진의 값 ${value}과 다릅니다. 확인하세요."
                }
                disabledFields().forEach { fields[it]?.apply { setText(""); isEnabled=false } }
                preview.regions.putAll(result.regions); preview.invalidate()
                val count=fields.count { it.key !in disabledFields() && it.value.text.isNotBlank() }
                status.text="상단 표 ${result.values.size}개 인식 · $count/${selectableLabels().size}개 입력 · 숫자 확인 ${result.review.size}개. 누락된 칸은 영역을 선택해 다시 읽거나 직접 입력하세요."
                File(filesDir,"last-colored-ocr.json").writeText(JSONObject().put("version",BuildConfig.VERSION_NAME).put("page",recordPage).put("values",JSONObject(result.values)).put("details",result.details).toString(2))
            } catch(e:Exception) { status.text="상단 표 인식 실패: ${e.message}" }
            finally { setBusy(false) }
        }
    }
    private fun readLegacyTable(image: Bitmap, photo: Photo) {
        val candidates = linkedMapOf<String, MutableMap<String, Pair<Cp3TableReader.Cell, Int>>>()
        val zeroCorrected = mutableSetOf<String>()
        val segmentReview = mutableSetOf<String>()
        val lcdNotices = mutableSetOf<String>()
        val beforeZeroCorrection = mutableMapOf<String, MutableSet<String>>()
        val afterZeroCorrection = mutableMapOf<String, MutableSet<String>>()
        val anchors = linkedMapOf<String, Cp3TableReader.Word>()
        val rowWords = mutableListOf<Cp3TableReader.Word>()
        fun accept(
            text: Text, width: Int = image.width, height: Int = image.height,
            roi: RectF = RectF(0f, 0f, 1f, 1f), pass: String = "원본"
        ) {
            val tokens = words(text, width, height).map { word ->
                word.copy(
                    left = roi.left + word.left * roi.width(),
                    top = roi.top + word.top * roi.height(),
                    right = roi.left + word.right * roi.width(),
                    bottom = roi.top + word.bottom * roi.height()
                )
            }
            photo.ocr += "[인식 경로] $pass\n"
            rowWords.addAll(tokens.filter { it.text.trim() in setOf("압력", "속도", "시간", "위치", "배압") })
            val parsed = Cp3TableReader.read(tokens + anchors.values + rowWords.distinct(),recordPage)
            parsed.headers.forEach { anchors[it.text] = it }
            photo.ocr += "[표 인식]\n" + parsed.cells.entries.joinToString("\n") { (key, cell) -> "${activeLabels()[key]}=${cell.value}" } + "\n"
            parsed.cells.forEach { (key, original) ->
                val glyphs = original.words
                val corrected = CaptureNumberCorrection.correct(
                    image,
                    glyphs.minOf { it.left },
                    glyphs.minOf { it.top },
                    glyphs.maxOf { it.right },
                    glyphs.maxOf { it.bottom },
                    glyphs.joinToString("") { it.text },
                    this@Cp3Activity,
                    Cp3TableReader.MACHINE
                )
                val cell = if (corrected != null) {
                    zeroCorrected.add(key)
                    beforeZeroCorrection.getOrPut(key) { mutableSetOf() }.add(original.value)
                    Cp3TableReader.number(corrected)
                        ?.let { afterZeroCorrection.getOrPut(key) { mutableSetOf() }.add(it) }
                    photo.ocr += "[0/8/9 형태 보정] $key : " + original.value + " -> " + corrected + "\n"
                    original.copy(value = Cp3TableReader.number(corrected) ?: original.value)
                } else original
                if (CaptureNumberCorrection.needsSegmentReview(
                        image,
                        glyphs.minOf { it.left },
                        glyphs.minOf { it.top },
                        glyphs.maxOf { it.right },
                        glyphs.maxOf { it.bottom },
                        corrected ?: glyphs.joinToString("") { it.text })
                ) segmentReview.add(key)
                val votes = candidates.getOrPut(key) { linkedMapOf() }
                votes[cell.value] = cell to ((votes[cell.value]?.second ?: 0) + 1)
            }
        }

        fun finish() {
            candidates.forEach { (key, options) ->
                val verified =
                    options.filterKeys { it in afterZeroCorrection[key].orEmpty() }.values
                val chosen =
                    (if (verified.isNotEmpty()) verified else options.values).maxBy { it.second }.first
                val input = fields.getValue(key)
                val current = input.text.toString().trim()
                val tokens = chosen.words
                preview.regions[key] = RectF(
                    (tokens.minOf { it.left } - .004f).coerceAtLeast(0f),
                    (tokens.minOf { it.top } - .004f).coerceAtLeast(0f),
                    (tokens.maxOf { it.right } + .004f).coerceAtMost(1f),
                    (tokens.maxOf { it.bottom } + .004f).coerceAtMost(1f)
                )
                if (options.size > 1) {
                    input.error =
                        "인식 후보 충돌: " + options.keys.joinToString(" / ") + ". 사진과 확인해 직접 입력하세요."
                    return@forEach
                }
                if (absent[key]?.isChecked == true) input.error =
                    "사진에서 ${chosen.value}이 인식되었습니다. 표시 없음을 확인하세요."
                else if (current.isBlank()) {
                    input.setText(chosen.value)
                    input.error = if (key in zeroCorrected) "사진의 0/8/9 형태를 보정했습니다. 값을 확인하세요."
                    else if (key in segmentReview) "0/8/9 세그먼트가 불명확합니다. 사진과 확인하세요." else null
                } else if (key in zeroCorrected && (Cp3TableReader.number(current)
                        ?.let { it in beforeZeroCorrection[key].orEmpty() } == true)
                ) {
                    input.setText(chosen.value)
                    input.error = "사진의 0/8/9 형태로 재보정했습니다. 값을 확인하세요."
                } else if (Cp3TableReader.number(current) != chosen.value) {
                    input.error = "추가 사진의 값 ${chosen.value}과 기존 값이 다릅니다. 직접 확인하세요."
                }
            }
            preview.invalidate()
            val count =
                fields.count { it.value.text.isNotBlank() || absent[it.key]?.isChecked == true }
            status.text =
                "이번 사진 ${candidates.size}개 인식 · 사진 ${photos.size}장 · ${count}/${activeLabels().size}개 확인. 빈칸과 소수점을 확인하세요."
            setBusy(false)
        }

        fun readLcd() {
            lifecycleScope.launch {
                try {
                    LcdOcrPipeline.run(
                        image,
                        latin,
                        korean,
                        accept = { text, w, h, roi, pass -> accept(text, w, h, roi, pass) },
                        notice = { lcdNotices.add(it); photo.ocr += "[LCD 처리] $it\n" })
                    if (!isDestroyed) {
                        finish()
                        if (lcdNotices.isNotEmpty()) status.append("\n" + lcdNotices.joinToString("\n"))
                    }
                } finally {
                    if (!isDestroyed) setBusy(false)
                }
            }
        }
        status.text = "CP3 단계 제목과 표의 행·열을 분석하는 중…"
        korean.process(InputImage.fromBitmap(image, 0)).addOnSuccessListener { accept(it) }
            .addOnCompleteListener {
                if (isDestroyed) return@addOnCompleteListener
                val contrast =
                    Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                val gray = ColorMatrix().apply { setSaturation(0f) }
                gray.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            1.35f,
                            0f,
                            0f,
                            0f,
                            -35f,
                            0f,
                            1.35f,
                            0f,
                            0f,
                            -35f,
                            0f,
                            0f,
                            1.35f,
                            0f,
                            -35f,
                            0f,
                            0f,
                            0f,
                            1f,
                            0f
                        )
                    )
                )
                Canvas(contrast).drawBitmap(
                    image,
                    0f,
                    0f,
                    Paint().apply { colorFilter = ColorMatrixColorFilter(gray) })
                latin.process(InputImage.fromBitmap(contrast, 0))
                    .addOnSuccessListener { accept(it) }
                    .addOnCompleteListener { contrast.recycle(); if (!isDestroyed) readLcd() }
            }
    }

    private fun readSelected() {
        val key = preview.selectedKey
        if (key !in activeLabels() || preview.regions[key] == null) {
            message("선택 항목의 숫자 영역을 사진에서 드래그하세요."); return
        }
        val crop = preview.crop(key)
        setBusy(true); confirmed.isChecked = false
        lifecycleScope.launch {
            val options = linkedSetOf<String>()
            val notices = linkedSetOf<String>()
            var review = false
            fun acceptNumber(result: Text) {
                val corrected = CaptureNumberCorrection.correct(
                    crop,
                    0f,
                    0f,
                    1f,
                    1f,
                    result.text,
                    this@Cp3Activity,
                    Cp3TableReader.MACHINE
                )
                val value = Cp3TableReader.number(corrected ?: result.text) ?: return
                review = review || corrected != null ||
                        CaptureNumberCorrection.needsSegmentReview(
                            crop,
                            0f,
                            0f,
                            1f,
                            1f,
                            corrected ?: result.text
                        )
                options.add(value)
            }
            try {
                try {
                    acceptNumber(LcdOcrPipeline.recognize(latin, crop))
                } catch (e: Exception) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    notices.add("원본 숫자 인식 실패")
                }
                LcdOcrPipeline.run(
                    crop,
                    latin,
                    selected = true,
                    accept = { text, _, _, _, _ -> acceptNumber(text) },
                    notice = { notices.add(it) })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!isDestroyed) {
                    if (options.size == 1) {
                        absent[key]?.isChecked = false
                        fields[key]?.setText(options.single())
                        fields[key]?.error = if (review) "0/8/9 형태를 사진과 확인하세요."
                        else if (notices.isNotEmpty()) "일부 인식 경로가 실패했습니다. 값을 확인하세요." else null
                    } else fields[key]?.error = if (options.isEmpty())
                        "숫자를 확정하지 못했습니다. 영역을 조정하거나 직접 입력하세요."
                    else "인식 후보 충돌: " + options.joinToString(" / ") + ". 직접 확인하세요."
                }
            } finally {
                crop.recycle()
                if (!isDestroyed) setBusy(false)
            }
        }
    }

    private fun learnZeroFont() {
        val key = preview.selectedKey
        if (key !in activeLabels() || preview.regions[key] == null || photoSelector.selectedItemPosition !in photos.indices) {
            message("0이 포함된 항목을 선택하고 사진에서 숫자 전체만 드래그하세요.")
            return
        }
        val answer = EditText(this).apply {
            isSingleLine = true
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "예: 010.0 / 050.0 / 000.0"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0)
            addView(TextView(this@Cp3Activity).apply {
                text =
                    "선택 영역: " + activeLabels()[key] + "\n사진의 숫자를 앞자리 0과 소수점까지 그대로 입력하세요. 810.0으로 인식돼도 실제 표시가 010.0이면 010.0을 입력합니다."
            })
            addView(answer)
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("이 모니터의 0 글꼴 학습").setView(box)
            .setPositiveButton("학습 후 다시 읽기") { _, _ ->
                val expected = answer.text.toString().trim()
                val crop = preview.crop(key)
                try {
                    val count =
                        CaptureNumberCorrection.learn(crop, expected, this, Cp3TableReader.MACHINE)
                    val image = preview.bitmap ?: error("사진을 다시 선택하세요.")
                    confirmed.isChecked = false; setBusy(true)
                    message("0 글꼴 " + count + "개 학습. 같은 사진을 다시 읽습니다.")
                    readTable(image, photos[photoSelector.selectedItemPosition])
                } catch (e: Exception) {
                    message(e.message ?: "학습할 숫자 영역을 다시 선택하세요."); setBusy(false)
                } finally {
                    crop.recycle()
                }
            }.setNegativeButton("취소", null).show()
    }

    private fun save() {
        if (busy) return
        if (saved) {
            message("등록 완료된 건입니다. 새 등록을 시작하세요."); return
        }
        val user = intent.getStringExtra("empNo").orEmpty()
        val values = fields.mapValues { it.value.text.toString().trim() }
        val absentKeys = absent.filterValues { it.isChecked }.keys
        try {
            require(user.isNotBlank()) { "다시 로그인하세요." }
            require(machine.value != null) { "사출기 호기를 선택하세요." }
            require(confirmed.isChecked) { "입력값 확인에 체크하세요." }
            Cp3TableReader.validate(values, absentKeys,recordPage)
        } catch (e: Exception) {
            saveStatus.text = e.message ?: "입력값을 확인하세요."
            message(saveStatus.text.toString()); return
        }
        if (capturedAt.isBlank()) capturedAt =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val storedFields = JSONObject().apply {
            CaptureFieldValues.nullableValues(
                activeLabels().keys,
                values,
                absentKeys,
                Cp3TableReader::number
            )
                .forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        val data =
            JSONObject().put("SCREEN", Cp3TableReader.NAME).put("MACHINE", machine.value)
                .put("SCREEN_TYPE",recordPage).put("CAPTURED_AT", capturedAt).put("FIELDS", storedFields)
                .put("NOT_DISPLAYED", JSONArray(absentKeys.toList())).toString()
        val draft = JSONObject().put("requestId", requestId).put("capturedAt", capturedAt)
            .put("values", JSONObject(values)).put("absent", JSONArray(absentKeys.toList()))
            .put("photos", photosJson()).put("machineNo", machine.value)
        val savedRequestId = requestId
        val savedCapturedAt = capturedAt
        val draftText = draft.toString()
        val pendingPrefs = getSharedPreferences("cp3_pending", MODE_PRIVATE)
        val pendingKey = draftKey()
        val wasPending = pending
        pending = true; setBusy(true)
        saveStatus.text = "등록 요청을 기기에 보관하는 중…"
        lifecycleScope.launch {
            var timer: kotlinx.coroutines.Job? = null
            var work: CaptureSaveWorker.Work? = null
            try {
                val retained = withContext(Dispatchers.IO) {
                    pendingPrefs.edit().putString(pendingKey, draftText).commit()
                }
                if (!retained) {
                    pending = wasPending
                    error("등록 준비 정보를 저장하지 못했습니다. 기기 저장공간을 확인하세요.")
                }
                val task = CaptureSaveWorker.submit("Cp3:" + savedRequestId) { task ->
                    Cp3Repository.save(
                        savedRequestId,
                        user,
                        savedCapturedAt,
                        data
                    ) { task.phase.set(it) }
                }
                work = task
                timer = launch {
                    var elapsed = 0
                    while (true) {
                        saveStatus.text = "${task.phase.get()} · ${elapsed}초"
                        kotlinx.coroutines.delay(1000); elapsed++
                    }
                }
                kotlinx.coroutines.withTimeout(35_000) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                        task.result.whenComplete { _, failure ->
                            if (continuation.isActive) {
                                if (failure == null) continuation.resumeWith(Result.success(Unit))
                                else continuation.resumeWith(Result.failure(failure))
                            }
                        }
                    }
                }
                pending = false; saved = true
                machine.remember()
                CapturePendingArchive.removeSaved(
                    this@Cp3Activity,
                    "cp3_pending",
                    pendingKey,
                    savedRequestId
                )
                status.text = "CP_Colored 수치 등록 완료"
                saveStatus.text = status.text
                message("등록 완료")
                openHistory(savedRequestId)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                work?.allowRetry()
                saveStatus.text = "35초 안에 저장 응답을 받지 못했습니다.\n마지막 단계: ${
                    work?.phase?.get().orEmpty()
                }\n요청: $savedRequestId\n서버에서 처리 중일 수 있습니다. 사진과 입력은 보관했습니다. 동일 요청 재확인 또는 저장자료 조회를 이용하세요."
                status.text = saveStatus.text
                message("저장 응답 지연. 등록 버튼 아래의 마지막 단계를 확인하세요.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                saveStatus.text =
                    "등록 결과를 확인하지 못했습니다.\n${e.message ?: "DB 오류"}\n요청: $savedRequestId\n입력은 보관했습니다. 같은 요청으로 재확인하세요."
                status.text = saveStatus.text
                message("저장 확인 실패. 등록 버튼 아래의 오류 내용을 확인하세요.")
            } finally {
                timer?.cancel(); if (!isDestroyed) setBusy(false)
            }
        }
    }

    private fun openHistory(id: String? = null) {
        runCatching {
            startActivity(Intent(this, Cp3HistoryActivity::class.java).apply {
                putExtra("empNo", this@Cp3Activity.intent.getStringExtra("empNo"))
                putExtra("empName", this@Cp3Activity.intent.getStringExtra("empName"))
                putExtra("screenType",recordPage)
                if (id != null) putExtra("requestId", id)
            })
        }.onFailure { message("조회 화면을 열지 못했습니다. 저장자료 조회를 다시 누르세요.") }
    }

    private fun openPendingArchive() {
        val items = CapturePendingArchive.items(this, "cp3_pending", draftKey())
        if (items.isEmpty()) {
            message("보관된 미확인 등록이 없습니다."); return
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("미확인 등록 · 선택하여 결과 재확인")
            .setItems(items.map { it.label }.toTypedArray()) { _, index ->
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("보관 자료 열기")
                    .setMessage("현재 작성 화면을 닫고 선택한 요청을 엽니다. 미확인 요청은 보관함에 유지됩니다. 저장 여부를 확인한 뒤 새 등록을 시작하세요.")
                    .setPositiveButton("열기") { _, _ ->
                        CapturePendingArchive.activate(
                            this,
                            "cp3_pending",
                            draftKey(),
                            items[index]
                        )
                        reset(); restorePendingDraft(); updatePhotoSelector()

                        setBusy(false)
                        status.text = "보관된 요청을 열었습니다. 동일 요청 재확인으로 저장 결과를 확인하세요."
                    }.setNegativeButton("취소", null).show()
            }.setNegativeButton("닫기", null).show()
    }

    private fun draftKey() = "user." + intent.getStringExtra("empNo").orEmpty() + if(recordPage==Cp3TableReader.METERING) ".metering" else ""
    private fun photosJson() = JSONArray().apply {
        photos.forEach {
            put(
                JSONObject().put("file", it.file.absolutePath).put("loadedAt", it.loadedAt)
                    .put("ocr", it.ocr)
            )
        }
    }

    private fun restorePendingDraft() {
        val raw =
            getSharedPreferences("cp3_pending", MODE_PRIVATE).getString(draftKey(), null) ?: return
        runCatching {
            val draft = JSONObject(raw)
            restoring = true
            requestId = draft.getString("requestId"); capturedAt = draft.getString("capturedAt")
            draft.optString("machineNo").takeIf { it.isNotBlank() }?.let { machine.value = it }
            val values = draft.getJSONObject("values")
            fields.forEach { (key, input) -> input.setText(values.optString(key)) }
            absent.values.forEach { it.isChecked = false }
            val absentArray = draft.getJSONArray("absent")
            for (i in 0 until absentArray.length()) absent[absentArray.getString(i)]?.isChecked =
                true
            val list = draft.getJSONArray("photos")
            photos.clear()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                photos.add(
                    Photo(
                        File(item.getString("file")),
                        item.getString("loadedAt"),
                        item.optString("ocr")
                    )
                )
            }
            pending = true; saved = false; confirmed.isChecked = true
        }.onFailure { message("미확인 등록 건을 복원하지 못했습니다. 앱 데이터를 지우지 말고 관리자에게 문의하세요.") }
        restoring = false
    }

    private fun reset() {
        restoring = true
        fields.values.forEach { it.setText(""); it.error = null }
        absent.values.forEach { it.isChecked = false }
        photos.clear(); pending = false; saved = false; capturedAt = ""; cameraUri =
            null; requestId = UUID.randomUUID().toString()
        confirmed.isChecked = false; preview.bitmap =
            null; preview.regions.clear(); updatePhotoSelector()
        status.text = "새 사진을 추가하세요."; restoring = false; setBusy(false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("requestId", requestId); out.putString("capturedAt", capturedAt)
        out.putString("cameraUri", cameraUri?.toString()); out.putBoolean(
            "pending",
            pending
        ); out.putBoolean("saved", saved)
        out.putString("photos", photosJson().toString())
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        korean.close(); latin.close(); super.onDestroy()
    }
}



