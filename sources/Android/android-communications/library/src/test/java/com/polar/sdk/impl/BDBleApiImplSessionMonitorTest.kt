package com.polar.sdk.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.core.util.Pair
import com.polar.androidcommunications.api.ble.BleDeviceListener
import com.polar.androidcommunications.api.ble.model.BleDeviceSession
import com.polar.androidcommunications.api.ble.model.BleDeviceSession.DeviceSessionState
import com.polar.androidcommunications.api.ble.model.advertisement.BleAdvertisementContent
import com.polar.androidcommunications.enpoints.ble.bluedroid.host.BDScanCallback
import com.polar.sdk.api.PolarBleApiCallback
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The session state monitor in [BDBleApiImpl] delivers every connection callback. Reconnecting a
 * device whose session closed must be observed, and the closed state must be reported only once.
 */
class BDBleApiImplSessionMonitorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        BDBleApiImpl.clearInstance()
        Dispatchers.setMain(UnconfinedTestDispatcher())

        mockkStatic(ParcelUuid::class)
        every { ParcelUuid.fromString(any()) } returns mockk(relaxed = true)
        mockkConstructor(IntentFilter::class)
        every { anyConstructed<IntentFilter>().addAction(any()) } just runs
        mockkConstructor(ScanFilter.Builder::class)
        every { anyConstructed<ScanFilter.Builder>().setServiceUuid(any()) } answers { self as ScanFilter.Builder }
        every { anyConstructed<ScanFilter.Builder>().setServiceUuid(null) } answers { self as ScanFilter.Builder }
        every { anyConstructed<ScanFilter.Builder>().setManufacturerData(any(), any()) } answers { self as ScanFilter.Builder }
        every { anyConstructed<ScanFilter.Builder>().build() } returns mockk(relaxed = true)
        mockkConstructor(BDScanCallback::class)
        every { anyConstructed<BDScanCallback>().powerOn() } just runs
        every { anyConstructed<BDScanCallback>().powerOff() } just runs

        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        every { bluetoothManager.adapter } returns mockk<BluetoothAdapter>(relaxed = true)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { context.registerReceiver(any(), any<IntentFilter>()) } returns null
    }

    @After
    fun tearDown() {
        BDBleApiImpl.clearInstance()
        Dispatchers.resetMain()
        unmockkStatic(ParcelUuid::class)
        unmockkConstructor(IntentFilter::class)
        unmockkConstructor(ScanFilter.Builder::class)
        unmockkConstructor(BDScanCallback::class)
    }

    @Test
    fun `reconnecting after the session closed delivers deviceConnected`() = runTest {
        val device = connectedDevice()
        device.moveTo(DeviceSessionState.SESSION_CLOSED)
        testScheduler.runCurrent()

        connect(device)

        verify(exactly = 2) { device.callback.deviceConnected(any()) }
        verify(exactly = 1) { device.callback.deviceDisconnected(any(), any()) }
    }

    @Test
    fun `reconnecting before the close was processed delivers deviceConnected`() = runTest {
        val device = connectedDevice()
        device.moveTo(DeviceSessionState.SESSION_CLOSED)

        connect(device)

        verify(exactly = 2) { device.callback.deviceConnected(any()) }
        verify(exactly = 1) { device.callback.deviceDisconnected(any(), any()) }
    }

    @Test
    fun `monitor relaunched after cleanup does not report the replayed SESSION_CLOSED`() = runTest {
        val device = connectedDevice()
        device.moveTo(DeviceSessionState.SESSION_CLOSED)
        testScheduler.runCurrent()
        device.api.cleanup()

        connect(device)

        verify(exactly = 1) { device.callback.deviceDisconnected(any(), any()) }
        verify(exactly = 2) { device.callback.deviceConnected(any()) }
    }

    /** An API with a fake listener and [DEVICE_ID] connected through connectToDevice(). */
    private fun TestScope.connectedDevice(): FakeDevice {
        val api = BDBleApiImpl.getInstance(context, emptySet())
        // The monitor never completes on its own; backgroundScope cancels it when the test ends.
        api.javaClass.getDeclaredField("apiScope").also { it.isAccessible = true }.set(api, backgroundScope)
        val device = FakeDevice(api)
        api.javaClass.getDeclaredField("listener").also { it.isAccessible = true }.set(api, device.listener)
        api.setApiCallback(device.callback)
        connect(device)
        return device
    }

    /**
     * connectToDevice(), with the session opening only after the monitor has processed what it
     * already had: the interleaving in which the monitor's dispatcher beats the connection handler.
     */
    private fun TestScope.connect(device: FakeDevice) {
        device.api.connectToDevice(DEVICE_ID)
        testScheduler.runCurrent()
        device.moveTo(DeviceSessionState.SESSION_OPENING)
        device.moveTo(DeviceSessionState.SESSION_OPEN)
        testScheduler.runCurrent()
    }

    private class FakeDevice(val api: BDBleApiImpl) {
        val callback = mockk<PolarBleApiCallback>(relaxed = true)
        val listener = mockk<BleDeviceListener>(relaxed = true)
        private val session = mockk<BleDeviceSession>(relaxed = true)
        // Same configuration as the session state flow in BDDeviceListenerImpl.
        private val states = MutableSharedFlow<Pair<BleDeviceSession, DeviceSessionState>>(replay = 1, extraBufferCapacity = 64)
        private var state = DeviceSessionState.SESSION_CLOSED
        private var previous = DeviceSessionState.SESSION_CLOSED

        init {
            every { session.advertisementContent } returns BleAdvertisementContent().apply { processName("Polar 360 $DEVICE_ID") }
            every { session.polarDeviceId } returns DEVICE_ID
            every { session.address } returns ADDRESS
            every { session.name } returns "Polar 360 $DEVICE_ID"
            every { session.polarDeviceType } returns "360"
            every { session.disconnectReason } returns BleDeviceSession.DisconnectReason.CONNECTION_LOST
            every { session.disconnectStatus } returns 0
            every { session.sessionState } answers { state }
            every { session.previousState } answers { previous }
            every { listener.monitorDeviceSessionState() } returns states
            every { listener.deviceSessions() } returns setOf(session)
        }

        /** What ConnectionHandler.updateSessionState() does: update the session, then emit. */
        fun moveTo(next: DeviceSessionState) {
            previous = state
            state = next
            states.tryEmit(Pair(session, next))
        }
    }

    private companion object {
        const val DEVICE_ID = "12345678"
        const val ADDRESS = "AA:BB:CC:00:00:01"
    }
}
