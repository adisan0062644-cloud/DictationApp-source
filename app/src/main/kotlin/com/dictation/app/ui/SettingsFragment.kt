package com.dictation.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.dictation.app.R
import com.dictation.app.data.Settings
import com.dictation.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        renderKeys()
        b.btnAddKey.setOnClickListener { addKeyRow() }
        b.promptEdit.setText(Settings.get().getPrompt())
        b.btnSavePrompt.setOnClickListener {
            Settings.get().setPrompt(b.promptEdit.text.toString())
            showToast("Сохранено")
        }
        b.btnResetPrompt.setOnClickListener {
            b.promptEdit.setText(Settings.DEFAULT_PROMPT)
            Settings.get().setPrompt(Settings.DEFAULT_PROMPT)
            showToast("Сброшено на умолчание")
        }
        renderOtherSettings()
    }

    private fun renderKeys() {
        b.keysContainer.removeAllViews()
        val keys = Settings.get().getKeys()
        val rows = if (keys.isEmpty()) listOf("") else keys
        rows.forEachIndexed { idx, k -> addKeyRow(k, idx + 1, keys.size > 1) }
    }

    private fun addKeyRow(initial: String = "", num: Int? = null, removable: Boolean = true) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        val tv = TextView(ctx).apply {
            text = "${num ?: (b.keysContainer.childCount + 1)}."
            setTextColor(ctx.getColor(R.color.muted))
            textSize = 12f
            setPadding(0, 18, 8, 0)
        }
        val edit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(initial)
            hint = "AIzaSy..."
            setBackgroundResource(R.drawable.bg_edit)
            setTextColor(ctx.getColor(R.color.text))
            setHintTextColor(ctx.getColor(R.color.muted))
            setPadding(16, 12, 16, 12)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(ctx).apply {
            text = "×"
            setTextColor(ctx.getColor(R.color.rec))
            setBackgroundResource(R.drawable.bg_chip)
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(ctx, 40)
            ).apply { marginStart = 8 }
            layoutParams = p
            visibility = if (removable) View.VISIBLE else View.GONE
            setOnClickListener {
                (row.parent as? LinearLayout)?.removeView(row)
            }
        }
        row.addView(tv); row.addView(edit); row.addView(btn)
        b.keysContainer.addView(row)
        // при сохранении — соберём все значения
        b.btnAddKey.tag = "needs_save_keys"
    }

    override fun onPause() {
        super.onPause()
        // Сохраняем ключи при уходе с фрагмента
        val keys = b.keysContainer.children.toList().map { row ->
            val edit = (row as LinearLayout).getChildAt(1) as EditText
            edit.text.toString().trim()
        }.filter { it.isNotEmpty() }
        Settings.get().setKeys(keys)
    }

    private fun renderOtherSettings() {
        val parent = b.root.findViewById<LinearLayout>(
            requireView().id
        ) // placeholder
        val container = (b.root as ViewGroup).let { root ->
            // Найдём ScrollView/LinearLayout, в который добавим секции
            (root.getChildAt(0) as LinearLayout)
        }
        addSpinnerSetting(container, getString(R.string.settings_lang),
            getString(R.string.lang_ru), listOf(
                getString(R.string.lang_ru) to Settings.Lang.RU,
                getString(R.string.lang_en) to Settings.Lang.EN,
                getString(R.string.lang_auto) to Settings.Lang.AUTO
            )) { idx ->
                val list = listOf(Settings.Lang.RU, Settings.Lang.EN, Settings.Lang.AUTO)
                Settings.get().setLang(list[idx])
            }.also { (spinner, _) ->
                spinner.setSelection(Settings.get().getLang().ordinal)
            }

        addSpinnerSetting(container, getString(R.string.settings_codec),
            "Opus 32 kbps", listOf(
                "Opus 32 kbps" to Settings.Codec.OPUS_32,
                "Opus 64 kbps" to Settings.Codec.OPUS_64,
                "AAC 64 kbps" to Settings.Codec.AAC_64,
                "AMR-WB 24 kbps" to Settings.Codec.AMR_WB
            )) { idx ->
                val list = listOf(Settings.Codec.OPUS_32, Settings.Codec.OPUS_64, Settings.Codec.AAC_64, Settings.Codec.AMR_WB)
                Settings.get().setCodec(list[idx])
            }.also { (spinner, _) ->
                spinner.setSelection(Settings.get().getCodec().ordinal)
            }

        addSpinnerSetting(container, getString(R.string.settings_orient),
            getString(R.string.orient_v), listOf(
                getString(R.string.orient_v) to Settings.Orient.VERTICAL,
                getString(R.string.orient_h) to Settings.Orient.HORIZONTAL
            )) { idx ->
                val list = listOf(Settings.Orient.VERTICAL, Settings.Orient.HORIZONTAL)
                Settings.get().setOrient(list[idx])
            }.also { (spinner, _) ->
                spinner.setSelection(Settings.get().getOrient().ordinal)
            }

        addSpinnerSetting(container, getString(R.string.settings_panel_size),
            getString(R.string.size_m), listOf(
                getString(R.string.size_s) to Settings.Size.S,
                getString(R.string.size_m) to Settings.Size.M,
                getString(R.string.size_l) to Settings.Size.L
            )) { idx ->
                val list = listOf(Settings.Size.S, Settings.Size.M, Settings.Size.L)
                Settings.get().setSize(list[idx])
            }.also { (spinner, _) ->
                spinner.setSelection(Settings.get().getSize().ordinal)
            }

        addSpinnerSetting(container, getString(R.string.settings_autodel),
            getString(R.string.del_7d), listOf(
                getString(R.string.del_7d) to 7,
                getString(R.string.del_30d) to 30,
                getString(R.string.del_never) to 0
            )) { idx ->
                val list = listOf(7, 30, 0)
                Settings.get().setAutoDeleteDays(list[idx])
            }.also { (spinner, _) ->
                spinner.setSelection(
                    when (Settings.get().getAutoDeleteDays()) {
                        7 -> 0; 30 -> 1; else -> 2
                    }
                )
            }

        addSpinnerSetting(container, getString(R.string.settings_anchor_pos),
            getString(R.string.anchor_remember), listOf(
                getString(R.string.anchor_remember) to Settings.Anchor.REMEMBER,
                getString(R.string.anchor_left) to Settings.Anchor.LEFT,
                getString(R.string.anchor_right) to Settings.Anchor.RIGHT
            )) { idx ->
                val list = listOf(Settings.Anchor.REMEMBER, Settings.Anchor.LEFT, Settings.Anchor.RIGHT)
                Settings.get().setAnchor(list[idx])
            }.also { (spinner, _) ->
                spinner.setSelection(Settings.get().getAnchor().ordinal)
            }

        addSwitchSetting(container, getString(R.string.settings_autocopy),
            "") {
            Settings.get().setAutoCopy(it)
        }.also { (_, sw) ->
            sw.isChecked = Settings.get().isAutoCopy()
        }

        addSwitchSetting(container, getString(R.string.settings_stop_protect),
            "Стоп срабатывает только если удерживать кнопку 2 сек — защищает от случайных нажатий") {
            Settings.get().setStopProtect(it)
        }.also { (_, sw) ->
            sw.isChecked = Settings.get().isStopProtect()
        }
    }

    private fun addSpinnerSetting(
        container: LinearLayout,
        label: String,
        defLabel: String,
        items: List<Pair<String, Any>>,
        onChange: (Int) -> Unit
    ): Pair<Spinner, Unit> {
        val ctx = requireContext()
        val row = LayoutInflater.from(ctx).inflate(R.layout.row_setting, container, false)
        row.findViewById<TextView>(R.id.settingLabel).text = label
        row.findViewById<TextView>(R.id.settingDesc).text = ""
        val spinner = row.findViewById<Spinner>(R.id.settingSpinner)
        spinner.visibility = View.VISIBLE
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                onChange(pos)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        container.addView(row)
        return spinner to Unit
    }

    private fun addSwitchSetting(
        container: LinearLayout,
        label: String,
        desc: String,
        onChange: (Boolean) -> Unit
    ): Pair<View, androidx.appcompat.widget.SwitchCompat> {
        val ctx = requireContext()
        val row = LayoutInflater.from(ctx).inflate(R.layout.row_setting, container, false)
        row.findViewById<TextView>(R.id.settingLabel).text = label
        row.findViewById<TextView>(R.id.settingDesc).text = desc
        val sw = row.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.settingSwitch)
        sw.visibility = View.VISIBLE
        row.findViewById<Spinner>(R.id.settingSpinner).visibility = View.GONE
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        container.addView(row)
        return row to sw
    }

    private fun showToast(msg: String) =
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private val android.view.ViewGroup.children: Sequence<View>
        get() = sequence { for (i in 0 until childCount) yield(getChildAt(i)) }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}