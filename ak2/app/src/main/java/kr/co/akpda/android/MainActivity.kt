package kr.co.akpda.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.co.akpda.shared.Screens
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF287C86))) { PdaApp() } }
    }
}

@Composable
private fun PdaApp(vm: PdaViewModel = viewModel()) {
    val s = vm.ui
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(s.login?.token) {
        if (s.login != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.pollNotifications(); delay(10000)
            }
        }
    }
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(s.screen.isNotEmpty()) { if (!s.busy) vm.open("") }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "AK PDA",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("공장 " + vm.plant, Modifier.weight(1f))
                TextButton(onClick = { settings = true }, enabled = !s.busy) { Text("설정") }
                if (s.login != null) TextButton(
                    onClick = vm::logout,
                    enabled = !s.busy
                ) { Text("로그아웃") }
            }
            if (s.login?.demo == true) Text(
                "데모 모드 · 실제 DB 미변경",
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF3CD))
                    .padding(12.dp)
            )
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (s.message.isNotBlank()) Text(s.message, color = MaterialTheme.colorScheme.primary)
            if (s.pending != null) Card {
                Column(Modifier.padding(12.dp)) {
                    Text("처리 여부가 확정되지 않은 저장 요청이 있습니다.", style = MaterialTheme.typography.titleSmall)
                    Text(Screens.titles[s.pending.request.screen].orEmpty() + " / " + s.pending.request.operation)
                    Text("최초 요청과 같은 서버·계정으로 재확인하면 같은 요청 번호를 사용합니다.")
                    Button(onClick = vm::retryPending, enabled = !s.busy && s.login != null) {
                        Text(
                            "동일 저장 요청 재확인"
                        )
                    }
                }
            }
            if (s.login == null) LoginForm(vm)
            else if (s.screen.isEmpty()) {
                Text(s.login.displayName + " 님", style = MaterialTheme.typography.titleMedium)
                Screens.titles.filterKeys { it in s.login.menus }.forEach { (id, title) ->
                    Button(
                        onClick = { vm.open(id) },
                        enabled = !s.busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) { Text(title) }
                }
                if (s.login.menus.isEmpty()) Text("사용 가능한 업무 권한이 없습니다.")
            } else if (s.screen == "Injection_1") {
                Text("Injection_1", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { vm.open("") }, enabled = !s.busy) { Text("업무 메뉴로") }
                InjectionScreen(vm)
            } else {
                Text(
                    Screens.titles[s.screen].orEmpty(),
                    style = MaterialTheme.typography.headlineSmall
                )
                TextButton(onClick = { vm.open("") }, enabled = !s.busy) { Text("업무 메뉴로") }
                if (s.screen != "PDA2005M1") Row {
                    FilterChip(
                        selected = s.fields["MODE"] != "C",
                        onClick = { vm.edit("MODE", "R") },
                        enabled = !s.busy,
                        label = { Text("등록") })
                    Spacer(Modifier.width(12.dp))
                    FilterChip(
                        selected = s.fields["MODE"] == "C",
                        onClick = { vm.edit("MODE", "C") },
                        enabled = !s.busy,
                        label = { Text("취소") })
                }
                if (s.screen == "PDA2002M1") {
                    Row {
                        FilterChip(
                            selected = s.fields["RESOURCE_GB"] == "T",
                            onClick = { vm.edit("RESOURCE_GB", "T") },
                            enabled = !s.busy,
                            label = { Text("탱크") })
                        Spacer(Modifier.width(12.dp))
                        FilterChip(
                            selected = s.fields["RESOURCE_GB"] == "R",
                            onClick = { vm.edit("RESOURCE_GB", "R") },
                            enabled = !s.busy,
                            label = { Text("설비") })
                    }
                }
                val optionKey = when (s.screen) {
                    "PDA2002M1" -> "RESOURCE_CD"; "PDA2005M1" -> "WC_CD"; else -> "CHILD_ITEM_CD"
                }
                if (s.screen in setOf("PDA2002M1", "PDA2003M1", "PDA2004M1", "PDA2005M1")) {
                    Picker(
                        if (s.screen == "PDA2005M1") "작업장" else if (s.screen == "PDA2002M1") "투입 대상" else "분쇄재 품목",
                        s.fields[optionKey].orEmpty(),
                        s.options.map { it["CODE"].orEmpty() to it["NAME"].orEmpty() },
                        !s.busy
                    ) { vm.edit(optionKey, it) }
                    TextButton(
                        onClick = { vm.request("options") },
                        enabled = !s.busy
                    ) { Text("목록 새로고침") }
                }
                if (s.screen == "PDA2005M1") {
                    Field(
                        "작업지시일 (yyyy-MM-dd)",
                        s.fields["ORDER_DT"].orEmpty(),
                        !s.busy,
                        { vm.edit("ORDER_DT", it) })
                    OutlinedButton(
                        onClick = { vm.request("orders") },
                        enabled = !s.busy
                    ) { Text("작업지시 조회") }
                    Picker(
                        "작업지시",
                        s.fields["PRODT_ORDER_NO"].orEmpty(),
                        s.orders.map { it["PRODT_ORDER_NO"].orEmpty() to (it["PRODT_ORDER_NO"].orEmpty() + " · " + it["ITEM_NM"].orEmpty() + " · " + it["PRODT_ORDER_QTY"].orEmpty()) },
                        !s.busy
                    ) { vm.edit("PRODT_ORDER_NO", it) }
                }
                if (s.screen !in setOf("PDA2003M1", "PDA2004M1")) {
                    Field(
                        if (s.screen == "PDA2006M1") "투입 LOT 바코드" else if (s.screen == "PDA2005M1") "LOT 바코드" else "BOX 바코드",
                        s.fields["SCAN"].orEmpty(),
                        !s.busy,
                        { vm.edit("SCAN", it) },
                        onEnter = { vm.request("search") },
                        autoFocus = !(s.screen == "PDA2006M1" && s.first.isNotEmpty())
                    )
                }
                Button(
                    onClick = { vm.request("search") },
                    enabled = !s.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (s.screen == "PDA2001M1") "BOX 조회·추가" else "조회") }
                Details(s.first)
                if (s.screen == "PDA2001M1") {
                    Text("스캔 BOX " + s.boxes.size + "개")
                    s.boxes.forEach { box ->
                        Row {
                            Text(box["BX_ID"].orEmpty(), Modifier.weight(1f))
                            TextButton(
                                onClick = { vm.removeBox(box["BX_ID"].orEmpty()) },
                                enabled = !s.busy
                            ) { Text("제거") }
                        }
                    }
                }
                if (s.screen in setOf(
                        "PDA2002M1",
                        "PDA2003M1",
                        "PDA2004M1"
                    ) && !(s.screen == "PDA2002M1" && s.fields["MODE"] == "C")
                ) {
                    Field(
                        "처리 수량",
                        s.fields["QTY"].orEmpty(),
                        !s.busy,
                        { vm.edit("QTY", it) },
                        decimal = true
                    )
                }
                if (s.screen == "PDA2006M1") {
                    Field(
                        "생산 LOT 바코드",
                        s.fields["SCAN2"].orEmpty(),
                        !s.busy,
                        { vm.edit("SCAN2", it) },
                        onEnter = { vm.request("secondary") },
                        autoFocus = s.first.isNotEmpty()
                    )
                    OutlinedButton(
                        onClick = { vm.request("secondary") },
                        enabled = !s.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("생산 LOT 조회") }
                    Details(s.second)
                }
                if (s.screen == "PDA2004M1" && s.fields["MODE"] == "C") Text("이 업무의 취소는 서버에서 확인된 취소 계약을 설정한 후 사용할 수 있습니다.")
                Button(
                    onClick = { confirm = true },
                    enabled = !s.busy && s.pending == null && (s.first.isNotEmpty() || s.boxes.isNotEmpty()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(if (s.fields["MODE"] == "C") "취소 처리" else "저장")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("업무 처리 확인") },
        text = { Text(Screens.titles[s.screen].orEmpty() + if (s.fields["MODE"] == "C") " 취소를 실행할까요?" else " 내용을 저장할까요?") },
        confirmButton = {
            TextButton(onClick = {
                confirm = false; vm.request(if (s.fields["MODE"] == "C") "cancel" else "save")
            }) { Text("실행") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("돌아가기") } })
    if (settings) {
        var url by remember { mutableStateOf(vm.endpoint) };
        var plant by remember { mutableStateOf(vm.plant) }
        AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("접속 설정") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("실제 Android PDA는 PC의 사내 서버 주소를 입력하세요. 에뮬레이터는 10.0.2.2를 사용합니다.")
                    Field("API 서버 주소", url, true, { url = it }); Field(
                    "공장 코드",
                    plant,
                    true,
                    { plant = it })
                    Text("DB 계정은 앱에 저장하지 않습니다. 비밀번호 변경은 기존 관리 화면을 이용하세요.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.settings(url, plant); settings = false
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("닫기") } })
    }
}

