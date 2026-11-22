package com.bsd.akProd

import android.app.Activity
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast

/**
 * 사출기 호기 (C01~C16) chooser for the CP registration screens. There is deliberately no default:
 * every new registration starts unselected so the operator must pick the machine before adding photos.
 */
class MachineNoPicker(private val activity: Activity, viewId: Int) {
    companion object {
        val numbers = (1..16).map { "C%02d".format(it) }
    }

    val spinner = Spinner(activity).apply {
        id = viewId
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("사출기 호기 선택") + numbers)
    }

    var value: String?
        get() = spinner.selectedItemPosition.takeIf { it > 0 }?.let { numbers[it - 1] }
        set(number) = spinner.setSelection(number?.let { numbers.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0)

    fun clear() {
        value = null
    }

    /** True when a machine is chosen; otherwise tells the operator and opens the list. */
    fun ensureSelected(): Boolean {
        if (value != null) return true
        Toast.makeText(activity, "사출기 호기를 먼저 선택하세요.", Toast.LENGTH_LONG).show()
        spinner.requestFocus(); spinner.performClick()
        return false
    }
}
