package com.fosifix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var logText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val logEntries = ArrayDeque<String>()
    private val maxLogEntries = 8

    private var permissionRequestedThisSession = false
    private var payloadSentForCurrentConnection = false
    private var connectFlowRunning = false
    private var countdownRunnable: Runnable? = null

    private val usbReceiver = UsbReceiver(
        onAttached = { device ->
            appendLog("Amplifier connected")
            payloadSentForCurrentConnection = false
            handleDeviceFound(device)
        },
        onDetached = {
            appendLog("Amplifier disconnected")
            cancelCountdown()
            connectFlowRunning = false
            payloadSentForCurrentConnection = false
            setStatus(Status.WAITING)
        },
    )

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return

            val device = intent.usbDevice() ?: return
            if (!UsbReceiver.isTargetDevice(device)) return

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                appendLog("USB permission granted")
                startConnectFlow(device)
            } else {
                appendLog("USB permission denied")
                connectFlowRunning = false
                setStatus(Status.WAITING)
            }
        }
    }

    private enum class Status(val emoji: String, val label: String) {
        WAITING("🔴", "Waiting — amp not connected"),
        CONNECTING("🟡", "Connecting — waiting for MCU boot"),
        ACTIVE("🟢", "Active — payload sent successfully"),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        statusText = findViewById(R.id.statusText)
        countdownText = findViewById(R.id.countdownText)
        logText = findViewById(R.id.logText)

        appendLog("App opened")
        setStatus(Status.WAITING)
    }

    override fun onResume() {
        super.onResume()

        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, usbFilter)

        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, permissionFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, permissionFilter)
        }

        scanAndHandleDevice()

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            intent.getParcelableExtraSafe(UsbManager.EXTRA_DEVICE)?.let { device ->
                if (UsbReceiver.isTargetDevice(device)) {
                    appendLog("Launched from USB attach")
                    payloadSentForCurrentConnection = false
                    handleDeviceFound(device)
                }
            }
            intent.action = null
        }
    }

    override fun onPause() {
        super.onPause()
        cancelCountdown()
        if (!payloadSentForCurrentConnection) {
            connectFlowRunning = false
        }
        unregisterReceiver(usbReceiver)
        unregisterReceiver(permissionReceiver)
    }

    private fun scanAndHandleDevice() {
        val device = findTargetDevice()
        if (device == null) {
            if (!connectFlowRunning) {
                setStatus(Status.WAITING)
            }
            return
        }
        handleDeviceFound(device)
    }

    private fun findTargetDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { UsbReceiver.isTargetDevice(it) }
    }

    private fun handleDeviceFound(device: UsbDevice) {
        if (payloadSentForCurrentConnection || connectFlowRunning) return

        if (usbManager.hasPermission(device)) {
            startConnectFlow(device)
            return
        }

        if (permissionRequestedThisSession) return

        permissionRequestedThisSession = true
        appendLog("Requesting USB permission")
        setStatus(Status.CONNECTING)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }

        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            flags,
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun startConnectFlow(device: UsbDevice) {
        if (payloadSentForCurrentConnection || connectFlowRunning) return

        connectFlowRunning = true
        setStatus(Status.CONNECTING)
        appendLog("Waiting 3s for MCU boot")

        var secondsLeft = 3
        countdownText.visibility = View.VISIBLE
        countdownText.text = "Boot countdown: ${secondsLeft}s"

        countdownRunnable = object : Runnable {
            override fun run() {
                secondsLeft--
                if (secondsLeft > 0) {
                    countdownText.text = "Boot countdown: ${secondsLeft}s"
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    countdownText.visibility = View.GONE
                    sendPayloadToDevice(device)
                }
            }
        }
        mainHandler.postDelayed(countdownRunnable!!, 1000L)
    }

    private fun sendPayloadToDevice(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            appendLog("USB permission denied")
            connectFlowRunning = false
            setStatus(Status.WAITING)
            return
        }

        val success = trySendPayload(device)
        connectFlowRunning = false

        if (success) {
            payloadSentForCurrentConnection = true
            appendLog("Payload sent successfully")
            setStatus(Status.ACTIVE)
        } else {
            appendLog("No endpoint accepted payload")
            setStatus(Status.WAITING)
        }
    }

    private fun trySendPayload(device: UsbDevice): Boolean {
        val connection = usbManager.openDevice(device) ?: return false
        val payload = buildPayload()
        var anySuccess = false

        try {
            for (interfaceIndex in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(interfaceIndex)
                if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                if (!connection.claimInterface(usbInterface, true)) continue

                try {
                    anySuccess = anySuccess || writeToInterface(connection, usbInterface, payload)
                } finally {
                    connection.releaseInterface(usbInterface)
                }
            }
        } finally {
            connection.close()
        }

        return anySuccess
    }

    private fun writeToInterface(
        connection: android.hardware.usb.UsbDeviceConnection,
        usbInterface: UsbInterface,
        payload: ByteArray,
    ): Boolean {
        var anySuccess = false

        for (endpointIndex in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            if (endpoint.direction != UsbConstants.USB_DIR_OUT) continue

            try {
                val sent = when (endpoint.type) {
                    UsbConstants.USB_ENDPOINT_XFER_INT,
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                        connection.bulkTransfer(endpoint, payload, payload.size, 5000)
                    }
                    else -> -1
                }
                if (sent == payload.size) {
                    anySuccess = true
                }
            } catch (_: Exception) {
                // Expected for read-only endpoints; ignore silently.
            }
        }

        if (!anySuccess) {
            try {
                val result = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01,
                    0x09,
                    0x0200,
                    usbInterface.id,
                    payload,
                    payload.size,
                    5000,
                )
                if (result == payload.size) {
                    anySuccess = true
                }
            } catch (_: Exception) {
                // Ignore control transfer failures per endpoint.
            }
        }

        return anySuccess
    }

    private fun buildPayload(): ByteArray {
        val payload = ByteArray(65)
        val header = byteArrayOf(
            0x00,
            0xA5.toByte(),
            0x5A.toByte(),
            0x88.toByte(),
            0x0B.toByte(),
            0xFF.toByte(),
            0x00,
            0x00,
            0x70.toByte(),
            0xE5.toByte(),
            0x03,
            0x00,
            0x05,
            0x00,
            0x64,
            0x00,
            0x16,
        )
        System.arraycopy(header, 0, payload, 0, header.size)
        return payload
    }

    private fun setStatus(status: Status) {
        statusText.text = "${status.emoji} ${status.label}"
        if (status != Status.CONNECTING) {
            countdownText.visibility = View.GONE
        }
    }

    private fun appendLog(message: String) {
        val entry = "${timeFormat.format(Date())} — $message"
        logEntries.addFirst(entry)
        while (logEntries.size > maxLogEntries) {
            logEntries.removeLast()
        }
        logText.text = logEntries.joinToString("\n")
    }

    private fun cancelCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        countdownText.visibility = View.GONE
    }

    private fun Intent.usbDevice(): UsbDevice? = getParcelableExtraSafe(UsbManager.EXTRA_DEVICE)

    private fun Intent.getParcelableExtraSafe(name: String): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.fosifix.USB_PERMISSION"
    }
}
