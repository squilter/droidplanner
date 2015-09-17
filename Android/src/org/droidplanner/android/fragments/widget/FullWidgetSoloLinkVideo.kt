package org.droidplanner.android.fragments.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.TextView
import com.o3dr.android.client.Drone
import com.o3dr.android.client.apis.GimbalApi
import com.o3dr.android.client.apis.solo.SoloCameraApi
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent
import com.o3dr.services.android.lib.drone.companion.solo.SoloAttributes
import com.o3dr.services.android.lib.drone.companion.solo.SoloEvents
import com.o3dr.services.android.lib.drone.companion.solo.tlv.SoloGoproState
import com.o3dr.services.android.lib.model.AbstractCommandListener
import org.droidplanner.android.R
import org.droidplanner.android.fragments.helpers.ApiListenerFragment
import timber.log.Timber
import kotlin.properties.Delegates

/**
 * Created by Fredia Huya-Kouadio on 7/19/15.
 */
public class FullWidgetSoloLinkVideo : TowerWidget() {

    companion object {
        private val filter = initFilter()

        private val TAG = FullWidgetSoloLinkVideo::class.java.simpleName

        private fun initFilter(): IntentFilter {
            val temp = IntentFilter()
            temp.addAction(AttributeEvent.STATE_CONNECTED)
            temp.addAction(SoloEvents.SOLO_GOPRO_STATE_UPDATED)
            return temp
        }
    }

