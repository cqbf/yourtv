package com.horsenma.mytv1

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
import android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import com.horsenma.yourtv.YourTVApplication
import androidx.core.view.setPadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.horsenma.yourtv.databinding.ListItemBinding
import com.horsenma.mytv1.models.TVListModel
import com.horsenma.mytv1.models.TVModel
import com.horsenma.yourtv.R

class ListAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    var tvListModel: TVListModel,
) :
    RecyclerView.Adapter<ListAdapter.ViewHolder>() {
    private var listener: ItemListener? = null
    private var focused: View? = null
    private var defaultFocused = false
    private var defaultFocus: Int = -1

    var visiable = false

    val application = context.applicationContext as YourTVApplication

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = ListItemBinding.inflate(inflater, parent, false)

        binding.icon.layoutParams.width = application.px2Px(binding.icon.layoutParams.width)
        binding.icon.layoutParams.height = application.px2Px(binding.icon.layoutParams.height)
        binding.icon.setPadding(application.px2Px(binding.icon.paddingTop))

        binding.title.layoutParams.width = application.px2Px(binding.title.layoutParams.width)
        binding.title.layoutParams.height = application.px2Px(binding.title.layoutParams.height)
        binding.title.textSize = application.px2PxFont(binding.title.textSize)

        binding.heart.layoutParams.width = application.px2Px(binding.heart.layoutParams.width)
        binding.heart.layoutParams.height = application.px2Px(binding.heart.layoutParams.height)
        binding.heart.setPadding(application.px2Px(binding.heart.paddingTop))

        return ViewHolder(context, binding)
    }

    fun focusable(able: Boolean) {
        recyclerView.isFocusable = able
        recyclerView.isFocusableInTouchMode = able
        if (able) {
            recyclerView.descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        } else {
            recyclerView.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        }
    }

    fun update(tvListModel: TVListModel) {
        this.tvListModel = tvListModel
        recyclerView.post {
            notifyDataSetChanged()
        }
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val tvModel = tvListModel.getTVModel(position)!!
        val view = viewHolder.itemView

        view.isFocusable = true
        view.isFocusableInTouchMode = true
//        view.alpha = 0.8F

        viewHolder.like(tvModel.like.value as Boolean)

        viewHolder.binding.heart.setOnClickListener {
            tvModel.setLike(!(tvModel.like.value as Boolean))
            viewHolder.like(tvModel.like.value as Boolean)
        }

        if (!defaultFocused && position == defaultFocus) {
            view.requestFocus()
            defaultFocused = true
        }

        val onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            listener?.onItemFocusChange(tvModel, hasFocus)

            if (hasFocus) {
                viewHolder.focus(true)
                focused = view
                if (visiable) {
                    if (position != tvListModel.position.value) {
                        tvListModel.setPosition(position)
                    }
                } else {
                    visiable = true
                }
            } else {
                viewHolder.focus(false)
            }
        }

        view.onFocusChangeListener = onFocusChangeListener

        view.setOnClickListener { _ ->
            listener?.onItemClicked(tvModel)
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                    val p = getItemCount() - 1

                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        p,
                        0
                    )

                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == getItemCount() - 1) {
                    val p = 0

                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        p,
                        0
                    )

                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    tvModel.setLike(!(tvModel.like.value as Boolean))
                    viewHolder.like(tvModel.like.value as Boolean)
                }

                return@setOnKeyListener listener?.onKey(this, keyCode) ?: false
            }
            false
        }

        viewHolder.bindTitle(tvModel.tv.title)

        viewHolder.bindImage(tvModel.tv.logo, tvModel.tv.id)
    }

    override fun getItemCount() = tvListModel.size()

    class ViewHolder(private val context: Context, val binding: ListItemBinding) :
        RecyclerView.ViewHolder(binding.root), OnSharedPreferenceChangeListener {

        init {
            SP.setOnSharedPreferenceChangeListener(this)
        }

        fun bindTitle(text: String) {
            binding.title.text = text
        }

        fun bindImage(url: String?, id: Int) {
            if (url.isNullOrBlank()) {
                val width = Utils.dpToPx(40)
                val height = Utils.dpToPx(40)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val paint = Paint().apply {
                    color = Color.WHITE
                    textSize = 32f
                    textAlign = Paint.Align.CENTER
                }
                val text = String.format("%3d", id + 1)
                val x = width / 2f
                val y = height / 2f - (paint.descent() + paint.ascent()) / 2
                canvas.drawText(text, x, y, paint)
                Glide.with(context)
                    .load(BitmapDrawable(context.resources, bitmap))
                    .centerInside()
                    .into(binding.icon)
//                binding.imageView.setImageDrawable(null)
            } else {
                Glide.with(context)
                    .load(url)
                    .centerInside()
//                    .error(BitmapDrawable(context.resources, bitmap))
                    .into(binding.icon)
            }
        }

        fun focus(hasFocus: Boolean) {
            if (hasFocus) {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.white))
                binding.root.setBackgroundResource(R.color.focus)
            } else {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.title_blur))
                binding.root.setBackgroundResource(R.color.blur)
            }
        }

        fun like(liked: Boolean) {
            if (liked) {
                binding.heart.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        R.drawable.baseline_favorite_24
                    )
                )
            } else {
                binding.heart.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        R.drawable.baseline_favorite_border_24
                    )
                )
            }
        }

        override fun onSharedPreferenceChanged(key: String) {
            Log.i(TAG, "$key changed")
            when (key) {
                SP.KEY_EPG -> {
                    if (SP.epg.isNullOrEmpty()) {
                        val constraintSet = ConstraintSet()
                        constraintSet.clone(binding.root)

                        constraintSet.connect(binding.title.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                        constraintSet.connect(binding.heart.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

                        constraintSet.applyTo(binding.root)
                    } else {
                        val constraintSet = ConstraintSet()
                        constraintSet.clone(binding.root)

                        constraintSet.clear(binding.title.id, ConstraintSet.BOTTOM)
                        constraintSet.clear(binding.heart.id, ConstraintSet.BOTTOM)

                        constraintSet.applyTo(binding.root)
                    }
                }
            }
        }
    }

    fun toPosition(position: Int) {
        recyclerView.post {
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                position,
                0
            )

            recyclerView.postDelayed({
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                viewHolder?.itemView?.isSelected = true
                viewHolder?.itemView?.requestFocus()
            }, 0)
        }
    }

    interface ItemListener {
        fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean)
        fun onItemClicked(tvModel: TVModel)
        fun onKey(listAdapter: ListAdapter, keyCode: Int): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    companion object {
        private const val TAG = "ListAdapter"
    }
}

