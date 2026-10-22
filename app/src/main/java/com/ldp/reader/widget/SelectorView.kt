package com.ldp.reader.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ldp.reader.R
import com.ldp.reader.databinding.ItemSelectorBinding
import com.ldp.reader.databinding.ViewSelectorBinding
import java.util.ArrayList

class SelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private var mListener: OnItemSelectedListener? = null
    private val parentView: ViewGroup

    init {
        parentView = this
        orientation = HORIZONTAL
    }

    fun setSelectData(selectType: List<List<String>>) {
        for (i in selectType.indices) {
            createChildView(i, selectType[i])
        }
    }

    fun setSelectData(vararg selectType: List<String>) {
        for (i in selectType.indices) {
            createChildView(i, selectType[i])
        }
    }

    private fun createChildView(flag: Int, types: List<String>) {
        val item = SelectItem(context)
        val params = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        params.weight = 1f
        item.layoutParams = params
        item.tag = flag
        item.setData(types)

        addView(item)
    }

    fun setOnItemSelectedListener(listener: OnItemSelectedListener?) {
        mListener = listener
    }

    interface OnItemSelectedListener {
        fun onItemSelected(type: Int, pos: Int)
    }

    private inner class SelectItem @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : LinearLayout(context, attrs),
        OnClickListener,
        AdapterView.OnItemClickListener,
        PopupWindow.OnDismissListener {
        private lateinit var tvSelected: TextView
        private lateinit var ivArrow: ImageView

        private var popupWindow: ListPopupWindow? = null
        private var popupAdapter: SelectorAdapter? = null
        private val typeList: MutableList<String> = ArrayList()

        private lateinit var rotateAnim: Animation
        private lateinit var restoreAnim: Animation

        private var isOpen = false

        init {
            initView()
            initWidget()
            initClick()
        }

        private fun initView() {
            val binding = ViewSelectorBinding.inflate(LayoutInflater.from(context), this, false)
            val view = binding.root
            addView(view)

            tvSelected = binding.selectorTvSelected
            ivArrow = binding.selectorIvArrow
            ivArrow.scaleType = ImageView.ScaleType.MATRIX
        }

        private fun initWidget() {
            setUpAnim()
        }

        private fun setUpAnim() {
            rotateAnim = AnimationUtils.loadAnimation(context, R.anim.rotate_0_to_180)
            restoreAnim = AnimationUtils.loadAnimation(context, R.anim.rotate_180_to_360)

            rotateAnim.interpolator = LinearInterpolator()
            restoreAnim.interpolator = LinearInterpolator()
            rotateAnim.fillAfter = true
            restoreAnim.fillAfter = true
        }

        private fun openPopWindow() {
            if (popupWindow == null) {
                createPopWindow()
            }
            popupWindow!!.show()
        }

        private fun createPopWindow() {
            popupWindow = ListPopupWindow(context)
            popupAdapter = SelectorAdapter()
            popupWindow!!.anchorView = parentView.getChildAt(0)
            popupWindow!!.setAdapter(popupAdapter)
            popupWindow!!.width = WindowManager.LayoutParams.MATCH_PARENT
            popupWindow!!.height = WindowManager.LayoutParams.WRAP_CONTENT
            popupWindow!!.isModal = true

            popupWindow!!.setOnItemClickListener(this)
            popupWindow!!.setOnDismissListener(this)
        }

        private fun closePopWindow() {
            if (popupWindow != null && popupWindow!!.isShowing) {
                popupWindow!!.dismiss()
            }
        }

        private fun initClick() {
            setOnClickListener(this)
        }

        fun setData(types: List<String>) {
            typeList.addAll(types)
            tvSelected.text = typeList[0]
        }

        override fun onClick(v: View) {
            if (isOpen) {
                closePopWindow()
                isOpen = false
                ivArrow.startAnimation(restoreAnim)
            } else {
                openPopWindow()
                isOpen = true
                ivArrow.startAnimation(rotateAnim)
            }
        }

        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            tvSelected.text = typeList[position]
            if (mListener != null) {
                mListener!!.onItemSelected(tag as Int, position)
            }
            popupAdapter!!.current = position
            popupWindow!!.dismiss()
        }

        override fun onDismiss() {
            if (isOpen) {
                isOpen = false
                ivArrow.startAnimation(restoreAnim)
            }
        }

        private inner class SelectorAdapter : BaseAdapter() {
            var current = 0

            override fun getCount(): Int {
                return typeList.size
            }

            override fun getItem(position: Int): Any {
                return typeList[position]
            }

            override fun getItemId(position: Int): Long {
                return position.toLong()
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view: View
                val holder: ViewHolder
                if (convertView == null) {
                    val binding = ItemSelectorBinding.inflate(LayoutInflater.from(context), parent, false)
                    view = binding.root
                    holder = ViewHolder()
                    holder.tvName = binding.selectorTvType
                    view.tag = holder
                } else {
                    view = convertView
                    holder = view.tag as ViewHolder
                }
                if (current == position) {
                    holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.ink_primary))
                } else {
                    holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.nb_text_default))
                }
                holder.tvName.text = typeList[position]
                return view
            }

            private inner class ViewHolder {
                lateinit var tvName: TextView
            }
        }
    }
}