@Composable
private fun LoginForm(vm: PdaViewModel) {
    var id by remember { mutableStateOf(vm.userId) }
    var password by remember { mutableStateOf("") }
    Text("작업자 로그인", style = MaterialTheme.typography.titleLarge)
    Text("데모 서버: 사번 demo / 비밀번호 demo")
    Field("사번", id, !vm.ui.busy, { id = it })
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("비밀번호") },
        singleLine = true,
        enabled = !vm.ui.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = { vm.login(id, password); password = "" },
        enabled = !vm.ui.busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) { Text("로그인") }
}

@Composable
private fun Field(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    decimal: Boolean = false,
    onEnter: (() -> Unit)? = null,
    autoFocus: Boolean = false
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus, enabled) { if (autoFocus && enabled) focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (onEnter != null) ImeAction.Search else ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onSearch = { onEnter?.invoke() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (onEnter != null && enabled && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    if (e.type == KeyEventType.KeyUp) onEnter(); true
                } else false
            })
}

@Composable
private fun Picker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onPick: (String) -> Unit
) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { show = true },
        enabled = enabled && options.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label + ": " + (options.firstOrNull { it.first == value }?.second ?: "선택하세요"))
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(label) }, text = {
        Column(Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())) {
            options.forEach { (id, text) ->
                TextButton(
                    onClick = { onPick(id); show = false },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(text) }
            }
        }
    }, confirmButton = { TextButton(onClick = { show = false }) { Text("닫기") } })
}

