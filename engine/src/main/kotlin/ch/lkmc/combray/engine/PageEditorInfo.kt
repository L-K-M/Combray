package ch.lkmc.combray.engine

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** The focused page field's keyboard contract. Multiline comes from Servo, not the input type. */
internal fun configurePageEditor(info: EditorInfo, kind: String, multiline: Boolean) {
    val textType = when (kind) {
        "password" -> InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        "email" -> InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        "url" -> InputType.TYPE_TEXT_VARIATION_URI
        else -> InputType.TYPE_TEXT_VARIATION_NORMAL
    }
    info.inputType = when (kind) {
        "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        "tel" -> InputType.TYPE_CLASS_PHONE
        else -> InputType.TYPE_CLASS_TEXT or textType or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
    }
    var options = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    if (kind == "password") options = options or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    options = options or when {
        multiline -> EditorInfo.IME_ACTION_NONE
        kind == "search" -> EditorInfo.IME_ACTION_SEARCH
        else -> EditorInfo.IME_ACTION_DONE
    }
    info.imeOptions = options
}
