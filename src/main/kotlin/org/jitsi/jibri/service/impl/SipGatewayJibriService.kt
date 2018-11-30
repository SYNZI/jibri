/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.jitsi.jibri.service.impl

import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.selenium.JibriSelenium
import org.jitsi.jibri.selenium.JibriSeleniumOptions
import org.jitsi.jibri.selenium.SIP_GW_URL_OPTIONS
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceStatus
import org.jitsi.jibri.sipgateway.SipClient
import org.jitsi.jibri.sipgateway.SipClientParams
import org.jitsi.jibri.sipgateway.pjsua.PjsuaClient
import org.jitsi.jibri.sipgateway.pjsua.PjsuaClientParams
import org.jitsi.jibri.util.ProcessMonitor
import org.jitsi.jibri.util.extensions.error
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

import org.jitsi.jibri.capture.Capturer
import org.jitsi.jibri.capture.ffmpeg.FfmpegCapturer
import org.jitsi.jibri.sink.Sink
import org.jitsi.jibri.sink.impl.V4LSink

data class SipGatewayServiceParams(
    /**
     * The params needed to join the web call
     */
    val callParams: CallParams,
    /**
     * The params needed for bringing a SIP client into
     * the call
     */
    val sipClientParams: SipClientParams
)

/**
 * A [JibriService] responsible for joining both a web call
 * and a SIP call, capturing the audio and video from each, and
 * forwarding thenm to the other side.
 */
class SipGatewayJibriService(
    private val sipGatewayServiceParams: SipGatewayServiceParams,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val conferenceCaptureDeviceName: String,
    private val sipCaptureDeviceName: String,
    private val conferenceDisplayId: Int,
    private val sipDisplayId: Int,
    private val conferenceCapturer: Capturer = FfmpegCapturer(),
    private val siCapturer: Capturer = FfmpegCapturer()
) : JibriService() {
    /**
     * The [Logger] for this class
     */
    private val logger = Logger.getLogger(this::class.qualifiedName)
    /**
     * Used for the selenium interaction
     */
    private val jibriSelenium = JibriSelenium(
        JibriSeleniumOptions(
            displayName = sipGatewayServiceParams.sipClientParams.displayName,
            extraChromeCommandLineFlags = listOf("--alsa-input-device=plughw:1,1"))
    )
    /**
     * The [Sink] this class will use to model the conference capture video device
     */
    private var conferenceSink: Sink
    /**
     * The [Sink] this class will use to model the capture device to use for incoming sip video
     */
    private var sipSink: Sink
    /**
     * The SIP client we'll use to connect to the SIP call (currently only a
     * pjsua implementation exists)
     */
    private val pjsuaClient = PjsuaClient(PjsuaClientParams(sipGatewayServiceParams.sipClientParams))

    /**
     * The handle to the scheduled process monitor task, which we use to
     * cancel the task
     */
    private var processMonitorTask: ScheduledFuture<*>? = null
    /**
    * Monitor the the conference capture task - if this goes down we will want 
    * to attempt to restart it. If it can't be restarted, we should shut down the call. 
    */
    private var conferenceCapturerMonitorTask: ScheduledFuture<*>? = null
    /**
    * Monitor the capture of the sip device - if this goes down we will want
    * to attempt to restart it. If it can't be restarted, we should shut down the call. 
    */
    private var sipCapturerMonitorTask: ScheduledFuture<*>? = null

    init {

        logger.info("Capturing conference from ${conferenceCaptureDeviceName}")
        logger.info("Capturing sip device from ${sipCaptureDeviceName}")

        conferenceSink = V4LSink(
            conferenceCaptureDeviceName
        )

        sipSink = V4LSink(
            sipCaptureDeviceName
        )

        jibriSelenium.addStatusHandler {
            publishStatus(it)
        }
    }

    /**
     * Starting a [SipGatewayServiceParams] involves the following steps:
     * 1) Start selenium and join the web call on display :0
     * 2) Start the SIP client to join the SIP call on display :1
     * There are already ffmpeg daemons running which are capturing from
     * each of the displays and writing to video devices which selenium
     * and pjsua will use
     */
    override fun start(): Boolean {
        if (!jibriSelenium.joinCall(
                sipGatewayServiceParams.callParams.callUrlInfo.copy(urlParams = SIP_GW_URL_OPTIONS))
        ) {
            logger.error("Selenium failed to join the call")
            return false
        }
        if (!pjsuaClient.start()) {
            logger.error("Pjsua failed to start")
            return false
        }
        if (!conferenceCapturer.start(conferenceSink, conferenceDisplayId)) {
            logger.error("Conference Capturer failed to start")
            return false
        }
        if (!sipCapturer.start(sipSink, sipDisplayId)) {
            logger.error("Sip Device Capturer failed to start")
            return false
        }

        processMonitorTask = executor.scheduleAtFixedRate(createSipClientMonitor(pjsuaClient), 30, 10, TimeUnit.SECONDS)

        conferenceCapturerMonitorTask = executor.scheduleAtFixedRate(createCaptureMonitor(conferenceCapturer), 30, 10, TimeUnit.SECONDS)
        sipCapturerMonitorTask = executor.scheduleAtFixedRate(createCaptureMonitor(sipCapturer), 30, 10, TimeUnit.SECONDS)

        return true
    }

    private fun createSipClientMonitor(process: SipClient): ProcessMonitor {
        return ProcessMonitor(process) { exitCode ->
            when (exitCode) {
                null -> {
                    logger.error("SipClient process is still running but no longer healthy")
                    publishStatus(JibriServiceStatus.ERROR)
                }
                0 -> {
                    logger.info("SipClient remote side hung up")
                    publishStatus(JibriServiceStatus.FINISHED)
                }
                2 -> {
                    logger.info("SipClient remote side busy")
                    publishStatus(JibriServiceStatus.ERROR)
                }
                else -> {
                    logger.info("Sip client exited with code $exitCode")
                    publishStatus(JibriServiceStatus.ERROR)
                }
            }
        }
    }

    private fun createCaptureMonitor(process: Capturer): ProcessMonitor {
        var numRestarts = 0
        var sink = process.sink;

        return ProcessMonitor(process) { exitCode ->
            if (exitCode != null) {
                logger.error("[SipGateway - ${sink.path}] Capturer process is no longer healthy.  It exited with code $exitCode")
            } else {
                logger.error("[SipGateway - ${sink.path}] Capturer process is no longer healthy but it is still running, stopping it now")
            }
            if (numRestarts == FFMPEG_RESTART_ATTEMPTS) {
                logger.error("[SipGateway - ${sink.path}] Giving up on restarting the capturer")
                publishStatus(JibriServiceStatus.ERROR)
            } else {
                logger.info("[SipGateway - ${sink.path}] Trying to restart capturer")
                numRestarts++
                process.stop()
                if (!process.start(sink)) {
                    logger.error("[SipGateway - ${sink.path}] Capture failed to restart, giving up")
                    publishStatus(JibriServiceStatus.ERROR)
                }
            }
        }
    }

    override fun stop() {
        processMonitorTask?.cancel(false)
        conferenceCapturerMonitorTask?.cancel(false);
        sipCapturerMonitorTask?.cancel(false);

        pjsuaClient.stop()
        conferenceCapturer.stop();
        sipCapturer.stop();

        jibriSelenium.leaveCallAndQuitBrowser()
    }
}
