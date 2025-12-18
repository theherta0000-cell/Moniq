package com.example.moniq

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.moniq.adapters.QueueAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.moniq.player.AudioPlayer

class QueueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar?>(R.id.queueToolbar)
        toolbar?.setNavigationOnClickListener { finish() }

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView?>(R.id.queueRecycler)
        val adapter = QueueAdapter(emptyList(), object : QueueAdapter.Callback {
            override fun onPlay(position: Int) {
                AudioPlayer.playAt(position)
                finish()
            }

            override fun onRemove(position: Int) {
                AudioPlayer.removeFromQueue(position)
            }
        })
        recycler?.layoutManager = LinearLayoutManager(this)
        recycler?.adapter = adapter

        // attach ItemTouchHelper for drag-to-reorder and swipe-to-remove
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rc: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                try {
                    adapter.moveItem(from, to)
                    AudioPlayer.moveInQueue(from, to)
                } catch (_: Exception) {}
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                try {
                    AudioPlayer.removeFromQueue(pos)
                    adapter.removeAt(pos)
                } catch (_: Exception) {}
            }

            override fun isLongPressDragEnabled(): Boolean = true
            override fun isItemViewSwipeEnabled(): Boolean = true
        }

        ItemTouchHelper(callback).attachToRecyclerView(recycler)

        // Observe queue
        AudioPlayer.queueTracks.observe(this) { list ->
            adapter.update(list)
        }

        AudioPlayer.currentQueueIndex.observe(this) { idx ->
            // highlight and scroll to current index
            try {
                adapter.setCurrentIndex(idx)
                recycler?.scrollToPosition(idx)
            } catch (_: Exception) {}
        }
    }
}
