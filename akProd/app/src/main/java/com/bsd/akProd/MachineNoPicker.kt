package com.bsd.akProd

import android.app.Activity
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast

/**
 * 사출기 호기 (C01~C16) chooser for the CP registration screens. There is deliberately no default:
 * every new registration starts unselected so the operator must pick the machine before adding photos.
 */
class MachineNoPicker(private val activity: Activity, viewId: Int, prefix: String = "C") {
    companion object {
        /** CP 사출기 C01~C16. */
        val numbers = numbers("C")
        /** PB 사출기 P01~P16. */
        val pbNumbers = numbers("P")
        fun numbers(prefix: String) = (1..16).map { "$prefix%02d".format(it) }
    }

    private val numbers = numbers(prefix)

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

/**
 * 차수 (1차, 2차, 3차 …, increasing) chosen together with the 사출기 호기. Like the 호기 it has no
 * default, so every new registration must pick it before adding photos.
 */
class RoundNoPicker(private val activity: Activity, viewId: Int) {
    companion object {
        const val MAX = 30
        val rounds = (1..MAX).toList()
    }

    val spinner = Spinner(activity).apply {
        id = viewId
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("차수 선택") + rounds.map { "${it}차" })
    }

    var value: Int?
        get() = spinner.selectedItemPosition.takeIf { it > 0 }?.let { rounds[it - 1] }
        set(round) = spinner.setSelection(round?.takeIf { it in 1..MAX } ?: 0)

    fun clear() {
        value = null
    }

    fun ensureSelected(): Boolean {
        if (value != null) return true
        Toast.makeText(activity, "차수를 먼저 선택하세요.", Toast.LENGTH_LONG).show()
        spinner.requestFocus(); spinner.performClick()
        return false
    }
}