private val labels = mapOf(
    "BX_ID" to "BOX",
    "ITEM_CD" to "품목 코드",
    "ITEM_NM" to "품목명",
    "INV_QTY" to "재고량",
    "RCPT_QTY" to "입고량",
    "USE_QTY" to "사용량",
    "QTY" to "수량",
    "BASIC_UNIT" to "단위",
    "MAX_WEIGHT" to "최대량",
    "UNIT" to "단위",
    "RESOURCE_CD" to "설비 코드",
    "RESOURCE_NM" to "설비명",
    "LOT_NO" to "LOT",
    "LOT_QTY" to "LOT 수량",
    "PO_NO" to "발주서",
    "INVOICE_NUMBER" to "INVOICE",
    "INPUT_LOT_NO" to "투입 LOT",
    "INPUT_ITEM_CD" to "투입 품목",
    "INPUT_ITEM_NM" to "투입 품목명",
    "INPUT_QTY" to "투입 수량",
    "PRODT_LOT_NO" to "생산 LOT",
    "PRODT_ITEM_CD" to "생산 품목",
    "PRODT_ITEM_NM" to "생산 품목명",
    "PRODT_QTY" to "생산 수량",
    "PACKING_QTY" to "포장 수량"
)

@Composable
private fun Details(row: Map<String, String>) {
    if (row.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            row.filterKeys { it in labels }
                .forEach { (key, value) -> Text(labels[key].orEmpty() + ": " + value) }
        }
    }
}
