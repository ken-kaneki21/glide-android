package com.gharaudio.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/*
 * K1 + K2: Glide Zune keyboard (Windows Phone style).
 * - One custom View drawn on a Canvas; it redraws only when touched (no animation loop).
 * - Flat dark keys, lowercase Zegoe letters, magenta press + letter preview, accents on hold,
 *   symbols pages, shift / caps lock, auto-capitals, double-space period, held backspace repeats.
 * - Suggestions from a bundled word list plus words you type, learned ON THE PHONE ONLY.
 *   Nothing is learned or suggested in password / no-suggestion fields. The keyboard never uses the network.
 * - Hold space to switch to another keyboard (LG keyboard stays installed as a fallback).
 */

private const val SHIFT = -1
private const val DEL = -2
private const val MODE = -3      // abc <-> 123
private const val ENTER = -4
private const val PAGE = -5      // 123 <-> more symbols
private const val EMOJI = -6     // K3: emoji pages
private const val EPREV = -7
private const val ENEXT = -8
private const val EMOJI_CHAR = 0x1F600 // any positive code: the key's label is what gets typed

// K3: four pages of common emoji (drawn by the phone's own emoji font)
private val EMOJI_PAGES = listOf(
    "😀 😂 🤣 😊 😍 😘 😎 🤩 🙂 😉 😅 😇 🤔 😐 🙄 😏 😴 😌 😜 🤗 🤭 😬 😢 😭",
    "😡 😱 😳 🥺 😤 🤯 🥳 😷 🤒 🤝 🙏 👍 👎 👏 🙌 💪 👌 ✌️ 🤞 👋 🤙 👀 🫶 💯",
    "❤️ 🧡 💛 💚 💙 💜 🖤 💔 ✨ 🔥 ⭐ 🌙 ☀️ 🌧️ ⚡ 🌈 🎉 🎂 🎁 🎵 🎶 🎧 🎸 🎤",
    "🏏 ⚽ 🏆 🍕 🍔 ☕ 🍺 🍫 🚗 ✈️ 🏠 💼 📱 💻 📷 📍 ⏰ ✅ ❌ ❓ ❗ 💤 👻 🙈"
).map { it.split(' ') }

private class Key(val label: String, val code: Int, val weight: Float = 1f, val alts: String = "") {
    val rect = RectF()
}

/** Word list (bundled) + learned words (private file). Prefix lookups on a sorted array. */
private class GlideWords(private val context: Context) {
    @Volatile private var words: Array<String> = emptyArray()
    @Volatile private var lower: Array<String> = emptyArray()
    @Volatile private var freq: LongArray = LongArray(0)
    private val learned = HashMap<String, Int>()
    private val learnedFile get() = File(context.filesDir, "glide_kb_words.txt")
    @Volatile var dirty = false

    fun load() = thread(name = "GlideKbWords", isDaemon = true) {
        try {
            val pairs = context.assets.open("glide_words.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { l -> val t = l.indexOf('\t'); if (t <= 0) null else l.substring(0, t) to (l.substring(t + 1).toLongOrNull() ?: 1L) }.toList()
            }.sortedBy { it.first.lowercase(Locale.ROOT) }
            words = Array(pairs.size) { pairs[it].first }
            lower = Array(pairs.size) { pairs[it].first.lowercase(Locale.ROOT) }
            freq = LongArray(pairs.size) { pairs[it].second }
        } catch (_: Exception) { }
        try {
            if (learnedFile.isFile) synchronized(learned) {
                learnedFile.readLines().forEach { l ->
                    val t = l.indexOf('\t'); if (t > 0) learned[l.substring(0, t)] = l.substring(t + 1).toIntOrNull() ?: 1
                }
            }
        } catch (_: Exception) { }
    }

    fun learn(word: String) {
        if (word.length < 2 || word.length > 24 || !word.all { it.isLetter() || it == '\'' }) return
        synchronized(learned) {
            learned[word] = (learned[word] ?: 0) + 1
            if (learned.size > 5000) learned.entries.sortedBy { it.value }.take(500).forEach { learned.remove(it.key) }
        }
        dirty = true
    }

