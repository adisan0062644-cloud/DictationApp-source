package com.dictation.app.data

import android.content.Context
import android.content.SharedPreferences
import com.dictation.app.DictationApp

/**
 * Хранилище настроек. Ключи API хранятся в обычном SharedPreferences
 * с префиксом (для prod-версии можно перенести в EncryptedSharedPreferences).
 */
class Settings private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- API ключи ---
    fun getKeys(): List<String> {
        val raw = prefs.getString(KEY_API_KEYS, "") ?: ""
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
    fun setKeys(keys: List<String>) {
        prefs.edit().putString(KEY_API_KEYS, keys.joinToString("\n")).apply()
    }

    // --- Промт ---
    fun getPrompt(): String = prefs.getString(KEY_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT
    fun setPrompt(p: String) { prefs.edit().putString(KEY_PROMPT, p).apply() }

    // --- Язык ---
    enum class Lang(val tag: String) { RU("ru"), EN("en"), AUTO("auto") }
    fun getLang(): Lang = Lang.entries.firstOrNull { it.tag == prefs.getString(KEY_LANG, "ru") } ?: Lang.RU
    fun setLang(l: Lang) { prefs.edit().putString(KEY_LANG, l.tag).apply() }

    // --- Кодек ---
    enum class Codec(val mime: String, val bitrate: Int, val ext: String) {
        OPUS_32("audio/ogg;codecs=opus", 32000, "ogg"),
        OPUS_64("audio/ogg;codecs=opus", 64000, "ogg"),
        AAC_64("audio/mp4;codecs=mp4a.40.2", 64000, "m4a"),
        AMR_WB("audio/amr-wb", 24000, "amr")
    }
    fun getCodec(): Codec = Codec.entries.firstOrNull { it.name == prefs.getString(KEY_CODEC, "OPUS_32") } ?: Codec.OPUS_32
    fun setCodec(c: Codec) { prefs.edit().putString(KEY_CODEC, c.name).apply() }

    // --- Ориентация панели ---
    enum class Orient { VERTICAL, HORIZONTAL }
    fun getOrient(): Orient = Orient.entries.firstOrNull { it.name == prefs.getString(KEY_ORIENT, "VERTICAL") } ?: Orient.VERTICAL
    fun setOrient(o: Orient) { prefs.edit().putString(KEY_ORIENT, o.name).apply() }

    // --- Размер панели ---
    enum class Size(val idx: Int) { S(0), M(1), L(2) }
    fun getSize(): Size = Size.entries.firstOrNull { it.idx == prefs.getInt(KEY_SIZE, 1) } ?: Size.M
    fun setSize(s: Size) { prefs.edit().putInt(KEY_SIZE, s.idx).apply() }

    // --- Защита стопа ---
    fun isStopProtect(): Boolean = prefs.getBoolean(KEY_STOP_PROTECT, true)
    fun setStopProtect(v: Boolean) { prefs.edit().putBoolean(KEY_STOP_PROTECT, v).apply() }

    // --- Автокопирование ---
    fun isAutoCopy(): Boolean = prefs.getBoolean(KEY_AUTOCOPY, true)
    fun setAutoCopy(v: Boolean) { prefs.edit().putBoolean(KEY_AUTOCOPY, v).apply() }

    // --- Автоудаление аудио (дни) ---
    fun getAutoDeleteDays(): Int = prefs.getInt(KEY_AUTODEL, 7)
    fun setAutoDeleteDays(d: Int) { prefs.edit().putInt(KEY_AUTODEL, d).apply() }

    // --- Позиция якоря ---
    enum class Anchor { REMEMBER, LEFT, RIGHT }
    fun getAnchor(): Anchor = Anchor.entries.firstOrNull { it.name == prefs.getString(KEY_ANCHOR, "REMEMBER") } ?: Anchor.REMEMBER
    fun setAnchor(a: Anchor) { prefs.edit().putString(KEY_ANCHOR, a.name).apply() }

    // --- Позиция якоря (запомненная) ---
    fun getHandleX(): Int = prefs.getInt(KEY_HANDLE_X, -1)
    fun setHandleX(v: Int) { prefs.edit().putInt(KEY_HANDLE_X, v).apply() }
    fun getHandleY(): Int = prefs.getInt(KEY_HANDLE_Y, -1)
    fun setHandleY(v: Int) { prefs.edit().putInt(KEY_HANDLE_Y, v).apply() }

    companion object {
        private const val PREFS = "dictation_prefs"
        private const val KEY_API_KEYS = "api_keys"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_LANG = "lang"
        private const val KEY_CODEC = "codec"
        private const val KEY_ORIENT = "orient"
        private const val KEY_SIZE = "size"
        private const val KEY_STOP_PROTECT = "stop_protect"
        private const val KEY_AUTOCOPY = "autocopy"
        private const val KEY_AUTODEL = "autodel"
        private const val KEY_ANCHOR = "anchor"
        private const val KEY_HANDLE_X = "handle_x"
        private const val KEY_HANDLE_Y = "handle_y"

        const val DEFAULT_PROMPT = """Обработай расшифровку голосовой диктовки и верни только чистый текст.

Удали:
— слова-паразиты: «ну», «как бы», «это самое», «короче», «типа», «вот», «так сказать», «в общем», «то есть» в начале фраз, «э-э», «м-м»;
— повторы слов подряд («я я я», «ну ну»);
— оборванные слова и фразы без смысла.

Расставь:
— точки, запятые, тире, вопросительные и восклицательные знаки;
— разбей на абзацы там, где в расшифровке были паузы длиннее 1.5 секунд.

Сохрани:
— смысл, авторскую лексику, порядок мыслей;
— имена собственные, термины, цифры как продиктовано;
— язык оригинала.

Не добавляй от себя ничего. Верни только готовый текст."""

        @Volatile private var INSTANCE: Settings? = null
        fun get(): Settings = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Settings(DictationApp.instance).also { INSTANCE = it }
        }
    }
}