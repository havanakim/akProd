package com.bsd.akProd

import android.Manifest
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

class Cp14Activity : AppCompatActivity() {
    private data class Photo(val file: File, val loadedAt: String, var ocr: String = "")

    private val photos = mutableListOf<Photo>()
    /** Inputs by screen: CP screens share one set (""), each PB screen has its own (their keys overlap). */
    private val pageFields = linkedMapOf<String, LinkedHashMap<String, EditText>>()
    private val fields get() = pageFields.getOrPut(if (pbMenu) recordPage else "") { linkedMapOf() }
    private var fieldCount = 0
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
    private var cameraUri: Uri? = null
    private var requestId = UUID.randomUUID().toString()
    private var capturedAt = ""
    private var busy = false
    private var pending = false
    private var saved = false
    private var restoring = false
    /** The menu ("screenType" extra): "" CP 사출/보압(mono), METERING CP 계량/배압(m), PB_INJECTION, PB_METERING. */
    private val menu get() = intent.getStringExtra("screenType").orEmpty()
    private val meteringMenu get() = menu == Cp14TableReader.METERING
    /** PB 사출/보압 and PB 계량/배압: one fixed screen each, 호기 P01~P16, JSON "HMI":"P". */
    private val pbMenu get() = menu in Cp14TableReader.pbPages
    private val menuTitle
        get() = when (menu) {
            Cp14TableReader.METERING -> "CP 계량/배압(m)"
            Cp14TableReader.PB_INJECTION -> "PB 사출/보압"
            Cp14TableReader.PB_METERING -> "PB 계량/배압"
            else -> "CP 사출/보압(mono)"
        }
    private var recordPage = Cp14TableReader.INJECTION
    /**
     * 계량/배압(m) chooses between its two screens; the injection menu between injection and metering.
     * The PB menus offer the HYUNDAI MIRACON-I screen (PB 6호기) first, then the journal-layout screen.
     */
    private val selectablePages
        get() = if (menu == Cp14TableReader.PB_METERING) listOf(Cp14TableReader.MIRACON, Cp14TableReader.PB_METERING)
        else if (menu == Cp14TableReader.PB_INJECTION) listOf(Cp14TableReader.MIRACON_INJECTION, Cp14TableReader.PB_INJECTION)
        else if (meteringMenu) listOf(Cp14TableReader.METERING, Cp14TableReader.MIRACON)
        else listOf(Cp14TableReader.INJECTION, Cp14TableReader.METERING)
    /** Typed-in PB journal items of the current screen (none on the CP screens). */
    private fun manualFields() = if (pbMenu) PbJournal.manualFields(recordPage) else emptyList()
    /** Every input the current screen saves: the OCR items and the typed-in journal items. */
    private fun entryKeys() = activeLabels().keys + manualFields().map { it.key }
    private var changingPage = false
    private lateinit var pageSelector: Spinner
    private lateinit var machine: MachineNoPicker
    private lateinit var round: RoundNoPicker
    private lateinit var pageDescription: TextView
    private lateinit var headerChooser: Spinner
    private val groupViews = linkedMapOf<String, List<android.view.View>>()
    private fun activeLabels() = Cp14TableReader.labelsFor(recordPage)
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
        label(
            "$menuTitle · 수치 등록 (v${BuildConfig.VERSION_NAME})",
            22f
        )
        label(
            "작업자: ${intent.getStringExtra("empName").orEmpty()} (${
                intent.getStringExtra("empNo").orEmpty()
            })"
        )
        recordPage = if (pbMenu) state?.getString("recordPage")?.takeIf { it in selectablePages }
            ?: selectablePages.first()
        else if (meteringMenu) state?.getString("recordPage")?.takeIf { it in selectablePages }
            ?: Cp14TableReader.METERING
        else state?.getString("recordPage")
            ?: if (state?.getBoolean("pending") == true) Cp14TableReader.LEGACY else Cp14TableReader.INJECTION
        label("화면 종류를 선택해 수치를 등록합니다. 사진은 OCR에만 사용하며 다른 화면은 새 등록으로 구분하세요.")
        pageDescription = label("")
        pageSelector = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@Cp14Activity, android.R.layout.simple_spinner_dropdown_item,
                selectablePages.map { Cp14TableReader.pageTitle(it) + " (" + Cp14TableReader.labelsFor(it).size + "개)" }
            )
        }
        body.addView(pageSelector); editing.add(pageSelector)
        label("사출기 호기 · 차수")
        machine = MachineNoPicker(this, 23500, if (pbMenu) "P" else "C").also { picker ->
            round = RoundNoPicker(this, 23501)
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(picker.spinner, LinearLayout.LayoutParams(0, -2, 1f))
                addView(round.spinner, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
            editing.add(picker.spinner); editing.add(round.spinner)
        }
        pageSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (!changingPage && recordPage != Cp14TableReader.LEGACY && ::confirmed.isInitialized) changePage(
                    selectablePages[position]
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        actionRow("저장자료 조회" to { openHistory() }, "사진 촬영 추가" to {
            if (canEdit() && machine.ensureSelected() && round.ensureSelected()) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            }
        })
        actionRow(
            "사진 선택 추가 (최대 5장)" to { if (canEdit() && machine.ensureSelected() && round.ensureSelected()) gallery.launch("image/*") },
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
            regions.clear(); contentDescription = "CP14 등록 사진과 인식한 수치 위치"
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
                this@Cp14Activity,
                android.R.layout.simple_spinner_dropdown_item,
                Cp14TableReader.labels.values.toList()
            )
        }
        headerChooser = chooser
        body.addView(chooser)
        chooser.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                preview.selectedKey = activeLabels().keys.elementAtOrNull(position) ?: return
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
                    Cp14TableReader.MACHINE
                ); message("0 글꼴 학습을 초기화했습니다.")
            }
        }
        fun addField(grid: FieldGrid, key: String, title: String, numeric: Boolean = true, page: String = "") {
            val cell = grid.cell(title.substringBefore(" · "))
            val input = EditText(this).apply {
                id = 21000 + fieldCount++; isSingleLine = true
                inputType = if (numeric)
                    android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                else android.text.InputType.TYPE_CLASS_TEXT
                hint = "입력"; textSize = 15f; gravity = android.view.Gravity.CENTER
                contentDescription = title
            }
            pageFields.getOrPut(page) { linkedMapOf() }[key] = input; cell.addView(input, LinearLayout.LayoutParams(-1, -2)); editing.add(input)
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
            if (key in Cp14TableReader.blankAllowed) {
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
        if (pbMenu) selectablePages.forEach { page ->
            PbJournal.sections(page).forEach { (title, items) ->
                // PB 공정관리일지 order; typed-in items (C/T, 온도, 중량, 비고 …) are not read from the photo.
                val firstChild = body.childCount
                label(title + if (items.none { it.ocr }) " · 직접 입력" else "", 20f)
                val grid = FieldGrid(this, body)
                items.forEach { addField(grid, it.key, it.label, it.numeric, page) }
                // Keyed by screen: MIRACON-I and S5~S1 share section titles (배압, 계량 속도, 계량 위치).
                groupViews["$page|$title"] = (firstChild until body.childCount).map { body.getChildAt(it) }
            }
        }
        else (Cp14TableReader.stages + Cp14TableReader.miraconStages).forEach { stage ->
            val firstChild = body.childCount
            label(Cp14TableReader.stageName(stage), 20f)
            val grid = FieldGrid(this, body)
            Cp14TableReader.labels.filterKeys { Cp14TableReader.prefix(it) == stage }
                .forEach { (key, title) -> addField(grid, key, title) }
            groupViews[stage] = (firstChild until body.childCount).map { body.getChildAt(it) }
        }
        confirmed = CheckBox(this).apply { id = 23000; text = "입력값을 확인했으며 빈 항목은 null로 등록합니다." }
        body.addView(confirmed); editing.add(confirmed)
        button("서버 등록") { save() }
        saveStatus = label("서버에는 수치만 등록합니다. 000·000.0은 숫자 0이며, 빈 항목은 null로 등록합니다.")
        button("새 등록 시작") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("새 등록")
                .setMessage(if (pending) "미확인 요청의 사진과 입력값은 보관함에 남기고 새 등록을 시작합니다. 이전 건은 보관함에서 다시 열 수 있습니다. 저장자료 조회에서 저장 여부를 확인한 뒤 등록하세요." else "현재 사진과 입력값을 비우고 새 건을 시작합니다.")
                .setPositiveButton("시작") { _, _ ->
                    CapturePendingArchive.archiveActive(
                        this,
                        "cp14_pending",
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
                "cp14_pending",
                draftKey()
            )
        ) message("이전 미확인 자료는 보관함으로 옮겼습니다. 새 등록을 시작하세요.")
        applyPage()
        updatePhotoSelector()
        status.text = when {
            saved -> "등록 완료"; pending -> "등록 결과를 확인하지 못했습니다. 저장자료 조회에서 확인한 뒤, 없을 때만 다시 등록하세요."; else -> "사진 ${photos.size}장 · 인식값을 확인하세요."
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
        pageSelector.isEnabled =
            !value && !pending && !saved && recordPage != Cp14TableReader.LEGACY
    }

    private fun applyPage() {
        changingPage = true
        pageSelector.visibility =
            if (recordPage == Cp14TableReader.LEGACY) android.view.View.GONE else android.view.View.VISIBLE
        pageSelector.setSelection(selectablePages.indexOf(recordPage).coerceAtLeast(0))
        pageDescription.text =
            Cp14TableReader.pageTitle(recordPage) + " · " + activeLabels().size + "개 항목 · 화면별 별도 저장"
        groupViews.forEach { (prefix, views) ->
            views.forEach {
                it.visibility =
                    if (if (pbMenu) prefix.startsWith("$recordPage|") else prefix in Cp14TableReader.prefixes(recordPage))
                        android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        headerChooser.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            activeLabels().values.toList()
        )
        changingPage = false
    }

    private fun changePage(next: String) {
        if (next == recordPage) return
        if (!canEdit()) {
            applyPage(); return
        }
        if (photos.isEmpty() && fields.values.all { it.text.isBlank() }) {
            recordPage = next; applyPage(); return
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("다른 화면으로 새 등록")
            .setMessage("현재 사진과 입력을 비우고 선택한 화면을 별도로 등록합니다.")
            .setPositiveButton("새 등록") { _, _ -> recordPage = next; reset() }
            .setNegativeButton("취소") { _, _ -> applyPage() }.setOnCancelListener { applyPage() }
            .show()
    }

    private fun launchCamera() {
        runCatching {
            val dir = File(cacheDir, "injection").apply { mkdirs() }
            cameraUri = FileProvider.getUriForFile(
                this,
                "$packageName.injection.photos",
                File.createTempFile("cp14_camera_", ".jpg", dir)
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
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4200) sample *= 2
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
                        if (bytes.size > 6_000_000) current = Bitmap.createScaledBitmap(
                            current,
                            (current.width * .85f).toInt().coerceAtLeast(1),
                            (current.height * .85f).toInt().coerceAtLeast(1),
                            true
                        )
                    } while (bytes.size > 6_000_000)
                    File.createTempFile(
                        "cp14_photo_",
                        ".jpg",
                        File(filesDir, "cp14").apply { mkdirs() }).apply { writeBytes(bytes) }
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
                    Cp14TableReader.Word(
                        element.text,
                        b.left.toFloat() / width,
                        b.top.toFloat() / height,
                        b.right.toFloat() / width,
                        b.bottom.toFloat() / height
                    )
                }
            }

    private fun readTable(image: Bitmap, photo: Photo) {
        val ocr = MonoTableOcr(image, recordPage, activeLabels().keys, this)
        var handedOff = false
        status.text = "IF·S0·IP·IV·HP·HT·S·RV·BP 헤더와 바로 아래 숫자를 분석하는 중…"
        lifecycleScope.launch {
            try {
                ocr.run(latin, korean, alive = { !isDestroyed && !ocr.wrongPage })
                photo.ocr += ocr.log.toString()
                if (isDestroyed) return@launch
                if (ocr.wrongPage) {
                    // The PB 계량/배압 menu calls the S5~S1 metering screen PB_METERING.
                    val other = ocr.suggestedPage?.let {
                        if (menu == Cp14TableReader.PB_METERING && it == Cp14TableReader.METERING) Cp14TableReader.PB_METERING else it
                    }
                    // First photo of an empty entry shows the other screen of this menu: switch and read again.
                    if (other != null && other != recordPage && other in selectablePages && photos == listOf(photo) &&
                        fields.values.all { it.text.isBlank() }
                    ) {
                        recordPage = other; applyPage()
                        status.text = Cp14TableReader.pageTitle(other) + " 화면으로 인식되어 화면 종류를 바꿔 다시 읽습니다."
                        handedOff = true
                        readTable(image, photo)
                        return@launch
                    }
                    photos.remove(photo)
                    updatePhotoSelector()
                    if (photos.isEmpty()) {
                        capturedAt = ""; preview.bitmap = null; preview.regions.clear()
                    }
                    status.text = "다른 종류의 화면" + (ocr.suggestedPage?.let { "(" + Cp14TableReader.pageTitle(it) + ")" } ?: "") +
                        "이 인식되어 사진을 제외했습니다. 화면 종류를 선택하고 새 등록하세요."
                    return@launch
                }
                ocr.decideAll().forEach { (key, decision) ->
                    val chosen = decision.cell
                    val input = fields.getValue(key)
                    val current = input.text.toString().trim()
                    val tokens = chosen.words
                    preview.regions[key] = RectF(
                        (tokens.minOf { it.left } - .004f).coerceAtLeast(0f),
                        (tokens.minOf { it.top } - .004f).coerceAtLeast(0f),
                        (tokens.maxOf { it.right } + .004f).coerceAtMost(1f),
                        (tokens.maxOf { it.bottom } + .004f).coerceAtMost(1f)
                    )
                    decision.conflict?.let { remaining ->
                        input.error = "인식 후보 충돌: " + remaining.joinToString(" / ") + ". 사진과 확인해 직접 입력하세요."
                        return@forEach
                    }
                    if (decision.outOfOrder) {
                        input.error = "위치 순서(S5→S1, IF5→S0)와 맞지 않는 인식값 ${chosen.value}입니다. 사진과 확인해 직접 입력하세요."
                        return@forEach
                    }
                    if (decision.ambiguousDigits) {
                        input.error = "0/8/9를 구별하지 못했습니다. 숫자 영역을 선택해 확인하거나 직접 입력하세요. 후보: ${chosen.value}"
                        return@forEach
                    }
                    if (absent[key]?.isChecked == true) input.error =
                        "사진에서 ${chosen.value}이 인식되었습니다. 표시 없음을 확인하세요."
                    else if (current.isBlank()) {
                        input.setText(chosen.value)
                        input.error =
                            if (decision.pressureAgreement != null) "압력 칸의 확대 인식이 일치했습니다. 사진과 값을 확인하세요."
                            else if (key in ocr.zeroCorrected) "사진의 0/8/9 형태를 보정했습니다. 값을 확인하세요."
                            else if (key in ocr.segmentReview) "0/8/9 세그먼트가 불명확합니다. 사진과 확인하세요." else null
                    } else if (key in ocr.zeroCorrected && (Cp14TableReader.number(current)
                            ?.let { it in ocr.beforeZeroCorrection[key].orEmpty() } == true)
                    ) {
                        input.setText(chosen.value)
                        input.error = "사진의 0/8/9 형태로 재보정했습니다. 값을 확인하세요."
                    } else if (Cp14TableReader.number(current) != chosen.value) {
                        input.error = "추가 사진의 값 ${chosen.value}과 기존 값이 다릅니다. 직접 확인하세요."
                    }
                }
                preview.invalidate()
                val count = fields.count { it.key in activeLabels() && it.value.text.isNotBlank() }
                status.text =
                    "이번 사진 ${ocr.candidates.size}개 인식 · 사진 ${photos.size}장 · " + count + "/" + activeLabels().size + "개 확인. 빈칸과 소수점을 확인하세요."
                if (ocr.notices.isNotEmpty()) status.append("\n" + ocr.notices.joinToString("\n"))
                if (BuildConfig.DEBUG) {
                    val diagnostic =
                        JSONObject().put("version", BuildConfig.VERSION_NAME).put("page", recordPage)
                            .put("headers", JSONArray(ocr.anchors.keys.toList()))
                            .put("candidates", JSONObject(ocr.candidates.mapValues { JSONArray(it.value.keys.toList()) }))
                            .put("localVotes", JSONObject(ocr.localVotes.mapValues { JSONObject(it.value) }))
                            .put("inputs", JSONObject(fields.filterKeys { it in activeLabels() }
                                .mapValues { it.value.text.toString() }))
                    withContext(Dispatchers.IO) {
                        runCatching { File(filesDir, "last-mono-ocr.json").writeText(diagnostic.toString(2)) }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!isDestroyed) status.text = "사진 인식 실패: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (!isDestroyed && !handedOff) setBusy(false)
            }
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
                    this@Cp14Activity,
                    Cp14TableReader.MACHINE
                )
                val value = Cp14TableReader.number(corrected ?: result.text) ?: return
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
            addView(TextView(this@Cp14Activity).apply {
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
                        CaptureNumberCorrection.learn(crop, expected, this, Cp14TableReader.MACHINE)
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
        val keys = entryKeys()
        val values =
            fields.filterKeys { it in keys }.mapValues { it.value.text.toString().trim() }
        val ocrValues = values.filterKeys { it in activeLabels() }
        val manualValues = values - ocrValues.keys
        val absentKeys = absent.filterValues { it.isChecked }.keys
        val storedManual: Map<String, String?>
        try {
            require(user.isNotBlank()) { "다시 로그인하세요." }
            require(machine.value != null) { "사출기 호기를 선택하세요." }
            require(round.value != null) { "차수를 선택하세요." }
            require(confirmed.isChecked) { "입력값 확인에 체크하세요." }
            Cp14TableReader.validatePage(ocrValues, recordPage)
            storedManual = if (pbMenu) PbJournal.values(recordPage, manualValues) else emptyMap()
        } catch (e: Exception) {
            saveStatus.text = e.message ?: "입력값을 확인하세요."
            message(saveStatus.text.toString()); return
        }
        if (capturedAt.isBlank()) capturedAt =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val storedFields = JSONObject().apply {
            (CaptureFieldValues.nullableValues(
                activeLabels().keys,
                ocrValues,
                absentKeys,
                Cp14TableReader::number
            ) + storedManual)
                .forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        val payload =
            JSONObject().put("HMI", Cp14Repository.hmiOf(menu)).put("MACHINE", machine.value).put("ROUND", round.value)
                .put("CAPTURED_AT", capturedAt).put("FIELDS", storedFields).toString()
        val data = if (recordPage == Cp14TableReader.LEGACY) payload else JSONObject(payload).put(
            "SCREEN_TYPE",
            recordPage
        ).toString()
        val draft = JSONObject().put("requestId", requestId).put("capturedAt", capturedAt)
            .put("values", JSONObject(values)).put("absent", JSONArray(absentKeys.toList()))
            .put("photos", photosJson()).put("page", recordPage).put("machineNo", machine.value).put("roundNo", round.value)
        val savedRequestId = requestId
        val savedCapturedAt = capturedAt
        val draftText = draft.toString()
        val pendingPrefs = getSharedPreferences("cp14_pending", MODE_PRIVATE)
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
                val task = CaptureSaveWorker.submit("Cp14:" + savedRequestId) { task ->
                    task.savedId.set(Cp14Repository.save(
                        menu,
                        savedRequestId,
                        user,
                        savedCapturedAt,
                        data
                    ) { task.phase.set(it) }.toString())
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
                CapturePendingArchive.removeSaved(
                    this@Cp14Activity,
                    "cp14_pending",
                    pendingKey,
                    savedRequestId
                )
                status.text = "$menuTitle 수치 등록 완료 · 요청 번호 ${work?.savedId?.get().orEmpty()}"
                saveStatus.text = status.text
                message("등록 완료")
                openHistory(work?.savedId?.get())
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                work?.allowRetry()
                saveStatus.text = "35초 안에 저장 응답을 받지 못했습니다.\n마지막 단계: ${
                    work?.phase?.get().orEmpty()
                }\n요청: $savedRequestId\n서버에서 처리 중이거나 이미 저장됐을 수 있습니다. 사진과 입력은 보관했습니다. 다시 등록하면 중복 저장되므로 저장자료 조회에서 먼저 확인하세요."
                status.text = saveStatus.text
                message("저장 응답 지연. 등록 버튼 아래의 마지막 단계를 확인하세요.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                saveStatus.text =
                    "등록 결과를 확인하지 못했습니다.\n${e.message ?: "DB 오류"}\n요청: $savedRequestId\n입력은 보관했습니다. 저장자료 조회에서 확인한 뒤 다시 등록하세요."
                status.text = saveStatus.text
                message("저장 확인 실패. 등록 버튼 아래의 오류 내용을 확인하세요.")
            } finally {
                timer?.cancel(); if (!isDestroyed) setBusy(false)
            }
        }
    }

    private fun openHistory(id: String? = null) {
        runCatching {
            startActivity(Intent(this, Cp14HistoryActivity::class.java).apply {
                putExtra("empNo", this@Cp14Activity.intent.getStringExtra("empNo"))
                putExtra("empName", this@Cp14Activity.intent.getStringExtra("empName"))
                if (menu.isNotEmpty()) putExtra("screenType", menu)
                if (id != null) putExtra("requestId", id)
            })
        }.onFailure { message("조회 화면을 열지 못했습니다. 저장자료 조회를 다시 누르세요.") }
    }

    private fun openPendingArchive() {
        val items = CapturePendingArchive.items(this, "cp14_pending", draftKey())
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
                            "cp14_pending",
                            draftKey(),
                            items[index]
                        )
                        reset(); restorePendingDraft(); updatePhotoSelector()
                        applyPage()
                        setBusy(false)
                        status.text = "보관된 요청을 열었습니다. 저장자료 조회에서 저장 여부를 확인한 뒤, 없을 때만 다시 등록하세요."
                    }.setNegativeButton("취소", null).show()
            }.setNegativeButton("닫기", null).show()
    }

    private fun draftKey() = "user." + intent.getStringExtra("empNo")
        .orEmpty() + (if (menu.isNotEmpty()) ".$menu" else "")

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
            getSharedPreferences("cp14_pending", MODE_PRIVATE).getString(draftKey(), null) ?: return
        runCatching {
            val draft = JSONObject(raw)
            restoring = true
            requestId = draft.getString("requestId"); capturedAt = draft.getString("capturedAt")
            recordPage = draft.optString("page", Cp14TableReader.LEGACY)
            draft.optString("machineNo").takeIf { it.isNotBlank() }?.let { machine.value = it }
            draft.optInt("roundNo", 0).takeIf { it > 0 }?.let { round.value = it }
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
        if (recordPage == Cp14TableReader.LEGACY) recordPage = Cp14TableReader.INJECTION
        restoring = true
        pageFields.values.flatMap { it.values }.forEach { it.setText(""); it.error = null }
        machine.clear(); round.clear()
        absent.values.forEach { it.isChecked = false }
        photos.clear(); pending = false; saved = false; capturedAt = ""; cameraUri =
            null; requestId = UUID.randomUUID().toString()
        confirmed.isChecked = false; preview.bitmap =
            null; preview.regions.clear(); updatePhotoSelector()
        status.text = "새 사진을 추가하세요."; restoring = false; applyPage(); setBusy(false)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("requestId", requestId); out.putString("capturedAt", capturedAt)
        out.putString("recordPage", recordPage)
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




