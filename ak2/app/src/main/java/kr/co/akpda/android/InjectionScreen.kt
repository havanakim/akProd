package kr.co.akpda.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.akpda.shared.Injection
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
fun InjectionScreen(vm: PdaViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(Injection.labels.keys.first()) }
    var chooseItem by remember { mutableStateOf(false) }
    var replaceAction by remember { mutableStateOf<String?>(null) }
    val s = vm.ui
    val enabled = !s.busy && !reading && s.pending == null
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(recognizer) { onDispose { recognizer.close() } }

    fun read(uri: Uri) {
        reading = true
        scope.launch {
            try {
                val captured = Instant.now().toString()
                val bitmap = withContext(Dispatchers.IO) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0) { "사진 파일을 확인하세요." }
                    var sample = 1
                    while (maxOf(options.outWidth, options.outHeight) / sample > 2400) sample *= 2
                    val decoded = context.contentResolver.openInputStream(uri)!!.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("사진을 읽지 못했습니다.")
                    val exif = context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
                    val matrix = Matrix().apply {
                        postRotate(exif.rotationDegrees.toFloat())
                        if (exif.isFlipped) postScale(-1f, 1f)
                    }
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                }
                val text = suspendCancellableCoroutine<String> { continuation ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                        .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                }
                val photo = withContext(Dispatchers.IO) {
                    var current = bitmap
                    var data: ByteArray
                    do {
                        data = ByteArrayOutputStream().use { output ->
                            current.compress(Bitmap.CompressFormat.JPEG, 85, output); output.toByteArray()
                        }
                        if (data.size > 1_000_000) current = Bitmap.createScaledBitmap(current,
                            (current.width * .8f).toInt().coerceAtLeast(1), (current.height * .8f).toInt().coerceAtLeast(1), true)
                    } while (data.size > 1_000_000)
                    Base64.encodeToString(data, Base64.NO_WRAP)
                }
                vm.injectionPhoto(photo, captured, text)
            } catch (e: Exception) { vm.message(e.message ?: "사진 인식 실패. 다시 촬영하세요.") }
            finally { reading = false }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && cameraUri.isNotBlank()) read(Uri.parse(cameraUri))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) read(uri) }
    fun launchPhoto(action: String) {
        if (action == "gallery") gallery.launch("image/*")
        else runCatching {
            val dir = File(context.cacheDir, "injection").apply { mkdirs() }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".photos", File(dir, "capture.jpg"))
            cameraUri = uri.toString(); camera.launch(uri)
        }.onFailure { vm.message("카메라를 실행할 수 없습니다. 사진 선택을 이용하세요.") }
    }
    fun choosePhoto(action: String) {
        if (!s.fields["PHOTO"].isNullOrEmpty()) replaceAction = action else launchPhoto(action)
    }

    Text("사출기 화면 촬영 → 숫자를 항목에 배정 → 확인 후 등록")
    Text("화면 전체 또는 필요한 항목을 가까이 촬영하세요. 왼쪽·오른쪽 값의 의미와 단위는 장비 기준으로 확인하세요.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { choosePhoto("camera") }, enabled = enabled) { Text("사진 촬영") }
        OutlinedButton(onClick = { choosePhoto("gallery") }, enabled = enabled) { Text("사진 선택") }
    }
    if (reading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("사진에서 숫자를 읽는 중…") }
    val photo = s.fields["PHOTO"].orEmpty()
    val preview = remember(photo) { if (photo.isBlank()) null else runCatching {
        val bytes = Base64.decode(photo, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
    }.getOrNull() }
    preview?.let { Image(it, "등록할 사출기 사진", Modifier.fillMaxWidth().height(250.dp)) }
    Text("촬영/불러오기 시각: " + s.fields["CAPTURED_AT"].orEmpty())
    fun edit(key: String, value: String) { vm.edit(key, value); vm.edit("CONFIRMED", "N") }
    listOf("MACHINE" to "설비번호 (필수)", "MODEL" to "모델/금형명 표기", "ROBOT" to "Robot (ON / OFF, 선택)").forEach { (key, title) ->
        OutlinedTextField(s.fields[key].orEmpty(), { edit(key, it) }, label = { Text(title) }, enabled = enabled,
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (s.ocrText.isNotBlank()) {
        Text("OCR 결과 (자동 확정되지 않습니다)", style = MaterialTheme.typography.titleMedium)
        Text(s.ocrText)
    }
    OutlinedButton(onClick = { chooseItem = true }, enabled = enabled) { Text("배정할 항목: " + Injection.labels[selected]) }
    if (chooseItem) AlertDialog(onDismissRequest = { chooseItem = false }, title = { Text("항목 선택") }, text = {
        androidx.compose.foundation.lazy.LazyColumn {
            items(Injection.labels.size) { index ->
                val entry = Injection.labels.entries.elementAt(index)
                TextButton(onClick = { selected = entry.key; chooseItem = false }) { Text(entry.value) }
            }
        }
    }, confirmButton = { TextButton(onClick = { chooseItem = false }) { Text("닫기") } })
    if (s.ocrCandidates.isNotEmpty()) {
        Text("인식된 숫자를 누르면 선택한 항목에 입력됩니다. 소수점을 사진과 비교하세요.")
        s.ocrCandidates.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { value -> OutlinedButton(onClick = { edit(selected, value) }, enabled = enabled) { Text(value) } }
            }
        }
    } else if (photo.isNotBlank()) Text("숫자를 찾지 못했습니다. 재촬영하거나 사진을 보며 직접 입력하세요.")
    Injection.labels.forEach { (key, title) ->
        OutlinedTextField(s.fields[key].orEmpty(), { edit(key, it) }, label = { Text(title) },
            placeholder = { Text("미입력 또는 --- = 값 없음") }, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    }
    Row {
        Checkbox(s.fields["CONFIRMED"] == "Y", { vm.edit("CONFIRMED", if (it) "Y" else "N") }, enabled = enabled && photo.isNotBlank())
        Text("사진과 입력값을 비교하여 확인했습니다.", Modifier.padding(top = 12.dp))
    }
    Button(onClick = vm::saveInjection, enabled = enabled && photo.isNotBlank() && s.fields["CONFIRMED"] == "Y",
        modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("서버 등록") }
    if (replaceAction != null) AlertDialog(onDismissRequest = { replaceAction = null }, title = { Text("사진 교체") },
        text = { Text("새 사진을 읽으면 기존 측정값을 비웁니다. 사진 한 장을 기준으로 등록합니다.") },
        confirmButton = { TextButton(onClick = { val action = replaceAction!!; replaceAction = null; launchPhoto(action) }) { Text("계속") } },
        dismissButton = { TextButton(onClick = { replaceAction = null }) { Text("돌아가기") } })
}
