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
class Cp3HistoryActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private var busy=false
    private var from=""
    private var to=""
    private var page=0
    private var selectedId: String?=null
    private val user get()=intent.getStringExtra("empNo").orEmpty()
    private val screenType get()=if(intent.getStringExtra("screenType")==Cp3TableReader.METERING) Cp3TableReader.METERING else Cp3TableReader.INJECTION
    private val screenTitle get()=if(screenType==Cp3TableReader.METERING) "계량·흘림방지(color)" else "사출·보압(color)"
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val format=SimpleDateFormat("yyyy-MM-dd",Locale.US)
        to=state?.getString("to") ?: format.format(Date())
        from=state?.getString("from") ?: to
        page=state?.getInt("page") ?: 0
        selectedId=if(state!=null) state.getString("selectedId") else intent.getStringExtra("requestId")
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,20,20,20) }
        setContentView(ScrollView(this).apply { addView(body) })
        if(selectedId!=null) detail(selectedId!!) else list()
    }
    private fun text(value: String,size: Float=16f)=TextView(this).apply { text=value; textSize=size; body.addView(this) }
    private fun list() {
        body.setPadding(20,20,20,20)
        selectedId=null
        body.removeAllViews()
        text("$screenTitle · 내 저장자료",22f)
        text("${intent.getStringExtra("empName").orEmpty()} · $user")
        text("촬영/불러오기 현지시각 기준 · 한 페이지 20건")
        val (start,end)=HistoryFilterBar.dates(this,body,from,to)
        val buttons=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; body.addView(this) }
        val notice=text("")
        val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; body.addView(this) }
        HistoryFilterBar.buttons(this,buttons,listOf(
            "조회" to {
                from=start.text.toString().trim(); to=end.text.toString().trim(); page=0
                loadList(results,notice)
            },
            "선택 삭제" to { deleteSelected(results,notice) },
            (if(intent.getBooleanExtra("fromMenu",false)) "메뉴로" else "메뉴 가기") to { leave() }
        )) { !busy }
        loadList(results,notice)
    }
    private fun dateRange(): Pair<String,String> {
        require(from.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && to.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "날짜를 yyyy-MM-dd 형식으로 입력하세요." }
        val format=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply { isLenient=false }
        val start=requireNotNull(format.parse(from)); val end=requireNotNull(format.parse(to))
        require(!start.after(end)) { "시작일은 종료일 이후일 수 없습니다." }
        val next=Calendar.getInstance().apply { time=end; add(Calendar.DATE,1) }
        return from+"T00:00:00" to format.format(next.time)+"T00:00:00"
    }
    private fun loadList(results: LinearLayout,notice: TextView,done: String="") {
        val range=try { dateRange() } catch(e: Exception) { notice.text=e.message ?: "날짜를 확인하세요."; return }
        busy=true; results.removeAllViews(); notice.text="저장자료 조회 중…"; deletion.clear()
        lifecycleScope.launch {
            try {
                val rows=withContext(Dispatchers.IO) { Cp3Repository.search(user,range.first,range.second,page*20,screenType) }
                notice.text=done+"${page+1}페이지 · ${minOf(rows.size,20)}건"
                if(rows.isEmpty()) results.addView(TextView(this@Cp3HistoryActivity).apply { text="해당 기간에 저장된 자료가 없습니다." })
                rows.take(20).forEach { row ->
                    deletion.row(results,row.requestId,(row.machineNo?.let { "$it · " } ?: "")+row.capturedAt) { if(!busy) { selectedId=row.requestId; detail(row.requestId) } }
                }
                if(page>0) results.addView(Button(this@Cp3HistoryActivity).apply {
                    text="이전 페이지"; setOnClickListener { if(!busy) { page--; loadList(results,notice) } }
                })
                if(rows.size>20) results.addView(Button(this@Cp3HistoryActivity).apply {
                    text="다음 페이지"; setOnClickListener { if(!busy) { page++; loadList(results,notice) } }
                })
            } catch(e: Exception) { notice.text="조회 실패: "+(e.message ?: "DB 연결을 확인하세요.") }
            finally { busy=false }
        }
    }
    /** Opened from the menu: just close. Opened after registering: close the registration screen too and return to the main menu. */
    private fun leave() {
        if(!intent.getBooleanExtra("fromMenu",false)) startActivity(
            android.content.Intent(this,MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    private val deletion by lazy { HistoryDeleteSelection(this) }
    private fun deleteSelected(results: LinearLayout,notice: TextView) {
        deletion.confirm({ notice.text="삭제할 자료를 목록에서 선택하세요." }) { ids ->
            busy=true; notice.text="${ids.size}건 삭제 중…"
            lifecycleScope.launch {
                val message=try {
                    val removed=withContext(Dispatchers.IO) { Cp3Repository.delete(user,ids,screenType) }
                    "${removed}건을 삭제했습니다."
                } catch(e: Exception) { e.message ?: "삭제 실패: DB 연결을 확인하세요." }
                busy=false; loadList(results,notice,message+"\n")
            }
        }
    }
    private fun detail(id: String) {
        selectedId=id; body.removeAllViews()
        body.setPadding(dp(8),dp(2),dp(8),dp(2))
        text("$screenTitle · 저장 상세",20f).apply { includeFontPadding=false }
        val notice=text("저장된 값을 불러오는 중…",13f).apply { includeFontPadding=false }
        val dataView=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; body.addView(this) }
        detailNavigation()
        busy=true
        lifecycleScope.launch {
            try {
                val record=withContext(Dispatchers.IO) { Cp3Repository.get(user,id,screenType) }
                notice.text=(CaptureSaveRepository.machineNo(record.json)?.let { "호기 $it · " } ?: "") + (CaptureSaveRepository.roundNo(record.json)?.let { "${it}차 · " } ?: "")+"${record.summary.capturedAt} · 작업자 ${record.summary.user}"
                val payload=JSONObject(record.json)
                val fields=payload.getJSONObject("FIELDS")
                val recordPage=Cp3TableReader.pageOf(payload.optString("SCREEN_TYPE").ifBlank { null })
                check((if(recordPage==Cp3TableReader.METERING) Cp3TableReader.METERING else Cp3TableReader.INJECTION)==screenType) { "선택한 메뉴의 등록 자료가 아닙니다." }
                text(if(recordPage==Cp3TableReader.METERING) "계량·배압·흘림방지" else "사출·보압",14f)
                val notDisplayed=JSONObject(record.json).optJSONArray("NOT_DISPLAYED")
                val absent=if(notDisplayed==null) emptySet() else (0 until notDisplayed.length()).map { notDisplayed.getString(it) }.toSet()
                val table=TableLayout(this@Cp3HistoryActivity)
                val header=TableRow(this@Cp3HistoryActivity)
                (listOf("단계")+Cp3TableReader.rowsFor(recordPage).values.map { it.replace(" (","\n").removeSuffix(")") }).forEach {
                    header.addView(cell(it,true))
                }
                table.addView(header)
                Cp3TableReader.stagesFor(recordPage).forEachIndexed { index,stage ->
                    val values=TableRow(this@Cp3HistoryActivity).apply {
                        if(index%2==0) setBackgroundColor(0xFFF2F4F7.toInt())
                    }
                    values.addView(cell(Cp3TableReader.stageName(stage)))
                    Cp3TableReader.rowsFor(recordPage).forEach { (row,title) ->
                        val key="${stage}_$row"
                        // Saved as IP5 etc. since the rename; records saved before keep INJECT_5_PRESSURE etc.
                        val saved=Cp3TableReader.storedKey(key).takeIf { fields.has(it) } ?: key
                        val value=if(key !in Cp3TableReader.labelsFor(recordPage) || fields.isNull(saved) || key in absent) "—" else fields.optString(saved,"—")
                        // 사출·계량 cells show their saved name above the value (IP5, RP1 ...), as the mono history does.
                        val name=Cp3TableReader.storedKey(key).takeIf { it!=key && key in Cp3TableReader.labelsFor(recordPage) }
                        values.addView(cell(if(name!=null) "$name\n$value" else value))
                    }
                    table.addView(values)
                }
                dataView.addView(table)
            } catch(e: Exception) { notice.text="조회 실패: "+(e.message ?: "저장자료를 확인하세요.") }
            finally { busy=false }
        }
    }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    private fun cell(value: String,header: Boolean=false)=TextView(this).apply {
        text=value; textSize=if(header) 13f else 15f
        includeFontPadding=false; setLineSpacing(0f,1f)
        gravity=android.view.Gravity.CENTER; setPadding(dp(2),dp(2),dp(2),dp(2))
        minHeight=dp(24)
        if(header) { setTypeface(typeface,android.graphics.Typeface.BOLD); setBackgroundColor(0xFFE2E7ED.toInt()) }
        layoutParams=TableRow.LayoutParams(0,-2,1f)
    }
    private fun detailNavigation() {
        HistoryFilterBar.buttons(this,body,listOf(
            "목록으로" to { list() },
            (if(intent.getBooleanExtra("fromMenu",false)) "메뉴로" else "메뉴 가기") to { leave() }
        )) { !busy }
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("from",from); out.putString("to",to); out.putInt("page",page); out.putString("selectedId",selectedId)
        super.onSaveInstanceState(out)
    }
}



