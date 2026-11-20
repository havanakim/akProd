package com.bsd.akProd

import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Check boxes on the CP history list and the confirmed "선택 삭제" action, shared by the color and
 * mono history screens. Deleting is permanent; the dialog says so before anything is sent.
 */
class HistoryDeleteSelection(private val activity: AppCompatActivity) {
    private val chosen = linkedSetOf<String>()
    val selected: Set<String> get() = chosen

    fun clear() = chosen.clear()

    /** One list row: a check box for deletion and the existing button that opens the record. */
    fun row(container: LinearLayout, id: String, title: String, open: () -> Unit) {
        val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        line.addView(CheckBox(activity).apply {
            isChecked = id in chosen
            contentDescription = "$title 삭제 선택"
            setOnCheckedChangeListener { _, checked -> if (checked) chosen.add(id) else chosen.remove(id) }
        })
        line.addView(Button(activity).apply {
            text = title; isAllCaps = false; setOnClickListener { open() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(line)
    }

    /** Asks for confirmation, then runs [delete] with the selected request ids. */
    fun confirm(onEmpty: () -> Unit, delete: (List<String>) -> Unit) {
        if (chosen.isEmpty()) { onEmpty(); return }
        val ids = chosen.toList()
        AlertDialog.Builder(activity).setTitle("선택 자료 삭제")
            .setMessage("선택한 ${ids.size}건을 서버에서 영구 삭제합니다.\n저장된 값이 모두 지워지며 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ -> delete(ids) }
            .setNegativeButton("취소", null)
            .show()
    }
}
