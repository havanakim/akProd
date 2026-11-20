package com.bsd.akProd

import android.app.Activity
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner

/**
 * 사출기 호기 (C01~C16) chooser for the CP registration screens. The last number used on this
 * device is offered again, since an operator usually registers one machine after another in turn.
 */
class MachineNoPicker(private val activity: Activity, viewId: Int) {
    companion object {
        val numbers = (1..16).map { "C%02d".format(it) }
        private const val PREFS = "cp_machine_no"
    }

    val spinner = Spinner(activity).apply {
        id = viewId
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("사출기 호기 선택") + numbers)
    }

    var value: String?
        get() = spinner.selectedItemPosition.takeIf { it > 0 }?.let { numbers[it - 1] }
        set(number) = spinner.setSelection(number?.let { numbers.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0)

    fun restoreLast() {
        value = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)
    }

    fun remember() {
        value?.let { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", it).apply() }
    }
}