    private val receiver = object : BroadcastReceiver(){
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action){
                AttributeEvent.STATE_CONNECTED -> {
                    tryStreamingVideo()
                    checkGoproControlSupport()
                }

                SoloEvents.SOLO_GOPRO_STATE_UPDATED -> {
                    checkGoproControlSupport()
                }
            }
        }

    }

    private var surfaceRef: Surface? = null

    private val textureView by lazy(LazyThreadSafetyMode.NONE) {
        view?.findViewById(R.id.sololink_video_view) as TextureView?
    }

    private val videoStatus by lazy(LazyThreadSafetyMode.NONE) {
        view?.findViewById(R.id.sololink_video_status) as TextView?
    }

    private val widgetButtonBar by lazy(LazyThreadSafetyMode.NONE) {
        view?.findViewById(R.id.widget_button_bar)
    }

    private val takePhotoButton by lazy(LazyThreadSafetyMode.NONE) {
        view?.findViewById(R.id.sololink_take_picture_button)
    }

    private val recordVideo by lazy(LazyThreadSafetyMode.NONE) {
        view?.findViewById(R.id.sololink_record_video_button)
    }

    private val orientationListener = object : GimbalApi.GimbalOrientationListener {
        override fun onGimbalOrientationUpdate(orientation: GimbalApi.GimbalOrientation){
        }

        override fun onGimbalOrientationCommandError(code: Int){
            Timber.e("command failed with error code: %d", code)
        }
    }

    override fun onCreateView(inflater: LayoutInflater?, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater?.inflate(R.layout.fragment_widget_sololink_video, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?){
        super.onViewCreated(view, savedInstanceState)

        textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                adjustAspectRatio(textureView as TextureView);
                surfaceRef = Surface(surface)
                tryStreamingVideo()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                surfaceRef = null
                tryStoppingVideoStream()
                return true
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
            }

        }

        takePhotoButton?.setOnClickListener {
            Timber.d("Taking photo.. cheeze!")
            val drone = drone
            if(drone != null) {
                //TODO: fix when camera control support is stable on sololink
                SoloCameraApi.getApi(drone).takePhoto(null)
            }
        }

        recordVideo?.setOnClickListener {
            Timber.d("Recording video!")
            val drone = drone
            if(drone != null){
                //TODO: fix when camera control support is stable on sololink
                SoloCameraApi.getApi(drone).toggleVideoRecording(null)
            }
        }
    }

    override fun onApiConnected() {
        tryStreamingVideo()
        checkGoproControlSupport()
        broadcastManager.registerReceiver(receiver, filter)
    }

    override fun onResume(){
        super.onResume()
        tryStreamingVideo()
    }

    override fun onPause(){
        super.onPause()
        tryStoppingVideoStream()
    }

    override fun onApiDisconnected() {
        tryStoppingVideoStream()
        checkGoproControlSupport()
        broadcastManager.unregisterReceiver(receiver)
    }

    override fun getWidgetType() = TowerWidgets.SOLO_VIDEO

    private fun tryStreamingVideo() {
        if(surfaceRef == null)
            return

        val drone = drone
        videoStatus?.visibility = View.GONE

        Timber.d("Starting video stream with tag %s", TAG)
        SoloCameraApi.getApi(drone).startVideoStream(surfaceRef, TAG, object : AbstractCommandListener() {
            override fun onError(error: Int) {
                Timber.d("Unable to start video stream: %d", error)
                textureView?.setOnTouchListener(null)
                videoStatus?.visibility = View.VISIBLE
            }

            override fun onSuccess() {
                videoStatus?.visibility = View.GONE
                Timber.d("Video stream started successfully")

                val gimbalTracker = object : View.OnTouchListener {
                    var startX: Float = 0f
                    var startY: Float = 0f
                    val gimbalApi = GimbalApi.getApi(drone)
                    val orientation = gimbalApi.gimbalOrientation
                    var pitch = orientation.pitch
                    var yaw = orientation.yaw

                    override fun onTouch(view: View, event: MotionEvent): Boolean {
                        val conversionX = view.width / 90
                        val conversionY = view.height / 90
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = event.x
                                startY = event.y
                                gimbalApi.startGimbalControl(orientationListener)
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val vX = event.x - startX
                                val vY = event.y - startY
                                pitch += vY / conversionX
                                yaw += vX / conversionY
                                //                        Timber.d("drag %f, %f", yaw, pitch)
                                gimbalApi.updateGimbalOrientation(pitch, yaw, orientation.roll, orientationListener)
                                startX = event.x
                                startY = event.y
                                pitch = Math.min(pitch, 0f)
                                pitch = Math.max(pitch, -90f)
                                return true
                            }
                            MotionEvent.ACTION_UP -> gimbalApi.stopGimbalControl(orientationListener)

                        }
                        return false
                    }
                }

                textureView?.setOnTouchListener(gimbalTracker)
            }

            override fun onTimeout() {
                Timber.d("Timed out while trying to start the video stream")
                textureView?.setOnTouchListener(null)
                videoStatus?.visibility = View.VISIBLE
            }

        })
    }

    private fun tryStoppingVideoStream(){
        val drone = drone

        Timber.d("Stopping video stream with tag %s", TAG)
        SoloCameraApi.getApi(drone).stopVideoStream(TAG, object : AbstractCommandListener(){
            override fun onError(error: Int) {
                Timber.d("Unable to stop video stream: %d", error)
            }

            override fun onSuccess() {
                Timber.d("Video streaming stopped successfully.")
            }

            override fun onTimeout() {
                Timber.d("Timed out while stopping video stream.")
            }

        })
    }

    private fun checkGoproControlSupport(){
        val goproState: SoloGoproState? = drone?.getAttribute(SoloAttributes.SOLO_GOPRO_STATE)
        widgetButtonBar?.visibility = if (goproState == null)
            View.GONE
        else
            View.VISIBLE
    }

    private fun adjustAspectRatio(textureView: TextureView){
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        val aspectRatio: Float = 9f/16f

        val newWidth: Int
        val newHeight: Int
        if(viewHeight > (viewWidth * aspectRatio)){
            //limited by narrow width; restrict height
            newWidth = viewWidth
            newHeight = (viewWidth * aspectRatio).toInt()
        }
        else{
            //limited by short height; restrict width
            newWidth = (viewHeight / aspectRatio).toInt();
            newHeight = viewHeight
        }

        val xoff = (viewWidth - newWidth) / 2f
        val yoff = (viewHeight - newHeight) / 2f

        val txform = Matrix();
        textureView.getTransform(txform);
        txform.setScale((newWidth.toFloat() / viewWidth), newHeight.toFloat() / viewHeight);

        txform.postTranslate(xoff, yoff);
        textureView.setTransform(txform);
    }
}