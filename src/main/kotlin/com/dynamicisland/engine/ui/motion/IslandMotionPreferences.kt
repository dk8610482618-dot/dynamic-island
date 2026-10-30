package com.dynamicisland.engine.ui.motion

import android.animation.ValueAnimator
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.dynamicisland.core.geometry.MotionPreset
import com.dynamicisland.engine.settings.BehaviorPreferences

@Composable
fun islandMotionSpec(): AnimationSpec<Float> {
    val options by BehaviorPreferences.get(LocalContext.current).options.collectAsState()
    return if(options.reducedMotion || options.motion == MotionPreset.NONE || !ValueAnimator.areAnimatorsEnabled()) tween(0)
    else spring(dampingRatio=1f,stiffness=if(options.motion==MotionPreset.RESPONSIVE) 900f else 450f)
}
