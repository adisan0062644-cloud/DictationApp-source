package com.dictation.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dictation.app.R
import com.dictation.app.data.HistoryItem
import com.dictation.app.data.HistoryRepository
import com.dictation.app.databinding.FragmentHistoryBinding
import com.dictation.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _b: FragmentHistoryBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentHistoryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        adapter = HistoryAdapter(
            onCopy = { copy(it) },
            onShare = { share(it) },
            onDelete = { confirmDelete(it.id) },
            onPlay = { playAudio(it) }
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        b.searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { reload() }
        })
        b.btnDeleteAll.setOnClickListener { confirmDeleteAll() }
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val q = b.searchEdit.text?.toString()?.trim()
        val items = HistoryRepository.list(q)
        adapter.submit(items)
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun copy(text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dictation", text))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share(text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(i, null))
    }

    private fun confirmDelete(id: Long) {
        HistoryRepository.delete(id)
        reload()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.delete_all_confirm)
            .setPositiveButton(R.string.delete_all) { _, _ ->
                HistoryRepository.deleteAll()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun playAudio(item: HistoryItem) {
        val path = item.audioPath ?: return
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            mp.start()
            mp.setOnCompletionListener { it.release() }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Не удалось воспроизвести", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

private class HistoryAdapter(
    private val onCopy: (String) -> Unit,
    private val onShare: (String) -> Unit,
    private val onDelete: (HistoryItem) -> Unit,
    private val onPlay: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<HistoryItem>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    fun submit(list: List<HistoryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val now = System.currentTimeMillis()
        val sameDay = (now - item.ts) < 24L * 60 * 60 * 1000 &&
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(item.ts)) ==
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now))
        h.b.time.text = if (sameDay) timeFmt.format(Date(item.ts)) else dateFmt.format(Date(item.ts))
        h.b.text.text = item.text
        val sec = item.durationMs / 1000
        val sizeKb = item.audioSize / 1024
        h.b.meta.text = if (item.audioPath != null)
            "⏱ ${sec}с   ♪ ${sizeKb}КБ"
        else
            "⏱ ${sec}с"
        h.b.btnPlay.visibility = if (item.audioPath != null) View.VISIBLE else View.GONE
        h.b.btnPlay.setOnClickListener { onPlay(item) }
        h.b.btnCopy.setOnClickListener { onCopy(item.text) }
        h.b.btnShare.setOnClickListener { onShare(item.text) }
        h.b.btnDelete.setOnClickListener { onDelete(item) }
    }

    class VH(val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root)
}