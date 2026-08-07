/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.webrtc.aec3.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val controller = Aec3LoopbackController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Aec3LoopbackScreen(
                    controller = controller,
                    onToggle = { toggleProcessing() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.stop()
    }

    private fun toggleProcessing() {
        if (controller.isRunning) {
            controller.stop()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO,
                )
                return
            }
            controller.start()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        deviceId: Int,
    ) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                controller.start()
            } else {
                controller.status = "Microphone permission denied"
            }
        }
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 100
    }
}

@Composable
fun Aec3LoopbackScreen(
    controller: Aec3LoopbackController,
    onToggle: () -> Unit,
) {
    // mutableStateOf-backed properties are already observable by Compose, so a
    // plain read is enough to trigger recomposition on change.
    val isRunning = controller.isRunning
    val aecEnabled = controller.aecEnabled
    val delayMs = controller.delayMs
    val status = controller.status
    val error = controller.error

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "WebRTC AEC3 Loopback",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Plays a 440 Hz reference tone through the speaker, records the " +
                "microphone, and cancels the acoustic echo in real time.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        // ---- AEC switch ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Echo cancellation",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = aecEnabled,
                onCheckedChange = { controller.aecEnabled = it },
                modifier = Modifier.testTag("aecSwitch"),
            )
        }

        Spacer(Modifier.height(16.dp))

        HorizontalDivider()

        Spacer(Modifier.height(16.dp))

        // ---- Delay slider ----
        Text(
            text = "Delay: $delayMs ms",
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = delayMs.toFloat(),
            onValueChange = { controller.setDelay(it.toInt()) },
            valueRange = 0f..200f,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(24.dp))

        // ---- Status ----
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        error?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Error: $message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        // ---- Start / Stop ----
        if (isRunning) {
            OutlinedButton(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("startStopButton"),
            ) {
                Text("Stop")
            }
        } else {
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("startStopButton"),
            ) {
                Text("Start")
            }
        }
    }
}
