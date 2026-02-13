package com.example.c2wdemo

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Predictive back handler that rotates the "book cover" around its left edge,
 * revealing a stat rail "spine" underneath.
 *
 * Uses [View.setRotationY] with [View.setPivotX] = 0 for GPU-accelerated
 * 3D perspective rotation via RenderNode.
 */
class BackSpineHandler(
    private val bookCover: View,
    private val statRailView: View,
    private val onFinish: () -> Unit,
) {
    companion object {
        private const val MAX_ROTATION = 25f
        private const val COMMIT_ROTATION = 90f
        private const val COMMIT_DURATION_MS = 300L
        private const val SCALE_REDUCTION = 0.08f
        private const val CAMERA_DISTANCE_FACTOR = 12f
    }

    private var cancelSpring: SpringAnimation? = null
    private var commitAnimator: ValueAnimator? = null

    val callback: OnBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            cancelInFlightAnimations()
            statRailView.visibility = View.VISIBLE
            bookCover.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            bookCover.pivotX = 0f
            bookCover.pivotY = bookCover.height / 2f
            bookCover.cameraDistance = CAMERA_DISTANCE_FACTOR * bookCover.resources.displayMetrics.density
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            val progress = backEvent.progress
            bookCover.rotationY = -(progress * MAX_ROTATION)
            val scale = 1f - progress * SCALE_REDUCTION
            bookCover.scaleX = scale
            bookCover.scaleY = scale
        }

        override fun handleOnBackCancelled() {
            // Spring back to resting position
            val startRotation = bookCover.rotationY
            val startScaleX = bookCover.scaleX
            val startScaleY = bookCover.scaleY

            cancelSpring = SpringAnimation(bookCover, DynamicAnimation.ROTATION_Y, 0f).apply {
                spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                spring.stiffness = SpringForce.STIFFNESS_MEDIUM
                addUpdateListener { _, value, _ ->
                    // Derive scale from rotation progress
                    val t = if (startRotation != 0f) value / startRotation else 0f
                    bookCover.scaleX = 1f - t * (1f - startScaleX)
                    bookCover.scaleY = 1f - t * (1f - startScaleY)
                }
                addEndListener { _, _, _, _ ->
                    resetBookCover()
                }
                start()
            }
        }

        override fun handleOnBackPressed() {
            val startRotation = bookCover.rotationY
            commitAnimator = ValueAnimator.ofFloat(startRotation, -COMMIT_ROTATION).apply {
                duration = COMMIT_DURATION_MS
                interpolator = AccelerateInterpolator()
                addUpdateListener { anim ->
                    val value = anim.animatedValue as Float
                    bookCover.rotationY = value
                    // Continue scaling down proportionally
                    val progress = (value - startRotation) / (-COMMIT_ROTATION - startRotation)
                    val scale = bookCover.scaleX - progress * 0.05f
                    bookCover.scaleX = scale.coerceAtLeast(0.8f)
                    bookCover.scaleY = scale.coerceAtLeast(0.8f)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onFinish()
                    }
                })
                start()
            }
        }
    }

    private fun cancelInFlightAnimations() {
        cancelSpring?.cancel()
        cancelSpring = null
        commitAnimator?.cancel()
        commitAnimator = null
    }

    private fun resetBookCover() {
        bookCover.rotationY = 0f
        bookCover.scaleX = 1f
        bookCover.scaleY = 1f
        bookCover.setLayerType(View.LAYER_TYPE_NONE, null)
        statRailView.visibility = View.GONE
    }

    fun destroy() {
        cancelInFlightAnimations()
        resetBookCover()
    }
}