    fun save() {
        if (!dirty) return
        dirty = false
        val snapshot = synchronized(learned) { learned.entries.joinToString("\n") { "${it.key}\t${it.value}" } }
        thread(isDaemon = true) { try { learnedFile.writeText(snapshot) } catch (_: Exception) { } }
    }

    fun isKnown(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT)
        val i = java.util.Arrays.binarySearch(lower, w)
        return i >= 0 || synchronized(learned) { learned.keys.any { it.equals(word, true) } }
    }

    private fun freqOf(w: String): Long {
        val lw = w.lowercase(Locale.ROOT)
        val i = java.util.Arrays.binarySearch(lower, lw)
        val learnedCount = synchronized(learned) { learned[lw] ?: learned[lw.replaceFirstChar { it.titlecase(Locale.ROOT) }] ?: 0 }
        return (if (i >= 0) freq[i] else 0L) + if (learnedCount > 0) 1_000_000_000L + learnedCount else 0L
    }

    /** K3 autocorrect: the most common known word one edit away (swap, delete, insert, replace). Null if none. */
    fun correction(word: String): String? {
        val w = word.lowercase(Locale.ROOT)
        if (w.length < 3 || isKnown(w) || lower.isEmpty()) return null
        val abc = "abcdefghijklmnopqrstuvwxyz'"
        val cands = HashSet<String>()
        for (i in 0..w.length) {
            if (i < w.length) cands += w.removeRange(i, i + 1)
            if (i < w.length - 1) cands += w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2)
            for (c in abc) {
                if (i < w.length) cands += w.substring(0, i) + c + w.substring(i + 1)
                cands += w.substring(0, i) + c + w.substring(i)
            }
        }
        var best: String? = null; var bestF = 0L
        for (c in cands) {
            if (c.length < 2) continue
            val f = freqOf(c)
            if (f > bestF) { bestF = f; best = c }
        }
        // only confident fixes: common words (or your own)
        return best?.takeIf { bestF >= 50 }
    }

    /** K3 swipe typing: words that start at the first key, end at the last, and whose letters appear in order
     *  along the path. Ranked by frequency, penalised for skipping many path letters. */
    fun swipe(path: String, max: Int = 3): List<String> {
        if (path.length < 2) return emptyList()
        val first = path.first(); val last = path.last()
        val lw = lower
        var i = java.util.Arrays.binarySearch(lw, first.toString()).let { if (it < 0) -it - 1 else it }
        val scored = ArrayList<Pair<String, Double>>()
        fun fits(word: String): Int { // returns letters used, -1 if not a subsequence
            var p = 0; var used = 0; var prev = ' '
            for (ch in word) {
                if (ch == prev) continue // doubled letters ("hello") are one key on the path
                prev = ch
                while (p < path.length && path[p] != ch) p++
                if (p >= path.length) return -1
                p++; used++
            }
            return used
        }
        var scanned = 0
        while (i < lw.size && lw[i].isNotEmpty() && lw[i][0] == first && scanned < 40000) {
            val word = lw[i]; scanned++
            if (word.length >= 2 && word.last() == last && word.all { it in 'a'..'z' || it == '\'' }) {
                val used = fits(word)
                if (used > 0) {
                    val skipped = path.length - used
                    scored += words[i] to (kotlin.math.ln(freq[i].toDouble() + 2.0) - 0.35 * skipped)
                }
            }
            i++
        }
        synchronized(learned) {
            learned.keys.forEach { w -> val lwd = w.lowercase(Locale.ROOT)
                if (lwd.length >= 2 && lwd[0] == first && lwd.last() == last) { val used = fits(lwd); if (used > 0) scored += w to (25.0 - 0.35 * (path.length - used)) } }
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinctBy { it.lowercase(Locale.ROOT) }.take(max)
    }

    /** Best completions for a prefix, most frequent first. Your own words rank above the list. */
    fun suggest(prefix: String, max: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase(Locale.ROOT)
        val scored = ArrayList<Pair<String, Long>>()
        synchronized(learned) {
            learned.forEach { (w, c) -> if (w.lowercase(Locale.ROOT).startsWith(p)) scored += w to (1_000_000_000L + c) }
        }
        val lw = lower
        var i = java.util.Arrays.binarySearch(lw, p).let { if (it < 0) -it - 1 else it }
        var scanned = 0
        while (i < lw.size && lw[i].startsWith(p) && scanned < 5000) { scored += words[i] to freq[i]; i++; scanned++ }
        return scored.sortedByDescending { it.second }.map { it.first }.distinctBy { it.lowercase(Locale.ROOT) }.take(max)
    }
}

