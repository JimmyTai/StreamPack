/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.thibaultbee.streampack.streamer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.github.thibaultbee.streampack.data.AudioConfig
import com.github.thibaultbee.streampack.data.Frame
import com.github.thibaultbee.streampack.data.Packet
import com.github.thibaultbee.streampack.data.VideoConfig
import com.github.thibaultbee.streampack.encoders.AudioMediaCodecEncoder
import com.github.thibaultbee.streampack.encoders.IEncoderListener
import com.github.thibaultbee.streampack.encoders.VideoMediaCodecEncoder
import com.github.thibaultbee.streampack.endpoints.IEndpoint
import com.github.thibaultbee.streampack.listeners.OnErrorListener
import com.github.thibaultbee.streampack.muxers.IMuxerListener
import com.github.thibaultbee.streampack.muxers.ts.TSMuxer
import com.github.thibaultbee.streampack.muxers.ts.data.ServiceInfo
import com.github.thibaultbee.streampack.sources.AudioCapture
import com.github.thibaultbee.streampack.sources.CameraCapture
import com.github.thibaultbee.streampack.utils.Error
import com.github.thibaultbee.streampack.utils.EventHandlerManager
import com.github.thibaultbee.streampack.utils.ILogger
import com.github.thibaultbee.streampack.utils.getCameraList
import java.nio.ByteBuffer

/**
 * Base class of CaptureStreamer: [CaptureFileStreamer] or [CaptureSrtLiveStreamer]
 * Use this class, only if you want to implement a custom [IEndpoint].
 *
 * @param context application context
 * @param tsServiceInfo MPEG-TS service description
 * @param endpoint a [IEndpoint] implementation
 * @param logger a [ILogger] implementation
 */
