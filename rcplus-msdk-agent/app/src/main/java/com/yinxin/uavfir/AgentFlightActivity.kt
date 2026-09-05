package com.yinxin.uavfir

import android.os.Bundle
import android.view.ViewGroup
import com.yinxin.uavfir.firedetection.FireDetectionObservation
import com.yinxin.uavfir.firedetection.FireDetectionObservationBus
import com.yinxin.uavfir.firedetection.FireDetectionObservationListener
import com.yinxin.uavfir.ui.FireDetectionOverlayView
import dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity

/** Native DJI UXSDK flight page with a passive Agent AI observation overlay. */
class AgentFlightActivity : DefaultLayoutActivity(), FireDetectionObservationListener {
    private lateinit var fireOverlay: FireDetectionOverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        app.trialExpired.observe(this) { expired ->
            if (expired) finish()
        }
        if (app.isTrialExpired()) {
            finish()
            return
        }
        fireOverlay = FireDetectionOverlayView(this)
        fpvParentView.addView(
            fireOverlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onStart() {
        super.onStart()
        if ((application as App).isTrialExpired()) return
        FireDetectionObservationBus.addListener(this)
    }

    override fun onStop() {
        FireDetectionObservationBus.removeListener(this)
        super.onStop()
    }

    override fun onObservation(observation: FireDetectionObservation) {
        runOnUiThread { fireOverlay.update(observation) }
    }
}