class GlideKeyboardService : InputMethodService() {
    private lateinit var words: GlideWords
    private var view: GlideKeyboardView? = null
    private val composing = StringBuilder()
    private var noLearning = false
    private var lastSpaceAt = 0L

    override fun onCreate() {
        super.onCreate()
        words = GlideWords(this).also { it.load() }
    }

    override fun onCreateInputView(): View = GlideKeyboardView(this).also { view = it }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        lastCorrection = null
        autocorrect = getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getBoolean("kb_autocorrect", true)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        noLearning = password || (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            cls != InputType.TYPE_CLASS_TEXT || variation == InputType.TYPE_TEXT_VARIATION_URI ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        view?.apply {
            numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
            enterLabel = enterLabelFor(info)
            showSuggestions = !noLearning
            accent = GlideMetroStyle.accentArgb(this@GlideKeyboardService)
            swipeOn = getSharedPreferences("ghar_ui", Context.MODE_PRIVATE).getBoolean("kb_swipe", true)
            reset()
        }
        updateShift()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        commitComposing(learn = true)
        words.save()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The user moved the cursor away from the word being typed: stop composing it.
        if (composing.isNotEmpty() && (candidatesStart == -1 || newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composing.setLength(0)
            currentInputConnection?.finishComposingText()
            updateSuggestions()
        }
    }

    private fun enterLabelFor(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "go"; EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_SEND -> "send"; EditorInfo.IME_ACTION_NEXT -> "next"
            EditorInfo.IME_ACTION_DONE -> "done"; else -> "↵"
        }
    }

