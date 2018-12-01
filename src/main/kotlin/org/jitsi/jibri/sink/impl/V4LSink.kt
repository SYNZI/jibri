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

package org.jitsi.jibri.sink.impl

import org.jitsi.jibri.sink.Sink

/**
 * [V4LSink] represents a Video4Linux Loopback sink which will write
 * data to a video device.
 */
class V4LSink(val device: String) : Sink {
    override val path: String = device
    override val format: String = "v4l2"
    override val hasAudio: Boolean = false
    override val options: Array<String> = arrayOf(
        "-c:v", "rawvideo"
    )
}