open class BaseCaptureStreamer(
    private val context: Context,
    private val tsServiceInfo: ServiceInfo,
    protected val endpoint: IEndpoint,
    logger: ILogger
) : EventHandlerManager() {
    /**
     * Listener that reports streamer error.
     * Supports only one listener.
     */
    override var onErrorListener: OnErrorListener? = null

    /**
     * Get/Set current camera id.
     */
    var camera: String
        /**
         * Get current camera id.
         *
         * @return a string that described current camera
         */
        get() = videoSource.cameraId
        /**
         * Set current camera id.
         *
         * @param value string that described the camera. Retrieves list of camera from [Context.getCameraList]
         */
        @RequiresPermission(Manifest.permission.CAMERA)
        set(value) {
            setCameraId(value)
        }

    private var audioTsStreamId: Short? = null
    private var videoTsStreamId: Short? = null

    // Keep video configuration
    private var videoConfig: VideoConfig? = null
    private var audioConfig: AudioConfig? = null

    private val onCodecErrorListener = object : OnErrorListener {
        override fun onError(source: String, message: String) {
            stopStream()
            onErrorListener?.onError(source, message)
        }
    }

    private val onCaptureErrorListener = object : OnErrorListener {
        override fun onError(source: String, message: String) {
            stopStreamImpl()
            stopPreview()
            onErrorListener?.onError(source, message)
        }
    }

    private val audioEncoderListener = object : IEncoderListener {
        override fun onInputFrame(buffer: ByteBuffer): Frame {
            return audioSource.getFrame(buffer)
        }

        override fun onOutputFrame(frame: Frame) {
            audioTsStreamId?.let {
                try {
                    tsMux.encode(frame, it)
                } catch (e: Exception) {
                    reportError(e)
                }
            }
        }
    }

    private val videoEncoderListener = object : IEncoderListener {
        override fun onInputFrame(buffer: ByteBuffer): Frame {
            // Not needed for video
            throw RuntimeException("No video input on VideoEncoder")
        }

        override fun onOutputFrame(frame: Frame) {
            videoTsStreamId?.let {
                try {
                    tsMux.encode(frame, it)
                } catch (e: Exception) {
                    reportError(e)
                }
            }
        }
    }

    private val muxListener = object : IMuxerListener {
        override fun onOutputFrame(packet: Packet) {
            try {
                endpoint.write(packet)
            } catch (e: Exception) {
                stopStream()
            }
        }
    }

    private val audioSource = AudioCapture(logger)
    private val videoSource = CameraCapture(context, onCaptureErrorListener, logger)

    private var audioEncoder =
        AudioMediaCodecEncoder(audioEncoderListener, onCodecErrorListener, logger)
    private var videoEncoder =
        VideoMediaCodecEncoder(videoEncoderListener, onCodecErrorListener, context, logger)

    private val tsMux = TSMuxer(muxListener)

    /**
     * Configures both video and audio settings.
     * It is the first method to call after a [BaseCaptureStreamer] instantiation.
     * It must be call when both stream and capture are not running.
     *
     * If video encoder does not support [VideoConfig.level] or [VideoConfig.profile], it fallbacks
     * to video encoder default level and default profile.
     *
     * Inside, it creates most of record and encoders object.
     *
     * @param audioConfig Audio configuration to set
     * @param videoConfig Video configuration to set
     *
     * @throws Exception if configuration can not be applied.
     * @see [release]
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun configure(audioConfig: AudioConfig, videoConfig: VideoConfig) {
        // Keep settings when we need to reconfigure
        this.videoConfig = videoConfig
        this.audioConfig = audioConfig

        try {
            audioSource.configure(audioConfig)
            audioEncoder.configure(audioConfig)
            videoSource.configure(videoConfig.fps)
            videoEncoder.configure(videoConfig)

            endpoint.configure(videoConfig.startBitrate + audioConfig.startBitrate)
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /**
     * Starts audio and video capture.
     * [BaseCaptureStreamer.configure] must have been called at least once.
     *
     * Inside, it launches both camera and microphone capture.
     *
     * @param previewSurface Where to display camera capture
     * @param cameraId camera id (get camera id list from [Context.getCameraList])
     *
     * @throws Exception if audio or video capture couldn't be launch
     * @see [stopPreview]
     */
    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA])
    fun startPreview(previewSurface: Surface, cameraId: String = "0") {
        require(audioConfig != null) { "Audio has not been configured!" }
        require(videoConfig != null) { "Video has not been configured!" }

        try {
            videoSource.previewSurface = previewSurface
            videoSource.encoderSurface = videoEncoder.inputSurface
            videoSource.startPreview(cameraId)

            audioSource.startStream()
        } catch (e: Exception) {
            stopPreview()
            throw e
        }
    }

    /**
     * Stops capture.
     * It also stops stream if the stream is running.
     *
     * @see [startPreview]
     */
    fun stopPreview() {
        stopStreamImpl()
        videoSource.stopPreview()
        audioSource.stopStream()
    }

    /**
     * Set camera id implementation.
     * It restarts camera if camera was already running.
     *
     * @see [cameraId]
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun setCameraId(cameraId: String) {
        val restartStream = videoSource.isStreaming
        videoSource.stopPreview()
        videoSource.startPreview(cameraId, restartStream)
    }

    /**
     * Starts audio/video stream.
     * Stream depends of the endpoint: Audio/video could be write to a file or send to a remote
     * device.
     * To avoid creating an unresponsive UI, do not call on main thread.
     *
     * @see [stopStream]
     */
    open fun startStream() {
        require(audioConfig != null) { "Audio has not been configured!" }
        require(videoConfig != null) { "Video has not been configured!" }
        require(videoEncoder.mimeType != null) { "Missing video encoder mime type! Encoder not configured?" }
        require(audioEncoder.mimeType != null) { "Missing audio encoder mime type! Encoder not configured?" }

        try {
            endpoint.startStream()

            val streams = mutableListOf<String>()
            videoEncoder.mimeType?.let { streams.add(it) }
            audioEncoder.mimeType?.let { streams.add(it) }

            tsMux.addService(tsServiceInfo)
            tsMux.addStreams(tsServiceInfo, streams)
            videoEncoder.mimeType?.let { videoTsStreamId = tsMux.getStreams(it)[0].pid }
            audioEncoder.mimeType?.let { audioTsStreamId = tsMux.getStreams(it)[0].pid }

            audioEncoder.startStream()
            videoSource.startStream()
            videoEncoder.startStream()
        } catch (e: Exception) {
            stopStream()
            throw e
        }
    }

    /**
     * Stops audio/video stream.
     *
     * Internally, it resets audio and video recorders and encoders to get them ready for another
     * [startStream] session. It explains why camera is restarted when calling this method.
     *
     * @see [startStream]
     */
    fun stopStream() {
        stopStreamImpl()

        // Encoder does not return to CONFIGURED state... so we have to reset everything for video...
        resetAudio()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            resetVideo()
        }
    }

    /**
     * Stops audio/video stream implementation.
     *
     * @see [stopStream]
     */
    private fun stopStreamImpl() {
        videoSource.stopStream()
        videoEncoder.stopStream()
        audioEncoder.stopStream()

        tsMux.stop()

        endpoint.stopStream()
    }

    /**
     * Prepares audio encoder for another session
     *
     * @see [stopStream]
     */
    private fun resetAudio(): Error {
        require(audioConfig != null) { "Audio has not been configured!" }

        audioEncoder.release()

        // Reconfigure
        audioEncoder.configure(audioConfig!!)
        return Error.SUCCESS
    }

    /**
     * Prepares camera and video encoder for another session
     *
     * @see [stopStream]
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun resetVideo() {
        require(audioConfig != null) { "Video has not been configured!" }

        videoSource.stopPreview()
        videoEncoder.release()

        // And restart...
        videoEncoder.configure(videoConfig!!)
        videoSource.encoderSurface = videoEncoder.inputSurface
        videoSource.startPreview()
    }

    /**
     * Releases recorders and encoders object.
     *
     * @see [configure]
     */
    fun release() {
        audioEncoder.release()
        videoEncoder.release()
        audioSource.release()
        videoSource.release()
        endpoint.release()
    }
}