    // ---- input ----
    internal fun onChar(c: String) {
        val ic = currentInputConnection ?: return
        lastCorrection = null
        val isWordChar = c.length == 1 && (c[0].isLetterOrDigit() || c == "'")
        if (isWordChar && !noLearning) {
            composing.append(c)
            ic.setComposingText(composing, 1)
        } else {
            if (c.length == 1 && !isWordChar) commitComposing(learn = true) else commitComposing(learn = false)
            // Typing punctuation after an auto-space ("word ") puts it straight after the word.
            if (c in listOf(".", ",", "?", "!", ";", ":")) {
                val before = ic.getTextBeforeCursor(2, 0)
                if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) ic.deleteSurroundingText(1, 0)
            }
            ic.commitText(c, 1)
        }
        updateShift(); updateSuggestions()
    }

    internal fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        if (composing.isEmpty() && now - lastSpaceAt < 450) {
            // Double space -> ". " (Windows Phone behaviour)
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); lastSpaceAt = 0L
                updateShift(); updateSuggestions(); return
            }
        }
        // K3 autocorrect: replace an unknown word with the most common word one edit away (backspace undoes it).
        // F1: the word and its space go in as ONE commit inside a batch edit — committing them separately could
        // drop the space in some text fields ("such" + "such" became "suchsuch").
        val typed = composing.toString()
        val fix = if (autocorrect && !noLearning && typed.isNotEmpty() && typed.all { it.isLetter() || it == '\'' }) words.correction(typed) else null
        val word = if (fix != null) matchCase(fix, typed) else typed
        composing.setLength(0)
        ic.beginBatchEdit()
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (typed.isNotEmpty() && !noLearning && fix == null) words.learn(typed)
        lastCorrection = if (fix != null) typed to word else null
        lastSpaceAt = now
        updateShift(); updateSuggestions()
    }

    private var autocorrect = true
    private var lastCorrection: Pair<String, String>? = null
    private fun matchCase(word: String, like: String): String = when {
        like.length > 1 && like.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(Locale.getDefault())
        like.first().isUpperCase() -> word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        else -> word
    }

    /** K3 swipe: the gesture's best word goes in as composing text; the strip offers the next two. */
    internal fun onSwipe(path: String, capital: Boolean) {
        val ic = currentInputConnection ?: return
        if (noLearning) return
        val found = words.swipe(path, 3)
        if (found.isEmpty()) return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) { val w = composing.toString(); composing.setLength(0); ic.commitText("$w ", 1); words.learn(w) }
        else { val before = ic.getTextBeforeCursor(1, 0); if (!before.isNullOrEmpty() && before[0].isLetterOrDigit()) ic.commitText(" ", 1) }
        ic.endBatchEdit()
        val cased = found.map { if (capital) it.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } else it }
        composing.append(cased[0]); ic.setComposingText(composing, 1)
        lastCorrection = null
        view?.setSuggestions(cased, 0)
        updateShift()
    }

    internal fun onDelete() {
        val ic = currentInputConnection ?: return
        val lc = lastCorrection
        if (lc != null && composing.isEmpty()) {
            lastCorrection = null
            val before = ic.getTextBeforeCursor(lc.second.length + 1, 0)?.toString()
            if (before == lc.second + " ") { // undo the autocorrect: put back exactly what was typed
                ic.deleteSurroundingText(lc.second.length + 1, 0); ic.commitText(lc.first, 1)
                updateShift(); updateSuggestions(); return
            }
        }
        lastCorrection = null
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
        } else {
            val sel = ic.getSelectedText(0)
            if (!sel.isNullOrEmpty()) ic.commitText("", 1)
            else { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) }
        }
        updateShift(); updateSuggestions()
    }

    internal fun onEnter() {
        val ic = currentInputConnection ?: return
        commitComposing(learn = true)
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
        else { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)) }
        updateShift(); updateSuggestions()
    }

    internal fun onPickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        composing.setLength(0)
        ic.beginBatchEdit(); ic.commitText("$word ", 1); ic.endBatchEdit()
        if (!noLearning) words.learn(word)
        lastSpaceAt = 0L
        updateShift(); updateSuggestions()
    }

    internal fun moveCursor(steps: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) { ic.finishComposingText(); composing.setLength(0) }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        updateSuggestions()
    }

    internal fun switchKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun commitComposing(learn: Boolean) {
        if (composing.isEmpty()) return
        val word = composing.toString()
        currentInputConnection?.commitText(word, 1)
        composing.setLength(0)
        if (learn && !noLearning) words.learn(word)
    }

    private fun updateShift() {
        val v = view ?: return
        val info = currentInputEditorInfo ?: return
        if (v.capsLock) return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        v.setShifted(caps != 0 && composing.isEmpty())
    }

    private fun updateSuggestions() {
        val v = view ?: return
        if (noLearning) { v.setSuggestions(emptyList(), -1); return }
        val typed = composing.toString()
        if (typed.isEmpty()) { v.setSuggestions(emptyList(), -1); return }
        val cap = typed.first().isUpperCase()
        val list = words.suggest(typed, 3).map { if (cap && it.first().isLowerCase()) it.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } else it }
            .filter { it != typed }
        // Zune order: what you typed on the left, best guess in the middle (highlighted), next on the right.
        val fix = if (autocorrect && typed.length >= 3) words.correction(typed)?.let { matchCase(it, typed) } else null
        val cells = (listOf(typed) + listOfNotNull(fix) + list).distinct().take(3)
        v.setSuggestions(cells, if (cells.size > 1) 1 else 0)
    }
}

@SuppressLint("ViewConstructor")
private class GlideKeyboardView(private val ime: GlideKeyboardService) : View(ime) {
    private val d = resources.displayMetrics.density
    private val font: Typeface = try { ResourcesCompat.getFont(ime, R.font.zegoe_regular) ?: Typeface.DEFAULT } catch (_: Exception) { Typeface.DEFAULT }
    private val fontLight: Typeface = try { ResourcesCompat.getFont(ime, R.font.zegoe_light) ?: font } catch (_: Exception) { font }

