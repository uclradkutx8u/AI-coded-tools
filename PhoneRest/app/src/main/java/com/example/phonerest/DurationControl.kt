package com.example.phonerest

import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.SeekBar
import com.google.android.material.button.MaterialButton

/** Keeps a duration seek bar, numeric input, and preset buttons in sync. */
class DurationControl(
    private val seekBar: SeekBar,
    private val input: EditText,
    private val presetButtons: Map<Int, MaterialButton>,
    private val range: IntRange,
    initialValue: Int,
    private val persistValue: (Int) -> Unit,
    private val updatePresetButtons: (Map<Int, MaterialButton>, Int) -> Unit
) {
    private var isSynchronizing = false
    private var currentValue = initialValue

    init {
        require(!range.isEmpty()) { "Duration range must not be empty" }
        currentValue = currentValue.coerceIn(range)
        seekBar.max = range.last - range.first
        setValue(currentValue, persist = false)

        presetButtons.forEach { (minutes, button) ->
            button.setOnClickListener { setValue(minutes) }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setValue(progress + range.first)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                if (isSynchronizing) return
                text?.toString()?.toIntOrNull()?.takeIf { it in range }?.let(::setValue)
            }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            normalizeInput()
            input.clearFocus()
            true
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) normalizeInput()
        }
    }

    private fun setValue(minutes: Int, persist: Boolean = true) {
        currentValue = minutes.coerceIn(range)
        isSynchronizing = true
        seekBar.progress = currentValue - range.first
        val displayValue = currentValue.toString()
        if (input.text.toString() != displayValue) {
            input.setText(displayValue)
            input.setSelection(input.text.length)
        }
        updatePresetButtons(presetButtons, currentValue)
        isSynchronizing = false
        if (persist) persistValue(currentValue)
    }

    private fun normalizeInput() {
        setValue(input.text.toString().toIntOrNull() ?: currentValue)
    }
}
