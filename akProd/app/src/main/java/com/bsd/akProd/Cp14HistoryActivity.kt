package com.bsd.akProd

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Reads the committed database record, rather than redisplaying unsaved form values. */
class Cp14HistoryActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private var busy = false
    private var from = ""
    private var to = ""
    private var page = 0
    private var selectedId: String? = null
    private val screenType get() = if (intent.getStringExtra("screenType") == Cp14TableReader.METERING) Cp14TableReader.METERING else ""
    private val user get() = intent.getStringExtra("empNo").orEmpty()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        to = state?.getString("to") ?: format.format(Date())
        from = state?.getString("from") ?: format.format(
            Calendar.getInstance().apply { add(Calendar.DATE, -7) }.time
        )
        page = state?.getInt("page") ?: 0
        selectedId =
            if (state != null) state.getString("selectedId") else intent.getStringExtra("requestId")
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(
            20,
            20,
            20,
            20
        )
        }
        setContentView(ScrollView(this).apply { addView(body) })
        if (selectedId != null) detail(selectedId!!) else list()
    }

    private fun text(value: String, size: Float = 16f) =
        TextView(this).apply { text = value; textSize = size; body.addView(this) }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title; isAllCaps =
        false; body.addView(this); setOnClickListener { if (!busy) action() }
    }

    private fun list() {
        body.setPadding(20, 20, 20, 20)
        selectedId = null
        body.removeAllViews()
        text(if (screenType.isNotEmpty()) "계량/배압(m) · 내 저장자료" else "CP_mono · 내 저장자료", 22f)
        text("${intent.getStringExtra("empName").orEmpty()} · $user")
        text("촬영/불러오기 현지시각 기준 · 한 페이지 20건")
        text("시작일 (yyyy-MM-dd)")
        val start = EditText(this).apply { setSingleLine(); setText(from); body.addView(this) }
        text("종료일 (yyyy-MM-dd)")
        val end = EditText(this).apply { setSingleLine(); setText(to); body.addView(this) }
        val notice = text("")
        val results =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        button("조회") {
            from = start.text.toString().trim(); to = end.text.toString().trim(); page = 0
            loadList(results, notice)
        }
        button("선택 삭제") { deleteSelected(results, notice) }
        button(if (intent.getBooleanExtra("fromMenu", false)) "메뉴로" else "등록 화면으로") { finish() }
        loadList(results, notice)
    }

    private fun dateRange(): Pair<String, String> {
        require(from.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && to.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "날짜를 yyyy-MM-dd 형식으로 입력하세요." }
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val start = requireNotNull(format.parse(from));
        val end = requireNotNull(format.parse(to))
        require(!start.after(end)) { "시작일은 종료일 이후일 수 없습니다." }
        val next = Calendar.getInstance().apply { time = end; add(Calendar.DATE, 1) }
        return from + "T00:00:00" to format.format(next.time) + "T00:00:00"
    }

    private val deletion by lazy { HistoryDeleteSelection(this) }

    private fun deleteSelected(results: LinearLayout, notice: TextView) {
        deletion.confirm({ notice.text = "삭제할 자료를 목록에서 선택하세요." }) { ids ->
            busy = true; notice.text = "${ids.size}건 삭제 중…"
            lifecycleScope.launch {
                val message = try {
                    val removed = withContext(Dispatchers.IO) { Cp14Repository.delete(user, ids) }
                    "${removed}건을 삭제했습니다."
                } catch (e: Exception) {
                    e.message ?: "삭제 실패: DB 연결을 확인하세요."
                }
                busy = false; loadList(results, notice, message + "\n")
            }
        }
    }

    private fun loadList(results: LinearLayout, notice: TextView, done: String = "") {
        val range = try {
            dateRange()
        } catch (e: Exception) {
            notice.text = e.message ?: "날짜를 확인하세요."; return
        }
        busy = true; results.removeAllViews(); notice.text = "저장자료 조회 중…"; deletion.clear()
        lifecycleScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    Cp14Repository.search(
                        user,
                        range.first,
                        range.second,
                        page * 20,
                        screenType
                    )
                }
                notice.text = done + "${page + 1}페이지 · ${minOf(rows.size, 20)}건"
                if (rows.isEmpty()) results.addView(TextView(this@Cp14HistoryActivity).apply {
                    text = "해당 기간에 저장된 자료가 없습니다."
                })
                rows.take(20).forEach { row ->
                    deletion.row(results, row.requestId, (row.machineNo?.let { "$it · " } ?: "") + "${row.capturedAt}\n${Cp14TableReader.pageTitle(row.screenType)}") {
                        if (!busy) {
                            selectedId = row.requestId; detail(row.requestId)
                        }
                    }
                }
                if (page > 0) results.addView(Button(this@Cp14HistoryActivity).apply {
                    text = "이전 페이지"; setOnClickListener {
                    if (!busy) {
                        page--; loadList(results, notice)
                    }
                }
                })
                if (rows.size > 20) results.addView(Button(this@Cp14HistoryActivity).apply {
                    text = "다음 페이지"; setOnClickListener {
                    if (!busy) {
                        page++; loadList(results, notice)
                    }
                }
                })
            } catch (e: Exception) {
                notice.text = "조회 실패: " + (e.message ?: "DB 연결을 확인하세요.")
            } finally {
                busy = false
            }
        }
    }

    private fun detail(id: String) {
        selectedId = id; body.removeAllViews()
        body.setPadding(dp(8), dp(2), dp(8), dp(2))
        text("CP_mono · 저장 상세", 20f).apply { includeFontPadding = false }
        val notice = text("저장된 값을 불러오는 중…", 13f).apply { includeFontPadding = false }
        val dataView =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        detailNavigation()
        busy = true
        lifecycleScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { Cp14Repository.get(user, id) }
                // The 계량/배압(m) menu holds both of its screens (S5~S1 and MIRACON-I).
                val sameMenu = record.summary.screenType == screenType ||
                    screenType == Cp14TableReader.METERING && record.summary.screenType == Cp14TableReader.MIRACON
                check(screenType.isEmpty() || sameMenu) { "이 메뉴의 저장자료가 아닙니다." }
                notice.text =
                    Cp14TableReader.pageTitle(record.summary.screenType) + "\n" +
                        (CaptureSaveRepository.machineNo(record.json)?.let { "호기 $it · " } ?: "") +
                        "${record.summary.capturedAt} · 작업자 ${record.summary.user}"
                val fields = JSONObject(record.json).getJSONObject("FIELDS")
                val table = TableLayout(this@Cp14HistoryActivity)
                val header = TableRow(this@Cp14HistoryActivity)
                header.addView(cell("항목 / 단위", true, 2f))
                val miracon = record.summary.screenType == Cp14TableReader.MIRACON
                for (stage in 5 downTo 1) header.addView(cell(if (miracon) "" else "${stage}단", true))
                table.addView(header)
                Cp14TableReader.groups.filterKeys { it in Cp14TableReader.prefixes(record.summary.screenType) }.entries.forEachIndexed { index, (prefix, title) ->
                    val values = TableRow(this@Cp14HistoryActivity).apply {
                        if (index % 2 == 0) setBackgroundColor(0xFFF2F4F7.toInt())
                    }
                    val label = prefix + " · " + title.substringBefore(" (")
                        .replace(" ", "") + "\n" + title.substringAfter(" (", "").removeSuffix(")")
                    values.addView(cell(if (miracon) title.substringBefore(" (").replace(" ", "") + "\n" +
                        title.substringAfter(" (", "").removeSuffix(")") else label, false, 2f))
                    if (miracon) {
                        // MIRACON-I columns are named as printed (BP1, R1, SB.SPEED, S6, SB ...).
                        val keys = Cp14TableReader.labels.keys.filter { Cp14TableReader.prefix(it) == prefix }
                        keys.forEach { key ->
                            values.addView(cell(Cp14TableReader.printedName(key) + "\n" +
                                if (fields.isNull(key)) "—" else fields.optString(key, "—")))
                        }
                        repeat(5 - keys.size) { values.addView(cell("")) }
                    } else if (prefix == "S0") {
                        values.addView(
                            cell(
                                if (fields.isNull("S0")) "—" else fields.optString(
                                    "S0",
                                    "—"
                                )
                            ).apply {
                                layoutParams = TableRow.LayoutParams().apply { span = 5 }
                            })
                    } else for (stage in 5 downTo 1) {
                        val key = "$prefix$stage"
                        values.addView(
                            cell(
                                if (fields.isNull(key)) "—" else fields.optString(
                                    key,
                                    "—"
                                )
                            )
                        )
                    }
                    table.addView(values)
                }
                dataView.addView(table)
            } catch (e: Exception) {
                notice.text = "조회 실패: " + (e.message ?: "저장자료를 확인하세요.")
            } finally {
                busy = false
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun cell(value: String, header: Boolean = false, weight: Float = 1f) =
        TextView(this).apply {
            text = value; textSize = if (header) 13f else 15f
            includeFontPadding = false; setLineSpacing(0f, 1f)
            gravity = android.view.Gravity.CENTER; setPadding(dp(2), dp(2), dp(2), dp(2))
            minHeight = dp(24)
            if (header) {
                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                ); setBackgroundColor(0xFFE2E7ED.toInt())
            }
            layoutParams = TableRow.LayoutParams(0, -2, weight)
        }

    private fun detailNavigation() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "목록으로" to { list() },
            (if (intent.getBooleanExtra(
                    "fromMenu",
                    false
                )
            ) "메뉴로" else "등록 화면으로") to { finish() }).forEach { (title, action) ->
            row.addView(Button(this).apply {
                text = title; isAllCaps = false; setOnClickListener { if (!busy) action() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(row)
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("from", from); out.putString("to", to); out.putInt(
            "page",
            page
        ); out.putString("selectedId", selectedId)
        super.onSaveInstanceState(out)
    }
}