    private val keyColor = 0xFF262626.toInt() // F3: Zune HD / WP7 key grey
    private val funcColor = 0xFF1A1A1A.toInt()
    var accent = 0xFFD80073.toInt() // B2: set from your accent colour when the keyboard opens
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = font }

    var numeric = false
    var enterLabel = "↵"
    var showSuggestions = true
    var swipeOn = true
    // K3 swipe typing state
    private var swiping = false
    private val swipePath = StringBuilder()
    private val trail = ArrayList<Float>()
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var cursorAnchor = 0f; private var spaceMoved = false
    var capsLock = false
    private var shifted = false
    private var symbols = 0 // 0 letters, 1 = 123, 2 = more symbols
    private var lastShiftTap = 0L

    private var suggestions: List<String> = emptyList()
    private var highlighted = -1
    private val suggestionRects = Array(3) { RectF() }

    private val letterAlts = mapOf('q' to "1", 'w' to "2", 'e' to "3èéêëē", 'r' to "4", 't' to "5þ", 'y' to "6ý", 'u' to "7ùúûü",
        'i' to "8ìíîï", 'o' to "9òóôöõø", 'p' to "0", 'a' to "àáâäãåæ", 's' to "ßś", 'd' to "ð", 'g' to "ğ", 'l' to "ł",
        'z' to "žź", 'c' to "çć", 'n' to "ñń")
    private fun letters() = listOf(
        "qwertyuiop".map { Key(it.toString(), it.code, alts = letterAlts[it] ?: "") },
        "asdfghjkl".map { Key(it.toString(), it.code, alts = letterAlts[it] ?: "") },
        listOf(Key("shift", SHIFT, 1.5f)) + "zxcvbnm".map { Key(it.toString(), it.code, alts = letterAlts[it] ?: "") } + Key("del", DEL, 1.5f),
        listOf(Key("123", MODE, 1.25f), Key("☺", EMOJI, 1f), Key(",", ','.code, alts = ";:"), Key("space", ' '.code, 4f),
            Key(".", '.'.code, alts = "?!'\"-:;@"), Key("enter", ENTER, 1.5f))
    )
    private var emojiPage = 0
    private fun emojiRows(): List<List<Key>> {
        val page = EMOJI_PAGES[emojiPage.coerceIn(0, EMOJI_PAGES.lastIndex)]
        return page.chunked(8).take(3).map { row -> row.map { Key(it, EMOJI_CHAR, 1.25f) } } +
            listOf(listOf(Key("abc", MODE, 1.5f), Key("‹", EPREV, 1.25f), Key("space", ' '.code, 4.5f), Key("›", ENEXT, 1.25f), Key("del", DEL, 1.5f)))
    }
    private fun symbols1() = listOf(
        "1234567890".map { Key(it.toString(), it.code) },
        "@#$%&-+()".map { Key(it.toString(), it.code, alts = if (it == '$') "₹€£¥" else "") },
        listOf(Key("=\\<", PAGE, 1.5f)) + "*\"':;!?".map { Key(it.toString(), it.code) } + Key("del", DEL, 1.5f),
        listOf(Key("abc", MODE, 1.5f), Key(",", ','.code), Key("space", ' '.code, 5f), Key(".", '.'.code), Key("enter", ENTER, 1.5f))
    )
    private fun symbols2() = listOf(
        "~`|•√π÷×¶∆".map { Key(it.toString(), it.code) },
        "£€¥₹^°={}".map { Key(it.toString(), it.code) },
        listOf(Key("123", PAGE, 1.5f)) + "\\©®™[]<".map { Key(it.toString(), it.code) } + Key("del", DEL, 1.5f),
        listOf(Key("abc", MODE, 1.5f), Key(",", ','.code), Key("space", ' '.code, 5f), Key(".", '.'.code), Key("enter", ENTER, 1.5f))
    )
    private var rows: List<List<Key>> = letters()

    private val stripH get() = (44 * d)
    private val rowH get() = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 40 * d else 54 * d
    private val gap get() = 3 * d

    fun reset() {
        capsLock = false; shifted = false
        symbols = if (numeric) 1 else 0
        rows = if (symbols == 1) symbols1() else letters()
        suggestions = emptyList(); highlighted = -1
        // K1 fix: new key objects need positions now; the view size may not change, so onSizeChanged won't run
        // (that left an all-black keyboard after switching apps).
        layoutKeys()
        requestLayout(); invalidate()
    }
    fun setShifted(value: Boolean) { if (shifted != value) { shifted = value; invalidate() } }
    fun setSuggestions(list: List<String>, highlight: Int) { suggestions = list; highlighted = highlight; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (stripH + rowH * 4 + gap * 2).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutKeys() }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { layoutKeys() }

    private fun layoutKeys() {
        val w = width.toFloat(); if (w <= 0f) return
        val unit = (w - gap) / 10f
        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = gap / 2 + (10f - total) * unit / 2f // short rows are centred (asdf row)
            val top = stripH + r * rowH
            row.forEach { k ->
                k.rect.set(x + gap / 2, top + gap / 2, x + k.weight * unit - gap / 2, top + rowH - gap / 2)
                x += k.weight * unit
            }
        }
        val cw = w / 3f
        for (i in 0 until 3) suggestionRects[i].set(i * cw, 0f, (i + 1) * cw, stripH)
    }

    private fun label(k: Key): String = when (k.code) {
        SHIFT -> if (capsLock) "⇪" else "⇧"
        DEL -> "⌫"
        ENTER -> enterLabel
        ' '.code -> ""
        else -> if (k.code > 0 && (shifted || capsLock) && symbols == 0) k.label.uppercase(Locale.getDefault()) else k.label
    }

    // ---- drawing ----
    private var pressed: Key? = null
    private var altKey: Key? = null
    private var altIndex = 0
    private val altRects = ArrayList<RectF>()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF000000.toInt())
        // suggestion strip
        if (showSuggestions) suggestions.forEachIndexed { i, s ->
            if (i > 2) return@forEachIndexed
            val r = suggestionRects[i]
            text.typeface = if (i == highlighted) font else fontLight
            text.textSize = 18 * d
            text.color = if (i == highlighted) 0xFFFFFFFF.toInt() else 0xFFA0A0A0.toInt()
            val shown = KbText.ellipsize(s, text, r.width() - 12 * d)
            canvas.drawText(shown, r.centerX(), r.centerY() + 6 * d, text)
        }
        rows.forEach { row -> row.forEach { k ->
            val isFunc = k.code < 0 || k.code == ' '.code
            paint.color = when {
                k === pressed && altKey == null -> accent
                k.code == SHIFT && (shifted || capsLock) -> 0xFFFFFFFF.toInt()
                isFunc -> funcColor
                else -> keyColor
            }
            canvas.drawRect(k.rect, paint)
            val lab = label(k)
            text.color = if (k.code == SHIFT && (shifted || capsLock)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            val words = lab.length > 1 && k.code < 0 && lab != "⌫"
            text.typeface = if (words) font else fontLight
            text.textSize = when { words -> 15 * d; k.code < 0 -> 22 * d; else -> 24 * d }
            canvas.drawText(lab, k.rect.centerX(), k.rect.centerY() + text.textSize * 0.35f, text)
            if (k.code == ' '.code) { // thin space bar mark
                paint.color = 0xFF5A5A5A.toInt()
                canvas.drawRect(k.rect.centerX() - 22 * d, k.rect.bottom - 14 * d, k.rect.centerX() + 22 * d, k.rect.bottom - 12 * d, paint)
            }
        } }
        if (swiping && trail.size >= 4) {
            paint.color = accent; paint.alpha = 170; paint.strokeWidth = 6 * d; paint.strokeCap = Paint.Cap.ROUND
            var j = 2
            while (j + 1 < trail.size) { canvas.drawLine(trail[j - 2], trail[j - 1], trail[j], trail[j + 1], paint); j += 2 }
            paint.alpha = 255
        }
        // letter preview above the finger (Windows Phone: accent block with the white letter)
        val p = pressed ?: linger
        if (p != null && altKey == null && p.code > 0 && p.code != ' '.code) {
            val pw = p.rect.width() * 1.25f; val ph = rowH * 1.1f
            val left = (p.rect.centerX() - pw / 2).coerceIn(0f, width - pw)
            val top = (p.rect.top - ph - 4 * d).coerceAtLeast(0f)
            paint.color = accent
            canvas.drawRect(left, top, left + pw, top + ph, paint)
            text.color = 0xFFFFFFFF.toInt(); text.typeface = fontLight; text.textSize = 34 * d
            canvas.drawText(if (p === linger && pressed == null) lingerLabel else label(p), left + pw / 2, top + ph / 2 + 12 * d, text)
        }
        // accents popup
        val ak = altKey
        if (ak != null) {
            altRects.forEachIndexed { i, r ->
                paint.color = if (i == altIndex) accent else 0xFF3A3A3A.toInt()
                canvas.drawRect(r, paint)
                text.color = 0xFFFFFFFF.toInt(); text.typeface = fontLight; text.textSize = 24 * d
                canvas.drawText(altChars(ak)[i], r.centerX(), r.centerY() + 8 * d, text)
            }
        }
    }

    private fun altChars(k: Key): List<String> {
        val base = listOf(label(k))
        val alts = k.alts.map { if ((shifted || capsLock) && symbols == 0) it.uppercase() else it.toString() }
        return base + alts
    }

    // ---- touch ----
    private val handler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() { ime.onDelete(); handler.postDelayed(this, 55L) }
    }
    private val longPress = Runnable {
        val k = pressed ?: return@Runnable
        when {
            k.code == ' '.code -> { pressed = null; invalidate(); ime.switchKeyboard() }
            k.alts.isNotEmpty() -> {
                altKey = k; altIndex = 1.coerceAtMost(altChars(k).lastIndex)
                val chars = altChars(k)
                val cw = k.rect.width().coerceAtLeast(34 * d); val total = cw * chars.size
                var x = (k.rect.centerX() - cw * 1.5f).coerceIn(0f, (width - total).coerceAtLeast(0f))
                val top = (k.rect.top - rowH - 6 * d).coerceAtLeast(0f)
                altRects.clear(); chars.forEach { _ -> altRects += RectF(x, top, x + cw, top + rowH); x += cw }
                haptic(); invalidate()
            }
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        rows.forEach { row -> row.forEach { k -> if (x >= k.rect.left - gap / 2 && x <= k.rect.right + gap / 2 && y >= k.rect.top - gap / 2 && y <= k.rect.bottom + gap / 2) return k } }
        return null
    }

    private fun haptic() { GlideFeedback.tap(this); GlideFeedback.key(context) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = e.getX(i); val y = e.getY(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Fast typing: a second finger landing finishes the first key.
                handler.removeCallbacks(repeatDelete)
                if (pressed != null && altKey == null) release(pressed!!)
                if (y < stripH) {
                    suggestionRects.forEachIndexed { n, r -> if (r.contains(x, y) && n < suggestions.size) { haptic(); ime.onPickSuggestion(suggestions[n]) } }
                    pressed = null; return true
                }
                val k = keyAt(x, y) ?: return true
                pressed = k; haptic(); invalidate()
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    swiping = false; swipePath.setLength(0); trail.clear()
                    downX = x; downY = y; lastX = x; lastY = y; cursorAnchor = x; spaceMoved = false
                    if (k.code in 'a'.code..'z'.code) swipePath.append(k.code.toChar())
                }
                if (k.code == DEL) { ime.onDelete(); handler.postDelayed(repeatDelete, 420L) }
                else if (k.alts.isNotEmpty() || k.code == ' '.code) handler.postDelayed(longPress, 380L)
            }
            MotionEvent.ACTION_MOVE -> {
                val ak = altKey
                val mx = e.getX(0); val my = e.getY(0)
                val keyW = width / 10f
                // F3: slide along the spacebar to move the cursor one letter per half key
                val sp = pressed
                if (sp != null && sp.code == ' '.code && ak == null && !swiping) {
                    val steps = ((mx - cursorAnchor) / (keyW / 2f)).toInt()
                    if (steps != 0) {
                        handler.removeCallbacks(longPress); spaceMoved = true
                        ime.moveCursor(steps); cursorAnchor += steps * keyW / 2f
                    }
                    return true
                }
                if (!swiping && swipeOn && symbols == 0 && ak == null && e.pointerCount == 1 && swipePath.isNotEmpty() &&
                    kotlin.math.hypot(mx - downX, my - downY) > keyW * 0.8f) {
                    swiping = true; handler.removeCallbacks(longPress); pressed = null
                    trail.add(downX); trail.add(downY)
                }
                if (swiping) {
                    // sample the segment so fast strokes don't skip keys
                    val steps = (kotlin.math.hypot(mx - lastX, my - lastY) / (keyW / 3f)).toInt().coerceIn(1, 30)
                    for (n in 1..steps) {
                        val sx = lastX + (mx - lastX) * n / steps; val sy = lastY + (my - lastY) * n / steps
                        val k = keyAt(sx, sy)
                        if (k != null && k.code in 'a'.code..'z'.code && swipePath.last() != k.code.toChar()) swipePath.append(k.code.toChar())
                    }
                    lastX = mx; lastY = my
                    if (trail.size < 600) { trail.add(mx); trail.add(my) }
                    invalidate(); return true
                }
                if (ak != null) {
                    val px = e.getX(0)
                    val idx = altRects.indexOfFirst { px >= it.left && px < it.right }
                    if (idx >= 0 && idx != altIndex) { altIndex = idx; invalidate() }
                } else if (pressed != null && pressed!!.code != DEL) {
                    val k = keyAt(e.getX(0), e.getY(0))
                    if (k != null && k !== pressed) { handler.removeCallbacks(longPress); pressed = k; invalidate()
                        if (k.alts.isNotEmpty() || k.code == ' '.code) handler.postDelayed(longPress, 380L) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (swiping) {
                    swiping = false
                    ime.onSwipe(swipePath.toString(), shifted || capsLock)
                    afterChar(); trail.clear(); cancelTouch(); return true
                }
                val k = pressed
                if (k != null) {
                    val ak = altKey
                    if (ak != null) { ime.onChar(altChars(ak)[altIndex]); afterChar() }
                    else if (k.code == ' '.code && spaceMoved) { spaceMoved = false; pressed = null }
                    else if (k.code != DEL) {
                        // F4b: the letter preview stays up 60 ms after release, like WP
                        if (k.code > 0 && k.code != ' '.code) { linger = k; lingerLabel = label(k)
                            handler.removeCallbacks(clearLinger); handler.postDelayed(clearLinger, 60L) }
                        release(k)
                    }
                }
                cancelTouch()
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private var linger: Key? = null
    private var lingerLabel = ""
    private val clearLinger = Runnable { linger = null; invalidate() }

    private fun cancelTouch() {
        swiping = false; trail.clear()
        handler.removeCallbacks(repeatDelete); handler.removeCallbacks(longPress)
        pressed = null; altKey = null; altRects.clear(); invalidate()
    }

    private fun release(k: Key) {
        handler.removeCallbacks(longPress)
        when (k.code) {
            SHIFT -> {
                val now = System.currentTimeMillis()
                if (capsLock) { capsLock = false; shifted = false }
                else if (now - lastShiftTap < 350) { capsLock = true; shifted = true }
                else shifted = !shifted
                lastShiftTap = now
            }
            DEL -> {}
            MODE -> { symbols = if (symbols == 0) 1 else 0; rows = if (symbols == 0) letters() else symbols1(); layoutKeys() }
            EMOJI -> { symbols = 3; rows = emojiRows(); layoutKeys() }
            EPREV -> { emojiPage = (emojiPage + EMOJI_PAGES.size - 1) % EMOJI_PAGES.size; rows = emojiRows(); layoutKeys() }
            ENEXT -> { emojiPage = (emojiPage + 1) % EMOJI_PAGES.size; rows = emojiRows(); layoutKeys() }
            PAGE -> { symbols = if (symbols == 1) 2 else 1; rows = if (symbols == 2) symbols2() else symbols1(); layoutKeys() }
            ENTER -> ime.onEnter()
            ' '.code -> ime.onSpace()
            else -> { ime.onChar(label(k)); afterChar() }
        }
        pressed = null; invalidate()
    }

    private fun afterChar() {
        if (shifted && !capsLock) shifted = false
    }
}

private object KbText {
    fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }
}
