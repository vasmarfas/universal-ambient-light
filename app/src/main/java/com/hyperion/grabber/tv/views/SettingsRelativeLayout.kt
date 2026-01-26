package com.vasmarfas.UniversalAmbientLight.tv.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout

/**
 * Relative layout implementation that lays out child views based on provided keyline percent(
 * distance of TitleView baseline from the top).
 *
 * Repositioning child views in PreDraw callback in [androidx.leanback.widget.GuidanceStylist] was interfering with
 * fragment transition. To avoid that, we do that in the onLayout pass.
 *
 * Nino: Copied from Leanback code, changed to align icon bottom with description bottom
 */
class SettingsRelativeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : RelativeLayout(context, attrs, defStyle) {
    private val mTitleKeylinePercent: Float = getKeyLinePercent(context)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

        val mTitleView = rootView.findViewById<View>(androidx.leanback.R.id.guidance_title)
        val mBreadcrumbView = rootView.findViewById<View>(androidx.leanback.R.id.guidance_breadcrumb)
        val mDescriptionView = rootView.findViewById<View>(androidx.leanback.R.id.guidance_description)
        val mIconView = rootView.findViewById<ImageView>(androidx.leanback.R.id.guidance_icon)
        val mTitleKeylinePixels = (measuredHeight * mTitleKeylinePercent / 100).toInt()

        if (mTitleView != null && mTitleView.parent === this) {
            val titleViewBaseline = mTitleView.baseline
            val mBreadcrumbViewHeight = mBreadcrumbView?.measuredHeight ?: 0
            val guidanceTextContainerTop = mTitleKeylinePixels - titleViewBaseline - mBreadcrumbViewHeight - mTitleView.paddingTop
            val offset = guidanceTextContainerTop - (mBreadcrumbView?.top ?: 0)

            if (mBreadcrumbView != null && mBreadcrumbView.parent === this) {
                mBreadcrumbView.offsetTopAndBottom(offset)
            }

            mTitleView.offsetTopAndBottom(offset)

            if (mDescriptionView != null && mDescriptionView.parent === this) {
                mDescriptionView.offsetTopAndBottom(offset)
            }
        }

        if (mIconView != null && mIconView.parent === this) {
            val drawable = mIconView.drawable
            if (drawable != null && mDescriptionView != null) {
                val iconOffset = mDescriptionView.bottom - mIconView.bottom
                mIconView.offsetTopAndBottom(
                    iconOffset
                )
            }
        }
    }

    companion object {

        fun getKeyLinePercent(context: Context): Float {
            val ta = context.theme.obtainStyledAttributes(
                androidx.leanback.R.styleable.LeanbackGuidedStepTheme
            )
            val percent = ta.getFloat(androidx.leanback.R.styleable.LeanbackGuidedStepTheme_guidedStepKeyline, 40f)
            ta.recycle()
            return percent
        }
    }
}
